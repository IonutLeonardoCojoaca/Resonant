package com.example.resonant.live

import android.content.Context
import android.util.Log
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.ApiErrorParser
import com.example.resonant.data.network.LiveChannelInfoDTO
import com.example.resonant.data.network.LiveChannelsResponseDTO
import com.example.resonant.data.network.LiveNowResponseDTO
import com.example.resonant.data.network.services.LiveService
import com.example.resonant.data.network.services.PlatformService
import com.example.resonant.managers.CapabilitiesManager
import com.example.resonant.unisound.ServerClock
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response

/** The broadcast this device is tuned to. Null state means "not in Live". */
data class LiveSessionState(
    val slug: String,
    val channel: LiveChannelInfoDTO,
    val timeline: LiveTimeline,
    val status: LiveListenStatus
)

sealed class LiveManagerEvent {
    data class Message(val text: String) : LiveManagerEvent()

    /** Left the broadcast; [message] explains why when it was not the user's choice. */
    data class Closed(val message: String?) : LiveManagerEvent()
}

sealed class LiveNowResult {
    data class Success(val now: LiveNowResponseDTO) : LiveNowResult()
    data class Failure(val failure: LiveFailure, val message: String) : LiveNowResult()
}

/**
 * Owns the Live broadcast of this app: REST calls, its server clock (sampled with `GET /api/time`)
 * and the known schedule. The player follows [state]; screens also listen to [events].
 * Nothing here changes the broadcast for anyone else: it is read-only on the server.
 */
