package com.example.resonant.unisound

/**
 * UI guard so taps do not burn the server limit (5 reactions per 10 s by default):
 * after [maxTaps] in [windowMs] the bar pauses for [cooldownMs]. The server stays the authority.
 */
class UnisoundReactionThrottle(
    private val maxTaps: Int = 5,
    private val windowMs: Long = 10_000L,
    private val cooldownMs: Long = 2_000L
) {
    private val taps = ArrayDeque<Long>()
    private var blockedUntilMs = 0L

    fun tryTap(nowMs: Long): Boolean {
        if (nowMs < blockedUntilMs) return false
        while (taps.isNotEmpty() && nowMs - taps.first() >= windowMs) taps.removeFirst()
        if (taps.size >= maxTaps) {
            blockedUntilMs = nowMs + cooldownMs
            return false
        }
        taps.addLast(nowMs)
        return true
    }

    fun isBlocked(nowMs: Long): Boolean = nowMs < blockedUntilMs
}
