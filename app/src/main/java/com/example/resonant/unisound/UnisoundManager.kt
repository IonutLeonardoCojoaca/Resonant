package com.example.resonant.unisound

import android.content.Context
import android.util.Log
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.ApiErrorParser
import com.example.resonant.data.network.UnisoundCodeDTO
import com.example.resonant.data.network.UnisoundJoinRequestDTO
import com.example.resonant.data.network.UnisoundPlaybackStateDTO
import com.example.resonant.data.network.UnisoundQueueItemDTO
import com.example.resonant.data.network.UnisoundSessionSnapshotDTO
import com.example.resonant.data.network.UnisoundSessionSummaryDTO
import com.example.resonant.data.network.UnisoundSettingsUpdateDTO
import com.example.resonant.data.network.UnisoundSourceDTO
import com.example.resonant.data.network.UnisoundTransferHostRequestDTO
import com.example.resonant.data.network.services.UnisoundService
import com.example.resonant.managers.CapabilitiesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import java.util.UUID

/** Why this client is no longer in a room. */
enum class UnisoundCloseReason { LEFT, ENDED, KICKED, GONE, UNAVAILABLE }

sealed class UnisoundManagerEvent {
    data class Message(val text: String) : UnisoundManagerEvent()
    object BecameHost : UnisoundManagerEvent()
    data class Closed(
        val reason: UnisoundCloseReason,
        val summary: UnisoundSessionSummaryDTO? = null
    ) : UnisoundManagerEvent()
}

sealed class UnisoundResult<out T> {
    data class Success<T>(val value: T) : UnisoundResult<T>()
    data class Failure(val code: String?, val message: String) : UnisoundResult<Nothing>()
}

/**
 * Owns the Unisound room of this app: REST calls, the hub connection (only while in a room),
 * the server clock and the room state. The player follows [state]; screens also listen to
 * [events] for one-shot messages and the end of the room.
 */
