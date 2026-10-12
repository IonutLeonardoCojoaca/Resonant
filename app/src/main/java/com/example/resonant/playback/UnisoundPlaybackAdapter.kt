package com.example.resonant.playback

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.resonant.unisound.UnisoundDriftAction
import com.example.resonant.unisound.UnisoundDriftPolicy
import com.example.resonant.unisound.UnisoundDriftReport
import com.example.resonant.unisound.UnisoundDriftStats
import com.example.resonant.unisound.UnisoundManager
import com.example.resonant.unisound.UnisoundMirror
import com.example.resonant.unisound.UnisoundMirrorChange
import com.example.resonant.unisound.UnisoundMirrorPlan
import com.example.resonant.unisound.UnisoundRoomState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Makes MusicPlaybackService follow the Unisound room. It decides *what* changed and when to
 * correct drift; the service ([Host]) owns ExoPlayer and applies it. Everything runs on Main.
 */
class UnisoundPlaybackAdapter(
    private val manager: UnisoundManager,
    private val host: Host,
    private val reportDrift: (UnisoundDriftReport) -> Unit
) {
    interface Host {
        fun applyUnisoundState(state: UnisoundRoomState?, change: UnisoundMirrorChange)
        fun unisoundPlayerSample(): PlayerSample?
        fun applyUnisoundDrift(action: UnisoundDriftAction, state: UnisoundRoomState)
    }

    data class PlayerSample(
        val positionMs: Long,
        val mediaId: String?,
        val nextMediaId: String?,
        val remainingMs: Long,
        val buffering: Boolean,
        val playing: Boolean
    )

    private val handler = Handler(Looper.getMainLooper())
    private val driftPolicy = UnisoundDriftPolicy()
    private val driftStats = UnisoundDriftStats()
    private var mirror: UnisoundMirror? = null
    private var collectJob: Job? = null
    private var driftRunning = false

    private val driftTick = object : Runnable {
        override fun run() {
            val state = manager.state.value
            if (state == null || !state.isPlaying || state.localHold) {
                stopDriftLoop()
                return
            }
            correctDrift(state)
            handler.postDelayed(this, DRIFT_INTERVAL_MS)
        }
    }

    val clock get() = manager.clock

    fun start(scope: CoroutineScope) {
        if (collectJob?.isActive == true) return
        collectJob = scope.launch(Dispatchers.Main) {
            manager.state.collect(::onState)
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        stopDriftLoop()
    }

    /** Room position right now, or null outside a room. */
    fun expectedPositionMs(): Long? = manager.state.value?.expectedPositionMs(manager.clock.now())

    /** The player lost track of the room (unknown item): load the current state again. */
    fun forceRebuild() {
        mirror = null
        onState(manager.state.value)
    }

    private fun onState(state: UnisoundRoomState?) {
        val next = UnisoundMirrorPlan.mirrorOf(state)
        val change = UnisoundMirrorPlan.diff(mirror, next)
        mirror = next
        if (change == UnisoundMirrorChange.NONE) return
        if (state == null || !state.isPlaying || state.localHold || change == UnisoundMirrorChange.REBUILD) {
            driftPolicy.reset()
        }
        host.applyUnisoundState(state, change)
        if (state != null && state.isPlaying && !state.localHold) startDriftLoop() else stopDriftLoop()
    }

    private fun correctDrift(state: UnisoundRoomState) {
        val serverNow = manager.clock.now()
        // Orders are anchored slightly in the future; the service applies them on time.
        if (serverNow < state.playback.anchorServerTimeUtcMs) return
        val sample = host.unisoundPlayerSample() ?: return
        if (!sample.playing && !sample.buffering) return
        val now = System.currentTimeMillis()
        val expected = state.expectedPositionMs(serverNow)
        if (UnisoundMirrorPlan.isCrossingSongBoundary(
                state, sample.mediaId, sample.nextMediaId, sample.remainingMs, expected
            )
        ) return
        val sameSong = sample.mediaId != null && sample.mediaId == state.current?.songId
        val action = driftPolicy.decide(expected, sample.positionMs, sameSong, sample.buffering, now)
        if (sameSong && !sample.buffering) {
            driftStats.record(abs(sample.positionMs - expected), action is UnisoundDriftAction.Seek, now)
                ?.let(reportDrift)
        }
        if (action != UnisoundDriftAction.None) {
            Log.d(TAG, "drift ${sample.positionMs - expected} ms -> $action")
            host.applyUnisoundDrift(action, state)
        }
    }

    private fun startDriftLoop() {
        if (driftRunning) return
        driftRunning = true
        handler.postDelayed(driftTick, DRIFT_INTERVAL_MS)
    }

    private fun stopDriftLoop() {
        driftRunning = false
        handler.removeCallbacks(driftTick)
        driftStats.reset()
    }

    companion object {
        private const val TAG = "Unisound"
        const val DRIFT_INTERVAL_MS = 500L
    }
}
