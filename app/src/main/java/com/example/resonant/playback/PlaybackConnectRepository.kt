package com.example.resonant.playback

import android.content.Context
import android.os.Build
import com.example.resonant.BuildConfig
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.PlaybackConnectCommandAckDTO
import com.example.resonant.data.network.PlaybackConnectCommandDTO
import com.example.resonant.data.network.PlaybackConnectDeviceDTO
import com.example.resonant.data.network.PlaybackConnectHeartbeatRequestDTO
import com.example.resonant.data.network.PlaybackConnectSnapshotDTO
import com.example.resonant.data.network.PlaybackConnectStateDTO
import com.example.resonant.data.network.PlaybackTransferRequestDTO
import com.example.resonant.data.network.V2EndpointAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID

/**
 * Emitted when the user taps a song on this device while another Connect
 * device is already the active player.  The Activity collects this and
 * shows the "Ahora suena en X – ¿Escucharlo aquí?" dialog.
 *
 * @param activeDeviceName  Human-readable name of the device currently playing.
 * @param activeDeviceId    Backend id of that device (used to diff after user confirms).
 * @param nowPlayingTitle   Title of the track currently playing there, or null.
 * @param pendingActionTag  Opaque token the Service uses to retrieve the pending play
 *                          intent; the Activity echoes it back via
 *                          [MusicPlaybackService.ACTION_PLAY_CONNECT_CONFIRMED].
 */
data class ConnectConflictEvent(
    val activeDeviceName: String,
    val activeDeviceId: String,
    val nowPlayingTitle: String?,
    val pendingActionTag: String
)

data class PlaybackConnectUiState(
    val supported: Boolean = false,
    val refreshing: Boolean = false,
    val localDeviceId: String,
    val activeDeviceId: String? = null,
    val revision: Long? = null,
    val devices: List<PlaybackConnectDeviceDTO> = emptyList(),
    val errorMessage: String? = null
) {
    val activeDevice: PlaybackConnectDeviceDTO?
        get() = devices.firstOrNull { it.deviceId == activeDeviceId }

    val hasAlternativeDevice: Boolean
        get() = devices.any {
            it.isOnline && it.deviceId != localDeviceId
        }
}

data class PlaybackTransferResult(
    val successful: Boolean,
    val message: String
)

