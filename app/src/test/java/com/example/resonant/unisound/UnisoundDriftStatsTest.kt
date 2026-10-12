package com.example.resonant.unisound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnisoundDriftStatsTest {
    @Test
    fun `reports once per window with median max and seeks`() {
        val stats = UnisoundDriftStats(windowMs = 1_000L)

        assertNull(stats.record(40L, seeked = false, nowMs = 0L))
        assertNull(stats.record(120L, seeked = true, nowMs = 500L))
        val report = stats.record(60L, seeked = false, nowMs = 1_000L)

        assertEquals(UnisoundDriftReport(p50Ms = 60L, maxMs = 120L, seeks = 1, samples = 3), report)
        assertNull(stats.record(10L, seeked = false, nowMs = 1_100L))
    }
}
