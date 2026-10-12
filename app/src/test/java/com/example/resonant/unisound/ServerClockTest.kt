package com.example.resonant.unisound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ServerClockTest {
    private var deviceNow = 1_000_000L
    private val clock = ServerClock { deviceNow }

    @Test
    fun `device clock ahead is corrected within 50 ms`() {
        // Device is 2000 ms ahead of the server, round trip 80 ms, symmetric.
        val sent = deviceNow
        val server = sent - 2_000L + 40L
        clock.applySamples(listOf(ServerClock.Sample(sent, server, sent + 80L)))

        deviceNow = sent + 5_000L
        val trueServerNow = deviceNow - 2_000L
        assertTrue(abs(clock.now() - trueServerNow) < 50L)
    }

    @Test
    fun `sample with the shortest round trip wins`() {
        val samples = listOf(60L, 70L, 900L, 65L, 1_200L).mapIndexed { i, rtt ->
            val sent = 10_000L * i
            ServerClock.Sample(sent, sent + 500L + rtt / 2, sent + rtt)
        }
        clock.applySamples(samples)

        assertEquals(60L, clock.roundTripMs)
        assertEquals(500L, clock.offsetMs)
    }

    @Test
    fun `negative round trips are ignored`() {
        assertFalse(clock.applySamples(listOf(ServerClock.Sample(100L, 50L, 90L))))
        assertFalse(clock.hasEstimate)
    }

    @Test
    fun `snapshot time seeds until real samples exist`() {
        clock.seedFromServerTime(serverTimeMs = deviceNow + 300L)
        assertEquals(300L, clock.offsetMs)

        clock.applySamples(listOf(ServerClock.Sample(0L, 1_000L, 100L)))
        clock.seedFromServerTime(serverTimeMs = deviceNow + 9_999L)
        assertEquals(950L, clock.offsetMs)
    }

    @Test
    fun `server instants convert to device time`() {
        clock.applySamples(listOf(ServerClock.Sample(0L, 1_000L, 0L)))

        assertEquals(4_000L, clock.toDeviceTime(5_000L))
    }
}