class PlaybackConnectRepository private constructor(
    private val context: Context
) {
    private val service = ApiClient.getPlaybackConnectService(context)
    private val preferences = context.getSharedPreferences(
        DEVICE_PREFERENCES,
        Context.MODE_PRIVATE
    )
    val localDeviceId: String = preferences
        .getString(KEY_DEVICE_ID, null)
        ?.takeIf(String::isNotBlank)
        ?: UUID.randomUUID().toString().also { generated ->
            preferences.edit().putString(KEY_DEVICE_ID, generated).apply()
        }

    private val requestMutex = Mutex()
    private val repositoryScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastUploadedQueueRevision: Long? = null
    @Volatile
    private var scopedUserId: String? = currentUserId()

    /**
     * Consecutive heartbeat/refresh failures.  Connect is considered
     * degraded (but not dead) while this is >0 and fully unavailable
     * only when it crosses [MAX_CONSECUTIVE_FAILURES].  Any successful
     * response resets this to 0.
     */
    @Volatile
    private var consecutiveFailures: Int = 0

    private val _uiState = MutableStateFlow(
        PlaybackConnectUiState(localDeviceId = localDeviceId)
    )
    val uiState = _uiState.asStateFlow()

    /**
     * One-shot events signalling a playback conflict.  The Activity collects
     * this with [SharedFlow] (replay = 0) so each event is delivered exactly
     * once to any active collector, then discarded.
     */
    private val _conflictEvents = MutableSharedFlow<ConnectConflictEvent>(
        replay = 0,
        extraBufferCapacity = 4
    )
    val conflictEvents: SharedFlow<ConnectConflictEvent> = _conflictEvents.asSharedFlow()

    /** Emit a conflict event from any coroutine or thread. */
    fun emitConflict(event: ConnectConflictEvent) {
        repositoryScope.launch { _conflictEvents.emit(event) }
    }

    suspend fun heartbeat(
        playback: PlaybackConnectStateDTO?
    ): List<PlaybackConnectCommandDTO> = requestMutex.withLock {
        if (!synchronizeUserScope()) {
            return@withLock emptyList()
        }
        // Only block on the hard "unsupported" gate (405/501 seen previously).
        // Transient failures are handled via consecutiveFailures counter.
        if (!shouldTryFeature()) {
            return@withLock emptyList()
        }
        val outgoingPlayback = playback?.let { state ->
            if (state.queueRevision == lastUploadedQueueRevision) {
                state.copy(queueItems = null)
            } else {
                state
            }
        }
        val response = runCatching {
            service.heartbeat(
                PlaybackConnectHeartbeatRequestDTO(
                    deviceId = localDeviceId,
                    deviceName = localDeviceName(),
                    appVersion = BuildConfig.VERSION_NAME,
                    capabilities = CAPABILITIES,
                    playback = outgoingPlayback
                )
            )
        }.getOrElse { error ->
            recordFailure()
            publishTransientError(error)
            return@withLock emptyList()
        }

        val code = response.code()

        // 405/501 = backend genuinely doesn't implement the endpoint.
        // Only THESE codes trigger the hard 2-minute cooldown.
        if (code == 405 || code == 501) {
            markUnsupported()
            return@withLock emptyList()
        }

        val body = response.body()
        if (!response.isSuccessful || body == null) {
            recordFailure()
            // Don't wipe UI state — keep the last good device list visible.
            // Only show a transient message if we've failed multiple times.
            if (consecutiveFailures >= 2) {
                _uiState.value = _uiState.value.copy(
                    refreshing = false,
                    errorMessage = "Reconectando…"
                )
            }
            return@withLock emptyList()
        }

        // ── Success ──
        recordSuccess()
        if (
            playback != null &&
            (
                body.acceptedQueueRevision == playback.queueRevision ||
                    outgoingPlayback?.queueItems != null
                )
        ) {
            lastUploadedQueueRevision = playback.queueRevision
        }
        publishSnapshot(body.snapshot)
        body.commands
    }

    fun refreshAsync() {
        repositoryScope.launch { refresh() }
    }

    /**
     * Announces this device's presence to the backend right away without
     * waiting for the periodic heartbeat or blocking on [requestMutex].
     *
     * This uses a fast path: if the mutex is free it will run immediately;
     * if a heartbeat is already in flight we fire-and-forget from a new
     * coroutine so the UI caller is never blocked. The mutex is still
     * acquired inside [heartbeat] to avoid concurrent writes, but we don't
     * let that block the calling UI thread.
     */
    fun announcePresenceAsync() {
        repositoryScope.launch {
            // Use tryLock first — if a heartbeat is already in flight, skip
            // this presence ping (the heartbeat itself will carry presence).
            if (requestMutex.tryLock()) {
                try {
                    if (!synchronizeUserScope() || !shouldTryFeature()) return@launch
                    runCatching {
                        service.heartbeat(
                            PlaybackConnectHeartbeatRequestDTO(
                                deviceId = localDeviceId,
                                deviceName = localDeviceName(),
                                appVersion = BuildConfig.VERSION_NAME,
                                capabilities = CAPABILITIES,
                                playback = null
                            )
                        )
                    }.onSuccess { response ->
                        val body = response.body()
                        if (response.isSuccessful && body != null) {
                            recordSuccess()
                            publishSnapshot(body.snapshot)
                        }
                    }
                } finally {
                    requestMutex.unlock()
                }
            }
            // If mutex was busy a heartbeat is already in progress — no need
            // to queue another presence ping.
        }
    }

    suspend fun refresh() = requestMutex.withLock {
        if (!synchronizeUserScope() || !shouldTryFeature()) return@withLock
        _uiState.value = _uiState.value.copy(refreshing = true)
        val response = runCatching { service.getDevices() }
            .getOrElse { error ->
                recordFailure()
                // Keep last good device list — only update the error message.
                _uiState.value = _uiState.value.copy(
                    refreshing = false,
                    errorMessage = if (consecutiveFailures >= 2) "Reconectando…" else null
                )
                return@withLock
            }

        val code = response.code()
        if (code == 405 || code == 501) {
            markUnsupported()
            return@withLock
        }
        val snapshot = response.body()
        if (!response.isSuccessful || snapshot == null) {
            recordFailure()
            _uiState.value = _uiState.value.copy(
                refreshing = false,
                errorMessage = if (consecutiveFailures >= 2) "Reconectando…" else null
            )
            return@withLock
        }
        recordSuccess()
        publishSnapshot(snapshot)
    }

    suspend fun transferTo(
        targetDeviceId: String,
        currentPlayback: PlaybackConnectStateDTO? = null
    ): PlaybackTransferResult =
        requestMutex.withLock {
            if (targetDeviceId == _uiState.value.activeDeviceId) {
                return@withLock PlaybackTransferResult(
                    successful = true,
                    message = "La reproducción ya está en este dispositivo"
                )
            }
            if (!synchronizeUserScope() || !shouldTryFeature()) {
                return@withLock PlaybackTransferResult(
                    successful = false,
                    message = "La conexión entre dispositivos todavía no está disponible"
                )
            }
            
            val idempotencyKey = UUID.randomUUID().toString()
            var currentRevision = _uiState.value.revision

            for (attempt in 1..2) {
                val response = runCatching {
                    service.transfer(
                        PlaybackTransferRequestDTO(
                            sourceDeviceId =
                                _uiState.value.activeDeviceId ?: localDeviceId,
                            targetDeviceId = targetDeviceId,
                            expectedRevision = currentRevision,
                            idempotencyKey = idempotencyKey,
                            playback = currentPlayback
                        )
                    )
                }.getOrElse {
                    return@withLock PlaybackTransferResult(
                        successful = false,
                        message = "No se pudo contactar con el otro dispositivo"
                    )
                }

                if (response.code() == 405 || response.code() == 501) {
                    markUnsupported()
                    return@withLock PlaybackTransferResult(
                        successful = false,
                        message = "Actualiza el servidor para activar Resonant Connect"
                    )
                }

                if (response.code() == 409 || response.code() == 412) {
                    if (attempt == 1) {
                        val refreshResponse = runCatching { service.getDevices() }.getOrNull()
                        if (refreshResponse != null && refreshResponse.isSuccessful) {
                            val snapshot = refreshResponse.body()
                            if (snapshot != null) {
                                publishSnapshot(snapshot)
                                currentRevision = snapshot.revision
                                continue
                            }
                        }
                    }
                }

                val body = response.body()
                if (!response.isSuccessful || body == null || !body.accepted) {
                    return@withLock PlaybackTransferResult(
                        successful = false,
                        message = when (response.code()) {
                            404 -> "El dispositivo ya no está disponible"
                            409, 412 -> "La sesión cambió; vuelve a intentarlo"
                            else -> "No se pudo transferir la reproducción"
                        }
                    )
                }
                publishSnapshot(body.snapshot)
                return@withLock PlaybackTransferResult(
                    successful = true,
                    message = "Transfiriendo reproducción"
                )
            }
            
            return@withLock PlaybackTransferResult(
                successful = false,
                message = "La sesión cambió; vuelve a intentarlo"
            )
        }

    suspend fun acknowledge(
        command: PlaybackConnectCommandDTO,
        successful: Boolean,
        errorCode: String? = null
    ) {
        runCatching {
            service.acknowledgeCommand(
                command.commandId,
                PlaybackConnectCommandAckDTO(
                    deviceId = localDeviceId,
                    status = if (successful) "applied" else "failed",
                    errorCode = errorCode
                )
            )
        }
    }

    fun wasCommandProcessed(commandId: String): Boolean = synchronized(preferences) {
        preferences.getStringSet(KEY_PROCESSED_COMMANDS, emptySet())
            .orEmpty()
            .contains(commandId)
    }

    fun markCommandProcessed(commandId: String) = synchronized(preferences) {
        val existing = preferences
            .getStringSet(KEY_PROCESSED_COMMANDS, emptySet())
            .orEmpty()
            .toMutableList()
        existing.remove(commandId)
        existing.add(commandId)
        preferences.edit()
            .putStringSet(
                KEY_PROCESSED_COMMANDS,
                existing.takeLast(MAX_PROCESSED_COMMANDS).toSet()
            )
            .apply()
    }

    /**
     * Forces the next heartbeat to include the full queueItems payload
     * instead of eliding it as a no-change delta.  Call this right before
     * a transfer so the backend has up-to-date queue content and
     * currentIndex to relay to the target device.
     */
    fun forceFullQueueUpload() {
        lastUploadedQueueRevision = null
    }

    fun synchronizeUserScope(): Boolean = synchronized(this) {
        val currentUserId = currentUserId()
        if (currentUserId != scopedUserId) {
            scopedUserId = currentUserId
            lastUploadedQueueRevision = null
            _uiState.value = PlaybackConnectUiState(
                localDeviceId = localDeviceId
            )
        }
        !currentUserId.isNullOrBlank()
    }

    private fun publishSnapshot(snapshot: PlaybackConnectSnapshotDTO) {
        _uiState.value = PlaybackConnectUiState(
            supported = true,
            refreshing = false,
            localDeviceId = localDeviceId,
            activeDeviceId = snapshot.activeDeviceId,
            revision = snapshot.revision,
            devices = snapshot.devices.sortedWith(
                compareByDescending<PlaybackConnectDeviceDTO> { it.isActive }
                    .thenByDescending { it.isOnline }
                    .thenBy { it.name.lowercase() }
            ),
            errorMessage = null
        )
    }

    private fun publishTransientError(error: Throwable) {
        // Keep the last good state (device list, supported flag) intact.
        // Only show a message if we've seen multiple consecutive failures.
        recordFailure()
        if (consecutiveFailures >= 2) {
            _uiState.value = _uiState.value.copy(
                refreshing = false,
                errorMessage = if (error is IOException) "Sin conexión" else "Reconectando…"
            )
        }
    }

    private fun publishHttpError(code: Int) {
        _uiState.value = _uiState.value.copy(
            refreshing = false,
            errorMessage = when (code) {
                401 -> "Vuelve a iniciar sesión"
                403 -> "Acceso no permitido"
                else -> null // transient — don't scare the user
            }
        )
    }

    /**
     * Records a failed heartbeat/refresh attempt.  If the failure count
     * crosses [MAX_CONSECUTIVE_FAILURES], Connect is marked as unavailable
     * in V2EndpointAvailability (but with a short cooldown so recovery is
     * fast once the server is back).
     */
    private fun recordFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            // Use a short cooldown (16s ≈ 4 heartbeats) instead of the
            // default 2 minutes so we recover quickly once the server is up.
            val prefs = context.applicationContext
                .getSharedPreferences("v2_endpoint_availability", Context.MODE_PRIVATE)
            prefs.edit()
                .putLong(
                    "unsupported_until:${V2EndpointAvailability.FEATURE_CONNECT}",
                    System.currentTimeMillis() + SHORT_COOLDOWN_MS
                )
                .apply()
            _uiState.value = _uiState.value.copy(
                supported = false,
                refreshing = false,
                errorMessage = "Resonant Connect no disponible temporalmente"
            )
        }
    }

    /**
     * Resets the failure counter and marks Connect as available.
     */
    private fun recordSuccess() {
        if (consecutiveFailures > 0) {
            consecutiveFailures = 0
        }
        V2EndpointAvailability.markAvailable(
            context,
            V2EndpointAvailability.FEATURE_CONNECT
        )
    }

    private fun markUnsupported() {
        V2EndpointAvailability.markUnsupported(
            context,
            V2EndpointAvailability.FEATURE_CONNECT
        )
        _uiState.value = PlaybackConnectUiState(
            supported = false,
            localDeviceId = localDeviceId
        )
    }

    private fun shouldTryFeature(): Boolean =
        V2EndpointAvailability.shouldTry(
            context,
            V2EndpointAvailability.FEATURE_CONNECT
        )

    private fun currentUserId(): String? = context
        .getSharedPreferences("user_data", Context.MODE_PRIVATE)
        .getString("USER_ID", null)

    private fun localDeviceName(): String {
        val manufacturer = Build.MANUFACTURER
            .orEmpty()
            .trim()
            .replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty().trim()
        return listOf(manufacturer, model)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" ")
            .take(MAX_DEVICE_NAME_LENGTH)
            .ifBlank { "Android" }
    }

    companion object {
        private const val DEVICE_PREFERENCES = "playback_connect_device"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PROCESSED_COMMANDS = "processed_commands"
        private const val MAX_DEVICE_NAME_LENGTH = 80
        private const val MAX_PROCESSED_COMMANDS = 64
        /**
         * Number of consecutive heartbeat/refresh failures before Connect
         * is considered unavailable.  At ~4s per heartbeat this is ~16s.
         */
        private const val MAX_CONSECUTIVE_FAILURES = 4
        /** Short cooldown (16s) used after hitting MAX_CONSECUTIVE_FAILURES.
         *  Much shorter than the 2-min default for truly unsupported endpoints. */
        private const val SHORT_COOLDOWN_MS = 16_000L
        private val CAPABILITIES = listOf(
            "presence-v1",
            "transfer-v1",
            "queue-snapshot-v1"
        )

        @Volatile
        private var instance: PlaybackConnectRepository? = null

        fun get(context: Context): PlaybackConnectRepository =
            instance ?: synchronized(this) {
                instance ?: PlaybackConnectRepository(
                    context.applicationContext
                ).also { instance = it }
            }
    }
}
