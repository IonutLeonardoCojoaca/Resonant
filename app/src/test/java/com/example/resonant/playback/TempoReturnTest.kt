package com.example.resonant.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TempoReturnTest {

    // ── Curva ───────────────────────────────────────────────────────────────

    @Test
    fun `starts at the mix rate and ends at one`() {
        assertEquals(1.085, TempoReturn.speedAt(0, 1.085), 0.0)
        assertEquals(1.0, TempoReturn.speedAt(TempoReturn.TEMPO_RETURN_MS, 1.085), 0.0)
        assertEquals(1.0425, TempoReturn.speedAt(TempoReturn.TEMPO_RETURN_MS / 2, 1.085), 1e-9)
    }

    @Test
    fun `after the duration it stays at one`() {
        assertEquals(1.0, TempoReturn.speedAt(TempoReturn.TEMPO_RETURN_MS + 1, 1.085), 0.0)
        assertEquals(1.0, TempoReturn.speedAt(60_000, 0.9), 0.0)
        assertEquals(1.0, TempoReturn.speedAt(10, 1.085, durationMs = 0), 0.0)
    }

    @Test
    fun `curve is monotonic in both directions`() {
        for (from in listOf(1.085, 0.92)) {
            var previous = TempoReturn.speedAt(0, from)
            for (t in 50L..TempoReturn.TEMPO_RETURN_MS step 50L) {
                val speed = TempoReturn.speedAt(t, from)
                if (from > 1.0) assertTrue("t=$t", speed <= previous) else assertTrue("t=$t", speed >= previous)
                previous = speed
            }
            assertEquals(1.0, previous, 0.0)
        }
    }

    @Test
    fun `curve starts and ends smoothly`() {
        // smoothstep: los primeros y últimos 50 ms cambian mucho menos que los del centro.
        val first = 1.085 - TempoReturn.speedAt(50, 1.085)
        val middle = TempoReturn.speedAt(2_975, 1.085) - TempoReturn.speedAt(3_025, 1.085)
        assertTrue(first < middle / 10)
    }

    // ── Driver ──────────────────────────────────────────────────────────────

    private class FakeScheduler : TempoReturn.Scheduler {
        val tasks = mutableListOf<Runnable>()
        override fun postDelayed(task: Runnable, delayMs: Long) {
            assertEquals(TempoReturn.TICK_MS, delayMs)
            tasks += task
        }
        override fun remove(task: Runnable) { tasks.removeAll { it === task } }

        fun runPending() {
            val pending = tasks.toList()
            tasks.clear()
            pending.forEach { it.run() }
        }
    }

    private class FakePlayer : TempoReturn.Target {
        val speeds = mutableListOf<Float>()
        override fun setSpeed(speed: Float) { speeds += speed }
        val last: Float get() = speeds.last()
    }

    private var now = 1_000L
    private val scheduler = FakeScheduler()
    private val driver = TempoReturn.Driver(scheduler, clock = { now })

    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(TempoReturn.TICK_MS, left)
            now += step
            left -= step
            scheduler.runPending()
        }
    }

    @Test
    fun `ramp goes from the rate to one and stops ticking`() {
        val player = FakePlayer()
        driver.start(player, 1.085, "gasolina")

        assertEquals(1.085f, player.speeds.first(), 1e-6f)
        assertTrue(driver.isActive)
        assertEquals("gasolina", driver.songId)
        advance(TempoReturn.TEMPO_RETURN_MS / 2)
        assertEquals(1.0425f, player.last, 1e-3f)
        advance(TempoReturn.TEMPO_RETURN_MS)

        assertEquals(1f, player.last, 0f)
        assertFalse(driver.isActive)
        assertNull(driver.songId)
        assertTrue(scheduler.tasks.isEmpty())
    }

    @Test
    fun `every cancellation leaves speed at one and stops the tick`() {
        for (reason in listOf("seek", "siguiente", "anterior", "pausa", "cambio de canción", "transición", "Unisound", "Live")) {
            val player = FakePlayer()
            driver.start(player, 1.085, "gasolina")
            advance(1_000)
            assertTrue(player.last > 1.0f)

            driver.cancel(reason)

            assertEquals(reason, 1f, player.last, 0f)
            assertFalse(reason, driver.isActive)
            assertTrue(reason, scheduler.tasks.isEmpty())
            val calls = player.speeds.size
            advance(1_000)
            assertEquals(reason, calls, player.speeds.size)
        }
    }

    @Test
    fun `cancel without ramp does nothing`() {
        driver.cancel("pausa")
        assertFalse(driver.isActive)
        assertTrue(scheduler.tasks.isEmpty())
    }

    @Test
    fun `a new ramp resets the previous player to one`() {
        val first = FakePlayer()
        val second = FakePlayer()
        driver.start(first, 1.085, "a")
        advance(500)
        driver.start(second, 0.92, "b")

        assertEquals(1f, first.last, 0f)
        assertEquals(0.92f, second.speeds.first(), 1e-6f)
        assertEquals("b", driver.songId)
        assertEquals(1, scheduler.tasks.size)
    }

    @Test
    fun `rate of one sets one without ramp`() {
        val player = FakePlayer()
        driver.start(player, 1.0, "x")
        assertEquals(listOf(1f), player.speeds)
        assertFalse(driver.isActive)
        assertTrue(scheduler.tasks.isEmpty())
    }
}