class LiveManager internal constructor(
    private val service: LiveService,
    private val platform: PlatformService,
    /** Refreshes the capabilities (bypassing the cache with `force`) and tells whether Live is on. */
    private val isFeatureEnabled: suspend (force: Boolean) -> Boolean,
    private val scope: CoroutineScope,
    private val deviceNowMs: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {}
) {
    val clock = ServerClock(deviceNowMs)

    private val _state = MutableStateFlow<LiveSessionState?>(null)
    val state: StateFlow<LiveSessionState?> = _state.asStateFlow()

    private val _events = MutableSharedFlow<LiveManagerEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<LiveManagerEvent> = _events.asSharedFlow()

    private val fetchMutex = Mutex()
    private var lastFetchAtMs: Long? = null
    private var consecutiveFailures = 0
    private var refreshJob: Job? = null
    private var clockJob: Job? = null
    private var endCheckJob: Job? = null

    private var channelsCache: LiveChannelsResponseDTO? = null
    private var channelsFetchedAtMs = 0L

    fun currentSlugOrNull(): String? = _state.value?.slug

    // region Screens

    /** Channel listing for Explore, cached [CHANNELS_CACHE_MS]. Null when off or failing (never cached). */
    suspend fun loadChannels(): LiveChannelsResponseDTO? {
        channelsCache?.let { if (deviceNowMs() - channelsFetchedAtMs < CHANNELS_CACHE_MS) return it }
        if (!isFeatureEnabled(false)) return null
        val response = request { service.getChannels() }
        val body = response?.body()
        if (response == null || !response.isSuccessful || body == null) {
            if (response != null && failureOf(response) == LiveFailure.DISABLED) isFeatureEnabled(true)
            return null
        }
        channelsCache = body
        channelsFetchedAtMs = deviceNowMs()
        return body
    }

    /** Last channel listing of this app session, whatever its age (to paint at once while [loadChannels] revalidates). */
    fun lastChannels(): LiveChannelsResponseDTO? = channelsCache

    /** `/now` for the broadcast screen without tuning in. */
    suspend fun peek(slug: String): LiveNowResult {
        if (!isFeatureEnabled(false)) return LiveNowResult.Failure(LiveFailure.DISABLED, LiveErrors.MESSAGE_DISABLED)
        val response = request { service.getNow(slug) }
        val body = response?.body()
        if (response == null || !response.isSuccessful || body == null) return failureResult(response, slug)
        clock.seedFromServerTime(body.serverTimeUtcMs)
        return LiveNowResult.Success(body)
    }

    /**
     * Tunes in to [slug]. Audio can start right away with the clock seeded from `/now`; the real
     * samples arrive in the background. Off air it returns the response without tuning in.
     */
    suspend fun start(slug: String): LiveNowResult {
        val result = peek(slug)
        if (result !is LiveNowResult.Success) return result
        val now = result.now
        val channel = now.channel
        if (now.block == null || channel == null) return result
        stopJobs()
        lastFetchAtMs = now.serverTimeUtcMs
        consecutiveFailures = 0
        _state.value = LiveSessionState(slug, channel, LiveTimeline.from(now), LiveListenStatus.LISTENING)
        log("tuned in to $slug, block ${now.block.blockId}")
        scope.launch { syncClock() }
        startClockRefresh()
        startRefreshLoop()
        return result
    }

    // endregion

    // region Player callbacks

    /** The player moved to another song: a chance to learn the next block (and DJ intros). */
    fun onSongChanged() {
        val slug = currentSlugOrNull() ?: return
        if (!LiveRefreshPolicy.shouldFetchOnSongChange(clock.now(), lastFetchAtMs)) return
        scope.launch { refresh(slug) }
    }

    /** The audio of [key] failed: skip it, keeping the schedule. */
    fun markFailed(key: String) {
        _state.update { it?.copy(timeline = it.timeline.markFailed(key)) }
        log("slot $key skipped after an audio error")
    }

    /** The player reached the end of the known schedule. */
    fun onScheduleEnded() {
        if (endCheckJob?.isActive == true) return
        val slug = currentSlugOrNull() ?: return
        endCheckJob = scope.launch { refreshOrEnd(slug) }
    }

    fun hold() {
        _state.update { if (it == null || it.status == LiveListenStatus.HELD) it else it.copy(status = LiveListenStatus.HELD) }
    }

    /** Listen again at the current point; asks `/now` first if the known schedule no longer covers it. */
    fun rejoin() {
        val current = _state.value ?: return
        if (current.status == LiveListenStatus.LISTENING) return
        scope.launch {
            if (current.timeline.at(clock.now()) == LiveCursor.Ended && !refreshOrEnd(current.slug)) return@launch
            _state.update { if (it?.slug == current.slug) it.copy(status = LiveListenStatus.LISTENING) else it }
        }
    }

    /** Leaves the broadcast; it goes on for everyone else. */
    fun leave(message: String? = null) {
        if (_state.value == null) return
        stopJobs()
        _state.value = null
        _events.tryEmit(LiveManagerEvent.Closed(message))
        log("left live${message?.let { ": $it" }.orEmpty()}")
    }

    /** One-shot message for the visible screen (e.g. from the playback service). */
    fun notify(text: String) {
        _events.tryEmit(LiveManagerEvent.Message(text))
    }

    // endregion

    // region Refresh

    private fun startRefreshLoop() {
        refreshJob = scope.launch {
            while (isActive) {
                val current = _state.value ?: break
                if (current.status == LiveListenStatus.HELD) {
                    _state.first { it == null || it.status == LiveListenStatus.LISTENING }
                    continue
                }
                val nowMs = clock.now()
                if (current.timeline.at(nowMs) == LiveCursor.Ended) {
                    if (!refreshOrEnd(current.slug)) break
                    if (consecutiveFailures > 0) delay(LiveRefreshPolicy.backoffMs(consecutiveFailures))
                    continue
                }
                val at = LiveRefreshPolicy.nextFetchAt(nowMs, current.timeline.endUtcMs(), lastFetchAtMs, consecutiveFailures)
                if (at > nowMs) {
                    delay(at - nowMs)
                    continue
                }
                refresh(current.slug)
            }
        }
    }

    /** Asks `/now` once; leaves with "La emisión ha terminado" if the schedule is still over. */
    private suspend fun refreshOrEnd(slug: String): Boolean {
        refresh(slug)
        val current = _state.value ?: return false
        if (current.timeline.at(clock.now()) != LiveCursor.Ended) return true
        if (consecutiveFailures > 0) {
            // Nothing known to play and no answer yet: retry a few times before giving up.
            if (consecutiveFailures < MAX_FAILURES_AT_END) return true
        }
        leave(LiveErrors.MESSAGE_ENDED)
        return false
    }

    private suspend fun refresh(slug: String) = fetchMutex.withLock {
        if (currentSlugOrNull() != slug) return@withLock
        lastFetchAtMs = clock.now()
        val response = request { service.getNow(slug) }
        val body = response?.body()
        if (response != null && response.isSuccessful && body != null) {
            consecutiveFailures = 0
            _state.update { state ->
                if (state?.slug == slug) state.copy(timeline = state.timeline.merge(body, clock.now())) else state
            }
            return@withLock
        }
        when (val failure = if (response == null) LiveFailure.TRANSIENT else failureOf(response)) {
            LiveFailure.TRANSIENT -> {
                consecutiveFailures++
                log("refresh failed ($consecutiveFailures): ${response?.code() ?: "network"}")
            }
            LiveFailure.DISABLED -> {
                isFeatureEnabled(true)
                leave(LiveErrors.message(failure))
            }
            LiveFailure.NOT_FOUND -> leave(LiveErrors.message(failure))
        }
    }

    // endregion

    // region Clock

    private suspend fun syncClock() {
        val samples = (1..CLOCK_SAMPLES).mapNotNull {
            runCatching {
                val sentAt = deviceNowMs()
                val serverTime = platform.getServerTime().serverTimeUtcMs
                ServerClock.Sample(sentAt, serverTime, deviceNowMs())
            }.getOrNull()?.takeIf { it.serverTimeMs > 0L }
        }
        if (clock.applySamples(samples)) log("clock offset ${clock.offsetMs} ms, rtt ${clock.roundTripMs} ms")
    }

    private fun startClockRefresh() {
        clockJob = scope.launch {
            while (isActive && _state.value != null) {
                delay(CLOCK_REFRESH_MS)
                if (_state.value != null) syncClock()
            }
        }
    }

    // endregion

    // region Helpers

    private fun stopJobs() {
        refreshJob?.cancel()
        clockJob?.cancel()
        endCheckJob?.cancel()
        refreshJob = null
        clockJob = null
        endCheckJob = null
    }

    private fun failureOf(response: Response<*>): LiveFailure =
        LiveErrors.classify(response.code(), ApiErrorParser.parse(response)?.code)

    private suspend fun failureResult(response: Response<*>?, slug: String): LiveNowResult.Failure {
        val failure = if (response == null) LiveFailure.TRANSIENT else failureOf(response)
        when (failure) {
            LiveFailure.DISABLED -> {
                isFeatureEnabled(true)
                leave(LiveErrors.MESSAGE_DISABLED)
            }
            LiveFailure.NOT_FOUND -> if (currentSlugOrNull() == slug) leave(LiveErrors.MESSAGE_NOT_FOUND)
            LiveFailure.TRANSIENT -> Unit
        }
        return LiveNowResult.Failure(failure, LiveErrors.message(failure) ?: LiveErrors.MESSAGE_RETRY)
    }

    /** Null on network failure. */
    private suspend fun <T> request(block: suspend () -> Response<T>): Response<T>? =
        runCatching { block() }
            .onFailure { log("request failed: ${it.javaClass.simpleName}") }
            .getOrNull()

    // endregion

    companion object {
        private const val TAG = "Live"
        const val CLOCK_SAMPLES = 5
        const val CLOCK_REFRESH_MS = 5 * 60 * 1000L
        const val CHANNELS_CACHE_MS = 15_000L
        const val MAX_FAILURES_AT_END = 3

        @Volatile
        private var instance: LiveManager? = null

        /** Current broadcast without needing a Context (null if the manager was never created). */
        fun activeSlugOrNull(): String? = instance?.currentSlugOrNull()

        fun get(context: Context): LiveManager =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        private fun create(context: Context): LiveManager = LiveManager(
            service = ApiClient.getLiveService(context),
            platform = ApiClient.getPlatformService(context),
            isFeatureEnabled = { force ->
                CapabilitiesManager.refresh(context, force)
                CapabilitiesManager.isLiveEnabled()
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            log = { Log.d(TAG, it) }
        )
    }
}
