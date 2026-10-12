package com.example.resonant.unisound

import kotlin.math.abs

sealed class UnisoundDriftAction {
    object None : UnisoundDriftAction()

    /** Jump to [positionMs] and play at normal speed. */
    data class Seek(val positionMs: Long) : UnisoundDriftAction()

    /** Change playback speed (pitch is preserved by Media3). 1.0 ends a correction. */
    data class SetSpeed(val speed: Float) : UnisoundDriftAction()
}

/**
 * Decides how this device catches up with the room position. Thresholds are constants on
 * purpose: they are tuned with real devices, they are not part of the contract.
 *
 * Not thread-safe: the player calls it from the main thread only.
 */
class UnisoundDriftPolicy {
    private var correctingSinceMs: Long? = null
    private var currentSpeed = NORMAL_SPEED
    private var lastSeekAtMs: Long? = null
    private var wasBuffering = false

    /**
     * @param expectedMs room position now, from the server clock.
     * @param actualMs player position now.
     * @param sameSong whether the player is on the room's current song.
     */
    fun decide(
        expectedMs: Long,
        actualMs: Long,
        sameSong: Boolean,
        buffering: Boolean,
        nowMs: Long
    ): UnisoundDriftAction {
        if (buffering) {
            wasBuffering = true
            return UnisoundDriftAction.None
        }
        if (!sameSong || wasBuffering) {
            wasBuffering = false
            return seek(expectedMs, nowMs)
        }
        val sinceSeek = lastSeekAtMs?.let { nowMs - it }
        if (sinceSeek != null && sinceSeek < SETTLE_AFTER_SEEK_MS) return UnisoundDriftAction.None

        val drift = actualMs - expectedMs
        val absDrift = abs(drift)
        if (absDrift > SEEK_THRESHOLD_MS) return seek(expectedMs, nowMs)

        val since = correctingSinceMs
        if (since != null) {
            if (absDrift < TARGET_MS || nowMs - since >= MAX_CORRECTION_MS) return stopCorrection()
            return setSpeed(speedFor(drift))
        }
        if (absDrift >= TOLERANCE_MS) {
            correctingSinceMs = nowMs
            return setSpeed(speedFor(drift))
        }
        return UnisoundDriftAction.None
    }

    /** Forget any correction in progress (room paused, local hold, room left). */
    fun reset() {
        correctingSinceMs = null
        currentSpeed = NORMAL_SPEED
        lastSeekAtMs = null
        wasBuffering = false
    }

    private fun seek(positionMs: Long, nowMs: Long): UnisoundDriftAction {
        correctingSinceMs = null
        currentSpeed = NORMAL_SPEED
        lastSeekAtMs = nowMs
        return UnisoundDriftAction.Seek(positionMs)
    }

    private fun stopCorrection(): UnisoundDriftAction {
        correctingSinceMs = null
        return setSpeed(NORMAL_SPEED)
    }

    private fun setSpeed(speed: Float): UnisoundDriftAction {
        if (speed == currentSpeed) return UnisoundDriftAction.None
        currentSpeed = speed
        return UnisoundDriftAction.SetSpeed(speed)
    }

    /** Behind (negative drift) speeds up, ahead slows down, aiming to close it in ~10 s. */
    private fun speedFor(driftMs: Long): Float {
        val speed = NORMAL_SPEED - driftMs / CATCH_UP_WINDOW_MS
        return speed.coerceIn(MIN_SPEED, MAX_SPEED)
    }

    companion object {
        const val TOLERANCE_MS = 150L
        const val SEEK_THRESHOLD_MS = 800L
        const val TARGET_MS = 50L
        const val MAX_CORRECTION_MS = 8_000L
        const val SETTLE_AFTER_SEEK_MS = 1_500L
        const val NORMAL_SPEED = 1.0f
        const val MIN_SPEED = 0.97f
        const val MAX_SPEED = 1.03f
        private const val CATCH_UP_WINDOW_MS = 10_000f
    }
}
