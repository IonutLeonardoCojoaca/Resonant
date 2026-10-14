package com.example.resonant.live

import com.example.resonant.live.LiveRefreshPolicy.LOOKAHEAD_MS
import com.example.resonant.live.LiveRefreshPolicy.WAIT_NEXT_BLOCK_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRefreshPolicyTest {
    private val end = 10_000_000L

    @Test
    fun `song change refreshes at most once every 20 s`() {
        assertTrue(LiveRefreshPolicy.shouldFetchOnSongChange(nowMs = 1_000L, lastFetchAtMs = null))
        assertFalse(LiveRefreshPolicy.shouldFetchOnSongChange(nowMs = 25_000L, lastFetchAtMs = 10_000L))
        assertTrue(LiveRefreshPolicy.shouldFetchOnSongChange(nowMs = 30_000L, lastFetchAtMs = 10_000L))
    }

    @Test
    fun `nothing known asks right away`() {
        assertEquals(500L, LiveRefreshPolicy.nextFetchAt(500L, timelineEndMs = null, lastFetchAtMs = null, consecutiveFailures = 0))
    }

    @Test
    fun `plenty of schedule waits until five minutes before the end`() {
        val now = end - 30 * 60_000L

        assertEquals(end - LOOKAHEAD_MS, LiveRefreshPolicy.nextFetchAt(now, end, lastFetchAtMs = now, consecutiveFailures = 0))
    }

    @Test
    fun `inside the lookahead without next block retries every 30 s`() {
        val lastFetch = end - LOOKAHEAD_MS + 1_000L

        assertEquals(
            lastFetch + WAIT_NEXT_BLOCK_MS,
            LiveRefreshPolicy.nextFetchAt(lastFetch + 5_000L, end, lastFetch, consecutiveFailures = 0)
        )
    }

    @Test
    fun `entering the lookahead after an old fetch asks right away`() {
        val now = end - LOOKAHEAD_MS + 10L

        assertEquals(now, LiveRefreshPolicy.nextFetchAt(now, end, lastFetchAtMs = end - 20 * 60_000L, consecutiveFailures = 0))
    }

    @Test
    fun `failures back off 5, 15, 30 and then 60 s`() {
        val last = 1_000_000L
        assertEquals(last + 5_000L, LiveRefreshPolicy.nextFetchAt(last, end, last, consecutiveFailures = 1))
        assertEquals(last + 15_000L, LiveRefreshPolicy.nextFetchAt(last, end, last, consecutiveFailures = 2))
        assertEquals(last + 30_000L, LiveRefreshPolicy.nextFetchAt(last, end, last, consecutiveFailures = 3))
        assertEquals(last + 60_000L, LiveRefreshPolicy.nextFetchAt(last, end, last, consecutiveFailures = 4))
        assertEquals(last + 60_000L, LiveRefreshPolicy.nextFetchAt(last, end, last, consecutiveFailures = 9))
    }
}
