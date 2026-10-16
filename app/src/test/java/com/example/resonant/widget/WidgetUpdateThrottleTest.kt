package com.example.resonant.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetUpdateThrottleTest {

    private var now = 0L
    private val throttle = WidgetUpdateThrottle(clock = { now })

    /** Runs the timer as the Handler would: fires when due, returns the repaints done. */
    private fun advanceTo(target: Long, signalsAt: List<Long> = emptyList()): Int {
        var repaints = 0
        val events = (signalsAt.toSet() + (0..target step 10).toSet()).sorted()
        events.forEach { t ->
            now = t
            if (t in signalsAt) throttle.signal()
            if (throttle.fire() > 0) repaints++
        }
        return repaints
    }

    private fun observing() {
        assertEquals(WidgetObservationChange.START, throttle.update(attached = true, widgetCount = 1))
        assertEquals(1, throttle.fire())
    }

    @Test
    fun `five signals in under a second give one repaint`() {
        observing()
        val repaints = advanceTo(2_000L, signalsAt = listOf(100L, 300L, 500L, 700L, 900L))
        assertEquals(1, repaints)
    }

    @Test
    fun `a single signal repaints after 300 ms`() {
        observing()
        now = 1_000L
        assertEquals(300L, throttle.signal())
        now = 1_299L
        assertEquals(0, throttle.fire())
        now = 1_300L
        assertEquals(1, throttle.fire())
    }

    @Test
    fun `a burst never waits more than the max delay`() {
        observing()
        now = 0L
        throttle.signal()
        now = 800L
        assertEquals(100L, throttle.signal())
    }

    @Test
    fun `without widgets nothing is repainted`() {
        assertEquals(WidgetObservationChange.NONE, throttle.update(attached = true, widgetCount = 0))
        assertFalse(throttle.isObserving)
        assertNull(throttle.signal())
        assertEquals(0, throttle.fire())
    }

    @Test
    fun `not attached nothing is repainted`() {
        assertEquals(WidgetObservationChange.NONE, throttle.update(attached = false, widgetCount = 2))
        assertNull(throttle.signal())
    }

    @Test
    fun `going from one widget to none stops and drops the pending repaint`() {
        observing()
        throttle.signal()
        assertEquals(WidgetObservationChange.STOP, throttle.update(attached = true, widgetCount = 0))
        now = 5_000L
        assertEquals(0, throttle.fire())
        assertNull(throttle.signal())
    }

    @Test
    fun `going from none to one widget repaints right away`() {
        throttle.update(attached = true, widgetCount = 0)
        now = 42L
        assertEquals(WidgetObservationChange.START, throttle.update(attached = true, widgetCount = 1))
        assertEquals(0L, throttle.delayUntilDue())
        assertEquals(1, throttle.fire())
    }

    @Test
    fun `detach stops observing`() {
        observing()
        assertEquals(WidgetObservationChange.STOP, throttle.update(attached = false, widgetCount = 1))
        assertFalse(throttle.isObserving)
    }

    @Test
    fun `more widgets while observing change nothing`() {
        observing()
        assertEquals(WidgetObservationChange.NONE, throttle.update(attached = true, widgetCount = 3))
        assertTrue(throttle.isObserving)
    }
}
