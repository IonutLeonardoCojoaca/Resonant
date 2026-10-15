package com.example.resonant.streak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongOfDayRetryPolicyTest {

    @Test
    fun `server wait is used within bounds`() {
        assertEquals(15, SongOfDayRetryPolicy.delaySeconds(15))
        assertEquals(5, SongOfDayRetryPolicy.delaySeconds(2))
        assertEquals(60, SongOfDayRetryPolicy.delaySeconds(300))
    }

    @Test
    fun `missing or invalid wait defaults to 15 seconds`() {
        assertEquals(15, SongOfDayRetryPolicy.delaySeconds(null))
        assertEquals(15, SongOfDayRetryPolicy.delaySeconds(0))
        assertEquals(15, SongOfDayRetryPolicy.delaySeconds(-3))
    }

    @Test
    fun `allows exactly four retries`() {
        val allowed = (0 until 10).count { SongOfDayRetryPolicy.canRetry(it) }

        assertEquals(4, allowed)
        assertTrue(SongOfDayRetryPolicy.canRetry(3))
        assertFalse(SongOfDayRetryPolicy.canRetry(4))
    }

    @Test
    fun `a new opening starts the count again`() {
        assertFalse(SongOfDayRetryPolicy.canRetry(4))
        // Each opening of Explorar counts retries from zero.
        assertTrue(SongOfDayRetryPolicy.canRetry(0))
    }
}
