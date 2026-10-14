package com.example.resonant.live

/**
 * When to ask `GET /now` again while listening. Lists are fixed, so asking is only needed to learn
 * the next block in time (and, from I10 on, the DJ intros); everything else keeps playing from the
 * known schedule. Pure: all instants are server milliseconds passed in.
 */
object LiveRefreshPolicy {
    /** Ask for the next block when this much known schedule is left. */
    const val LOOKAHEAD_MS = 5 * 60_000L

    /** While the next block does not exist yet. */
    const val WAIT_NEXT_BLOCK_MS = 30_000L

    /** At most one refresh per song change in this window (rapid transitions, failed slots). */
    const val MIN_SONG_CHANGE_INTERVAL_MS = 20_000L

    private val BACKOFF_MS = longArrayOf(5_000L, 15_000L, 30_000L, 60_000L)

    fun shouldFetchOnSongChange(nowMs: Long, lastFetchAtMs: Long?): Boolean =
        lastFetchAtMs == null || nowMs - lastFetchAtMs >= MIN_SONG_CHANGE_INTERVAL_MS

    fun backoffMs(consecutiveFailures: Int): Long =
        BACKOFF_MS[(consecutiveFailures - 1).coerceIn(0, BACKOFF_MS.lastIndex)]

    /**
     * Next instant to ask without a song change. Never earlier than [nowMs]'s "right away".
     * @param timelineEndMs end of the known schedule, null when nothing is known yet.
     */
    fun nextFetchAt(
        nowMs: Long,
        timelineEndMs: Long?,
        lastFetchAtMs: Long?,
        consecutiveFailures: Int
    ): Long {
        if (consecutiveFailures > 0 && lastFetchAtMs != null) {
            return maxOf(nowMs, lastFetchAtMs + backoffMs(consecutiveFailures))
        }
        if (timelineEndMs == null || lastFetchAtMs == null) return nowMs
        val lookaheadAt = timelineEndMs - LOOKAHEAD_MS
        if (nowMs < lookaheadAt && lastFetchAtMs < lookaheadAt) return lookaheadAt
        return maxOf(nowMs, lastFetchAtMs + WAIT_NEXT_BLOCK_MS)
    }
}
