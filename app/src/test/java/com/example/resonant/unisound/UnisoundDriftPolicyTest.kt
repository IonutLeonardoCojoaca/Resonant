package com.example.resonant.unisound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnisoundDriftPolicyTest {
    private val policy = UnisoundDriftPolicy()

    private fun decide(expected: Long, actual: Long, now: Long, sameSong: Boolean = true, buffering: Boolean = false) =
        policy.decide(expected, actual, sameSong, buffering, now)

    @Test
    fun `small drift does nothing`() {
        assertEquals(UnisoundDriftAction.None, decide(10_000L, 10_100L, now = 0L))
    }

    @Test
    fun `behind speeds up within the limit`() {
        val action = decide(10_000L, 9_600L, now = 0L) as UnisoundDriftAction.SetSpeed

        assertEquals(UnisoundDriftPolicy.MAX_SPEED, action.speed)
    }

    @Test
    fun `ahead slows down within the limit`() {
        val action = decide(10_000L, 10_400L, now = 0L) as UnisoundDriftAction.SetSpeed

        assertEquals(UnisoundDriftPolicy.MIN_SPEED, action.speed)
    }

    @Test
    fun `moderate drift gets a proportional speed`() {
        val action = decide(10_000L, 9_800L, now = 0L) as UnisoundDriftAction.SetSpeed

        assertEquals(1.02f, action.speed, 0.0001f)
    }

    @Test
    fun `large drift seeks`() {
        assertEquals(UnisoundDriftAction.Seek(10_000L), decide(10_000L, 9_100L, now = 0L))
    }

    @Test
    fun `different song seeks`() {
        assertEquals(UnisoundDriftAction.Seek(5_000L), decide(5_000L, 5_000L, now = 0L, sameSong = false))
    }

    @Test
    fun `buffering waits and seeks once ready`() {
        assertEquals(UnisoundDriftAction.None, decide(10_000L, 7_000L, now = 0L, buffering = true))

        assertEquals(UnisoundDriftAction.Seek(13_000L), decide(13_000L, 7_000L, now = 3_000L))
    }

    @Test
    fun `measurements right after a seek are ignored`() {
        decide(10_000L, 8_000L, now = 0L)

        assertEquals(UnisoundDriftAction.None, decide(11_000L, 9_000L, now = 1_000L))
        assertTrue(decide(11_600L, 9_600L, now = 1_600L) is UnisoundDriftAction.Seek)
    }

    @Test
    fun `correction ends once on target`() {
        decide(10_000L, 9_600L, now = 0L)

        assertEquals(UnisoundDriftAction.SetSpeed(1.0f), decide(20_000L, 19_970L, now = 4_000L))
    }

    @Test
    fun `correction stops after eight seconds`() {
        decide(10_000L, 9_600L, now = 0L)

        assertEquals(UnisoundDriftAction.SetSpeed(1.0f), decide(18_000L, 17_800L, now = 8_000L))
    }

    @Test
    fun `reset forgets a correction in progress`() {
        decide(10_000L, 9_600L, now = 0L)
        policy.reset()

        assertTrue(decide(10_000L, 9_600L, now = 100L) is UnisoundDriftAction.SetSpeed)
    }
}
