package com.example.resonant.playback

import com.example.resonant.live.LiveCursor
import com.example.resonant.live.LiveListenStatus
import com.example.resonant.live.LiveMirror
import com.example.resonant.live.LiveMirrorChange
import com.example.resonant.live.LiveMirrorPlan
import com.example.resonant.live.LivePlayerSnapshot
import com.example.resonant.live.LiveSessionState
import com.example.resonant.live.LiveSlot
import com.example.resonant.live.LiveTickAction
import com.example.resonant.unisound.ServerClock
import com.example.resonant.unisound.UnisoundDriftAction
import com.example.resonant.unisound.UnisoundDriftPolicy
import com.example.resonant.unisound.UnisoundDriftReport
import com.example.resonant.unisound.UnisoundDriftStats
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Makes MusicPlaybackService follow a Live broadcast. It decides *what* changed and what the
 * schedule asks for every [TICK_INTERVAL_MS]; the service ([Host]) owns ExoPlayer and applies it.
 * Everything runs on [dispatcher] (Main in the app).
 */
class LivePlaybackAdapter(
    private val state: StateFlow<LiveSessionState?>,
    val clock: ServerClock,
    private val host: Host,
    private val onScheduleEnded: () -> Unit,
    private val reportDrift: (UnisoundDriftReport) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val deviceNowMs: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {}
) {
    interface Host {
        fun applyLiveState(state: LiveSessionState?, change: LiveMirrorChange)

        /** Null when the player is not on the Live queue. */
        fun livePlayerSnapshot(): LivePlayerSnapshot?

        /** Slot keys of the player queue, in order. */
        fun liveQueuedKeys(): List<String>

        fun applyLiveTick(action: LiveTickAction)
    }

    private val driftPolicy = UnisoundDriftPolicy()
    private val driftStats = UnisoundDriftStats()
    private var mirror: LiveMirror? = null
    private var scope: CoroutineScope? = null
    private var collectJob: Job? = null
    private var tickJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (collectJob?.isActive == true) return
        this.scope = scope
        collectJob = scope.launch(dispatcher) { state.collect(::onState) }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        stopTicks()
    }

    /**
     * Where to load the player from: the slot on air, or the next playable one during a gap.
     * Null outside Live or when nothing is left to play.
     */
    fun slotToLoad(): LiveSlot? {
        val timeline = state.value?.timeline ?: return null
        return when (val cursor = timeline.at(clock.now())) {
            is LiveCursor.Playing -> cursor.slot
            is LiveCursor.Gap -> cursor.resumesAt
            is LiveCursor.NotStarted -> timeline.slots.firstOrNull(timeline::isPlayable)
            LiveCursor.Ended -> null
        }
    }

    /** Position the schedule asks for in [key] right now (0 if that slot is not on air). */
    fun startPositionMs(key: String?): Long {
        val cursor = state.value?.timeline?.at(clock.now()) as? LiveCursor.Playing ?: return 0L
        return if (cursor.slot.key == key) cursor.positionMs else 0L
    }

    /** The player lost track of the schedule: load it again from the current point. */
    fun forceRebuild() {
        mirror = null
        onState(state.value)
    }

    private fun onState(next: LiveSessionState?) {
        val nextMirror = LiveMirrorPlan.mirrorOf(next?.slug, next?.status, next?.timeline)
        val change = LiveMirrorPlan.diff(mirror, nextMirror)
        mirror = nextMirror
        if (change == LiveMirrorChange.NONE) return
        if (change != LiveMirrorChange.APPEND) driftPolicy.reset()
        host.applyLiveState(next, change)
        if (next?.status == LiveListenStatus.LISTENING) startTicks() else stopTicks()
    }

    private fun tick() {
        val current = state.value ?: return
        if (current.status != LiveListenStatus.LISTENING) return
        val snapshot = host.livePlayerSnapshot() ?: return
        val cursor = current.timeline.at(clock.now())
        val queued = host.liveQueuedKeys()
        val now = deviceNowMs()
        val action = LiveMirrorPlan.tick(cursor, queued, snapshot, driftPolicy, now)
        recordDrift(cursor, queued, snapshot, action, now)
        when (action) {
            LiveTickAction.None -> Unit
            LiveTickAction.Rebuild -> {
                log("current slot not queued: rebuilding")
                forceRebuild()
            }
            LiveTickAction.Ended -> {
                host.applyLiveTick(action)
                onScheduleEnded()
            }
            else -> {
                log("tick -> $action")
                host.applyLiveTick(action)
            }
        }
    }

    private fun recordDrift(
        cursor: LiveCursor,
        queued: List<String>,
        snapshot: LivePlayerSnapshot,
        action: LiveTickAction,
        now: Long
    ) {
        if (cursor !is LiveCursor.Playing || snapshot.isBuffering || !snapshot.isPlaying) return
        if (snapshot.index != queued.indexOf(cursor.slot.key)) return
        val seeked = action is LiveTickAction.Drift && action.action is UnisoundDriftAction.Seek
        driftStats.record(abs(snapshot.positionMs - cursor.positionMs), seeked, now)?.let(reportDrift)
    }

    private fun startTicks() {
        if (tickJob?.isActive == true) return
        val scope = scope ?: return
        tickJob = scope.launch(dispatcher) {
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                tick()
            }
        }
    }

    private fun stopTicks() {
        tickJob?.cancel()
        tickJob = null
        driftStats.reset()
    }

    companion object {
        const val TICK_INTERVAL_MS = 500L
    }
}
