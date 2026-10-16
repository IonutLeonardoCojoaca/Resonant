package com.example.resonant.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDjDuckRampTest {
    @Test
    fun `ramp down is monotonic and ends at the ducked volume`() {
        val steps = LiveDjDuckRamp.steps(LiveDjDuckRamp.FULL, LiveDjDuckRamp.DUCKED)

        assertEquals(10, steps.size)
        assertTrue(steps.zipWithNext().all { (a, b) -> b < a })
        assertTrue(steps.first() < 1.0f)
        assertEquals(0.3f, steps.last())
    }

    @Test
    fun `ramp up is monotonic and ends at full volume`() {
        val steps = LiveDjDuckRamp.steps(LiveDjDuckRamp.DUCKED, LiveDjDuckRamp.FULL)

        assertTrue(steps.zipWithNext().all { (a, b) -> b > a })
        assertEquals(1.0f, steps.last())
    }

    @Test
    fun `a ramp cut halfway goes back to full volume`() {
        val down = LiveDjDuckRamp.steps(LiveDjDuckRamp.FULL, LiveDjDuckRamp.DUCKED)
        val halfway = down[4]

        val back = LiveDjDuckRamp.steps(halfway, LiveDjDuckRamp.FULL)

        assertTrue(back.zipWithNext().all { (a, b) -> b > a })
        assertEquals(1.0f, back.last())
    }

    @Test
    fun `same value is a single step`() {
        assertEquals(listOf(1.0f), LiveDjDuckRamp.steps(1.0f, 1.0f))
    }
}