class UnisoundManager internal constructor(
    private val service: UnisoundService,
    private val gateway: UnisoundHubGateway,
    private val myUserId: () -> String?,
    private val isFeatureEnabled: suspend () -> Boolean,
    private val scope: CoroutineScope,
    private val deviceNowMs: () -> Long = System::currentTimeMillis,
    private val newKey: () -> String = { "and-" + UUID.randomUUID().toString().replace("-", "") },
    private val log: (String) -> Unit = {}
) : UnisoundHubListener {

    val clock = ServerClock(deviceNowMs)

    private val stateMutex = Mutex()
    private val _state = MutableStateFlow<UnisoundRoomState?>(null)
    val state: StateFlow<UnisoundRoomState?> = _state.asStateFlow()

    private val _events = MutableSharedFlow<UnisoundManagerEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UnisoundManagerEvent> = _events.asSharedFlow()

    /** Songs seen in the last room, kept after it closes for the summary screen. */
    @Volatile
    var lastRoomSongs: Map<String, Song> = emptyMap()
        private set

    private var connectJob: Job? = null
    private var clockJob: Job? = null
    private var reconnectAttempt = 0

    init {
        gateway.listener = this
    }

    fun currentSessionIdOrNull(): String? = _state.value?.sessionId

    fun canAddNow(): Boolean = _state.value?.canAdd == true

    // region Room lifecycle (REST)

    suspend fun createRoom(): UnisoundResult<UnisoundRoomState> {
        val response = request { service.createSession() }
            ?: return failure(null, UnisoundErrorContext.GENERAL, CREATE_FAILED)
        if (response.isSuccessful) return enterFrom(response.body())
        val code = ApiErrorParser.parse(response)?.code
        if (code == UnisoundErrors.ALREADY_IN_SESSION) return openCurrentRoom()
        return failure(code, UnisoundErrorContext.GENERAL, CREATE_FAILED)
    }

    suspend fun joinRoom(rawCode: String): UnisoundResult<UnisoundRoomState> {
        val code = UnisoundJoinCode.normalizedOrNull(rawCode)
            ?: return UnisoundResult.Failure(UnisoundErrors.CODE_INVALID, UnisoundErrors.message(UnisoundErrors.CODE_INVALID, UnisoundErrorContext.JOIN))
        val response = request { service.join(UnisoundJoinRequestDTO(code)) }
            ?: return failure(null, UnisoundErrorContext.JOIN)
        if (response.isSuccessful) return enterFrom(response.body())
        val errorCode = ApiErrorParser.parse(response)?.code
            ?: if (response.code() == 429) UnisoundErrors.RATE_LIMITED else null
        if (errorCode == UnisoundErrors.ALREADY_IN_SESSION) return openCurrentRoom()
        return failure(errorCode, UnisoundErrorContext.JOIN)
    }

    /**
     * Restores the room after a process restart or app start. Audio stays on local hold until
     * the user asks for it. Does nothing (and makes no request) with the feature off.
     */
    suspend fun restore(): Boolean {
        if (!isFeatureEnabled()) return false
        val response = request { service.getCurrentSession() } ?: return _state.value != null
        val snapshot = response.body()
        if (response.code() == 204 || (response.isSuccessful && snapshot == null)) {
            if (_state.value != null) close(UnisoundCloseReason.GONE)
            return false
        }
        if (!response.isSuccessful || snapshot == null) return _state.value != null
        if (_state.value?.sessionId == snapshot.sessionId) return true
        enter(snapshot, localHold = true)
        return true
    }

    /** Leaves for good. Always leaves locally; a failed request is retried once later. */
    suspend fun leaveRoom() {
        val sessionId = currentSessionIdOrNull() ?: return
        close(UnisoundCloseReason.LEFT)
        val response = request { service.leave(sessionId) }
        if (response == null || response.code() >= 500) {
            scope.launch {
                delay(LEAVE_RETRY_DELAY_MS)
                request { service.leave(sessionId) }
            }
        }
    }

    suspend fun endRoom(): UnisoundResult<UnisoundSessionSummaryDTO> {
        val sessionId = currentSessionIdOrNull() ?: return failure(null, UnisoundErrorContext.HOST_ACTION)
        val response = request { service.end(sessionId) }
            ?: return failure(null, UnisoundErrorContext.HOST_ACTION)
        val summary = response.body()
        if (!response.isSuccessful || summary == null) return restFailure(response, UnisoundErrorContext.HOST_ACTION)
        close(UnisoundCloseReason.ENDED, summary)
        return UnisoundResult.Success(summary)
    }

    suspend fun updateSettings(update: UnisoundSettingsUpdateDTO): UnisoundResult<Unit> =
        snapshotCall { sessionId -> service.updateSettings(sessionId, update) }

    suspend fun transferHost(userId: String): UnisoundResult<Unit> =
        snapshotCall { sessionId -> service.transferHost(sessionId, UnisoundTransferHostRequestDTO(userId)) }

    suspend fun regenerateCode(): UnisoundResult<UnisoundCodeDTO> {
        val sessionId = currentSessionIdOrNull() ?: return failure(null, UnisoundErrorContext.HOST_ACTION)
        val response = request { service.regenerateCode(sessionId) }
            ?: return failure(null, UnisoundErrorContext.HOST_ACTION)
        val code = response.body()
        if (!response.isSuccessful || code == null) return restFailure(response, UnisoundErrorContext.HOST_ACTION)
        updateState { it.copy(code = code.code, joinUrl = code.joinUrl) }
        return UnisoundResult.Success(code)
    }

    suspend fun kick(userId: String): UnisoundResult<Unit> {
        val sessionId = currentSessionIdOrNull() ?: return failure(null, UnisoundErrorContext.HOST_ACTION)
        val response = request { service.kick(sessionId, userId) }
            ?: return failure(null, UnisoundErrorContext.HOST_ACTION)
        return if (response.isSuccessful) UnisoundResult.Success(Unit)
        else restFailure(response, UnisoundErrorContext.HOST_ACTION)
    }

    // endregion

    // region Commands (hub)

    /** Entry point for every transport control while in a room (app, system, Aria). */
    fun control(control: UnisoundControl, positionMs: Long? = null, origin: UnisoundControlOrigin) {
        when (val route = UnisoundControlRouter.route(control, _state.value, positionMs)) {
            UnisoundRoute.Ignored -> Unit
            UnisoundRoute.ReengageLocal -> setLocalHold(false)
            is UnisoundRoute.Denied -> message(route.message)
            is UnisoundRoute.SendCommand -> scope.launch {
                log("command ${route.control} from $origin")
                sendCommand(route)
            }
        }
    }

    suspend fun addSong(songId: String): UnisoundResult<List<UnisoundQueueItemDTO>> =
        hubCall(UnisoundErrorContext.ADD) { sessionId, key ->
            gateway.addItems(sessionId, UnisoundSourceDTO(type = SOURCE_SONG, id = songId), key)
        }

    suspend fun removeItem(itemId: String): UnisoundResult<Unit> =
        hubCall(UnisoundErrorContext.COMMAND) { sessionId, key -> gateway.removeItem(sessionId, itemId, key) }

    /** Local-only pause/resume of this device; never sent to the hub. */
    fun setLocalHold(hold: Boolean) {
        if (_state.value?.localHold == hold) return
        scope.launch { updateState { it.copy(localHold = hold) } }
    }

    /** One-shot message for the visible screen (e.g. from the playback service). */
    fun notify(text: String) = message(text)

    /** The app came back to the foreground after [backgroundMs] in the background. */
    fun onForeground(backgroundMs: Long) {
        if (backgroundMs < FOREGROUND_RESYNC_MS) return
        val revision = _state.value?.revision ?: return
        scope.launch { resync(revision) }
    }

    private suspend fun sendCommand(route: UnisoundRoute.SendCommand) {
        val result = hubCall(UnisoundErrorContext.COMMAND) { sessionId, key ->
            val revision = _state.value?.revision ?: 0L
            when (route.control) {
                UnisoundControl.PLAY -> gateway.play(sessionId, revision, key)
                UnisoundControl.PAUSE -> gateway.pause(sessionId, route.positionMs ?: 0L, revision, key)
                UnisoundControl.SEEK -> gateway.seek(sessionId, route.positionMs ?: 0L, revision, key)
                UnisoundControl.NEXT -> gateway.skip(sessionId, UnisoundHubGateway.SKIP_NEXT, revision, key)
                UnisoundControl.PREVIOUS -> gateway.skip(sessionId, UnisoundHubGateway.SKIP_PREVIOUS, revision, key)
            }
        }
        if (result is UnisoundResult.Success<UnisoundPlaybackStateDTO>) {
            if (route.control == UnisoundControl.PLAY) setLocalHold(false)
            dispatch(UnisoundEvent.PlaybackChanged(result.value))
        }
    }

    /**
     * Runs one logical hub operation: a fresh idempotency key, one retry with the same key on
     * transient failures, and the shared error handling. Messages are emitted here so callers
     * only need the result.
     */
    private suspend fun <T> hubCall(
        context: UnisoundErrorContext,
        block: suspend (sessionId: String, key: String) -> T
    ): UnisoundResult<T> {
        val sessionId = currentSessionIdOrNull() ?: return failure(null, context)
        if (_state.value?.connection != UnisoundConnection.CONNECTED) {
            return UnisoundResult.Failure(null, UnisoundErrors.MESSAGE_RECONNECTING).also { message(it.message) }
        }
        val key = newKey()
        var lastCode: String? = null
        repeat(2) { attempt ->
            try {
                return UnisoundResult.Success(block(sessionId, key))
            } catch (error: Throwable) {
                lastCode = UnisoundErrors.codeOf(error)
                val transient = lastCode == null || UnisoundErrors.isRetryable(lastCode)
                if (!transient || attempt == 1) return handleHubFailure(lastCode, context)
            }
        }
        return handleHubFailure(lastCode, context)
    }

    private suspend fun handleHubFailure(code: String?, context: UnisoundErrorContext): UnisoundResult.Failure {
        val text = UnisoundErrors.message(code, context)
        when {
            // The server already sends the fresh snapshot to this connection.
            code == UnisoundErrors.STALE_REVISION -> Unit
            UnisoundErrors.endsRoom(code) -> close(closeReasonFor(code))
            else -> message(text)
        }
        return UnisoundResult.Failure(code, text)
    }

    // endregion

    // region Hub connection

    override fun onEvent(event: UnisoundEvent) {
        scope.launch { dispatch(event) }
    }

    override fun onClosed(error: Throwable?) {
        if (_state.value == null) return
        log("hub closed: ${error?.javaClass?.simpleName}")
        scope.launch {
            updateState { it.copy(connection = UnisoundConnection.RECONNECTING) }
            scheduleConnect()
        }
    }

    private suspend fun enterFrom(snapshot: UnisoundSessionSnapshotDTO?): UnisoundResult<UnisoundRoomState> {
        if (snapshot == null) return failure(null, UnisoundErrorContext.GENERAL)
        return UnisoundResult.Success(enter(snapshot, localHold = false))
    }

    private suspend fun openCurrentRoom(): UnisoundResult<UnisoundRoomState> {
        val response = request { service.getCurrentSession() }
        val snapshot = response?.body()
        if (response == null || !response.isSuccessful || snapshot == null) {
            return failure(null, UnisoundErrorContext.GENERAL)
        }
        if (_state.value?.sessionId == snapshot.sessionId) return UnisoundResult.Success(_state.value!!)
        return UnisoundResult.Success(enter(snapshot, localHold = false))
    }

    private suspend fun enter(snapshot: UnisoundSessionSnapshotDTO, localHold: Boolean): UnisoundRoomState {
        val previous = _state.value
        if (previous != null && previous.sessionId != snapshot.sessionId) close(UnisoundCloseReason.LEFT)
        clock.seedFromServerTime(snapshot.serverTimeUtcMs)
        val next = stateMutex.withLock {
            UnisoundRoomState.fromSnapshot(snapshot, myUserId())
                .copy(localHold = localHold, connection = UnisoundConnection.CONNECTING)
                .also { _state.value = it }
        }
        reconnectAttempt = 0
        scheduleConnect()
        startClockRefresh()
        return next
    }

    private fun scheduleConnect() {
        if (connectJob?.isActive == true) return
        val delayMs = RECONNECT_DELAYS_MS[reconnectAttempt.coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)]
        reconnectAttempt++
        connectJob = scope.launch {
            delay(delayMs)
            connectAndJoin()
        }
    }

    private suspend fun connectAndJoin() {
        val sessionId = currentSessionIdOrNull() ?: return
        try {
            gateway.connect()
            syncClock()
            val revision = _state.value?.revision ?: 0L
            val snapshot = gateway.joinRoom(sessionId, revision)
            if (currentSessionIdOrNull() != sessionId) return
            dispatch(UnisoundEvent.Snapshot(snapshot))
            updateState { it.copy(connection = UnisoundConnection.CONNECTED) }
            reconnectAttempt = 0
            log("joined hub at revision ${snapshot.state?.revision}, offset ${clock.offsetMs} ms, rtt ${clock.roundTripMs} ms")
        } catch (error: Throwable) {
            val code = UnisoundErrors.codeOf(error)
            log("connect failed: ${code ?: error.javaClass.simpleName}")
            if (UnisoundErrors.endsRoom(code)) {
                close(closeReasonFor(code))
                return
            }
            updateState { it.copy(connection = UnisoundConnection.RECONNECTING) }
            connectJob = null
            scheduleConnect()
        }
    }

    private suspend fun syncClock() {
        val samples = (1..CLOCK_SAMPLES).mapNotNull {
            runCatching {
                val sentAt = deviceNowMs()
                val serverTime = gateway.syncClock()
                ServerClock.Sample(sentAt, serverTime, deviceNowMs())
            }.getOrNull()
        }
        clock.applySamples(samples)
    }

    private fun startClockRefresh() {
        if (clockJob?.isActive == true) return
        clockJob = scope.launch {
            while (isActive && _state.value != null) {
                delay(CLOCK_REFRESH_MS)
                if (gateway.isConnected) syncClock()
            }
        }
    }

    private suspend fun resync(lastRevision: Long) {
        val sessionId = currentSessionIdOrNull() ?: return
        if (!gateway.isConnected) return
        try {
            dispatch(UnisoundEvent.Snapshot(gateway.joinRoom(sessionId, lastRevision)))
        } catch (error: Throwable) {
            val code = UnisoundErrors.codeOf(error)
            if (UnisoundErrors.endsRoom(code)) close(closeReasonFor(code))
        }
    }

    private suspend fun dispatch(event: UnisoundEvent) {
        val effects = stateMutex.withLock {
            val current = _state.value ?: return
            val reduction = UnisoundRoomReducer.reduce(current, event)
            if (reduction.state != null) _state.value = reduction.state
            reduction.effects
        }
        effects.forEach { effect ->
            when (effect) {
                is UnisoundEffect.ResyncNeeded -> scope.launch { resync(effect.lastRevision) }
                is UnisoundEffect.Ended -> close(UnisoundCloseReason.ENDED, effect.summary)
                is UnisoundEffect.Kicked -> close(UnisoundCloseReason.KICKED, effect.summary)
                UnisoundEffect.BecameHost -> _events.tryEmit(UnisoundManagerEvent.BecameHost)
                UnisoundEffect.PlaybackUpdated -> Unit
            }
        }
    }

    private suspend fun close(reason: UnisoundCloseReason, summary: UnisoundSessionSummaryDTO? = null) {
        val sessionId = stateMutex.withLock {
            val closing = _state.value ?: return
            lastRoomSongs = closing.knownItems.values
                .mapNotNull(UnisoundQueueItemDTO::song)
                .associateBy(Song::id)
            _state.value = null
            closing.sessionId
        }
        log("left room $sessionId: $reason")
        connectJob?.cancel()
        connectJob = null
        clockJob?.cancel()
        clockJob = null
        reconnectAttempt = 0
        clock.reset()
        scope.launch {
            if (reason == UnisoundCloseReason.LEFT) runCatching { gateway.leaveRoom(sessionId) }
            gateway.disconnect()
        }
        _events.tryEmit(UnisoundManagerEvent.Closed(reason, summary))
        if (reason == UnisoundCloseReason.UNAVAILABLE || reason == UnisoundCloseReason.GONE) {
            message(if (reason == UnisoundCloseReason.UNAVAILABLE) UnisoundErrors.MESSAGE_NOT_AVAILABLE else UnisoundErrors.MESSAGE_ROOM_ENDED)
        }
    }

    // endregion

    // region Helpers

    private suspend fun snapshotCall(
        block: suspend (sessionId: String) -> Response<UnisoundSessionSnapshotDTO>
    ): UnisoundResult<Unit> {
        val sessionId = currentSessionIdOrNull() ?: return failure(null, UnisoundErrorContext.HOST_ACTION)
        val response = request { block(sessionId) } ?: return failure(null, UnisoundErrorContext.HOST_ACTION)
        val snapshot = response.body()
        if (!response.isSuccessful || snapshot == null) return restFailure(response, UnisoundErrorContext.HOST_ACTION)
        dispatch(UnisoundEvent.Snapshot(snapshot))
        return UnisoundResult.Success(Unit)
    }

    /** Null on network failure. */
    private suspend fun <T> request(block: suspend () -> Response<T>): Response<T>? =
        runCatching { block() }
            .onFailure { log("request failed: ${it.javaClass.simpleName}") }
            .getOrNull()

    private suspend fun restFailure(response: Response<*>, context: UnisoundErrorContext): UnisoundResult.Failure {
        val code = ApiErrorParser.parse(response)?.code
        if (UnisoundErrors.endsRoom(code)) close(closeReasonFor(code))
        return failure(code, context)
    }

    private fun failure(
        code: String?,
        context: UnisoundErrorContext,
        fallback: String? = null
    ): UnisoundResult.Failure {
        val text = if (code == null && fallback != null) fallback else UnisoundErrors.message(code, context)
        return UnisoundResult.Failure(code, text)
    }

    private suspend fun updateState(transform: (UnisoundRoomState) -> UnisoundRoomState) {
        stateMutex.withLock { _state.value?.let { _state.value = transform(it) } }
    }

    private fun message(text: String) {
        _events.tryEmit(UnisoundManagerEvent.Message(text))
    }

    private fun closeReasonFor(code: String?): UnisoundCloseReason =
        if (code == UnisoundErrors.NOT_AVAILABLE) UnisoundCloseReason.UNAVAILABLE else UnisoundCloseReason.GONE

    // endregion

    companion object {
        private const val TAG = "Unisound"
        private const val SOURCE_SONG = "song"
        private const val CREATE_FAILED = "No se ha podido crear la sala. Inténtalo de nuevo"
        const val CLOCK_SAMPLES = 5
        const val CLOCK_REFRESH_MS = 5 * 60 * 1000L
        const val FOREGROUND_RESYNC_MS = 30_000L
        const val LEAVE_RETRY_DELAY_MS = 5_000L
        val RECONNECT_DELAYS_MS = longArrayOf(0L, 2_000L, 5_000L, 10_000L, 30_000L)

        @Volatile
        private var instance: UnisoundManager? = null

        fun get(context: Context): UnisoundManager =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        private fun create(context: Context): UnisoundManager = UnisoundManager(
            service = ApiClient.getUnisoundService(context),
            gateway = UnisoundHubClient(context),
            myUserId = {
                context.getSharedPreferences("user_data", Context.MODE_PRIVATE).getString("USER_ID", null)
            },
            isFeatureEnabled = {
                CapabilitiesManager.refresh(context)
                CapabilitiesManager.isUnisoundEnabled()
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            log = { Log.d(TAG, it) }
        )
    }
}
