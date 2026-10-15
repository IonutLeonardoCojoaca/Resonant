package com.example.resonant.streak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreakEventBusTest {
    private val bus = StreakEventState()

    @Test
    fun `published event stays pending until consumed`() {
        bus.publish(StreakEvent.Milestone(7))
        assertEquals(StreakEvent.Milestone(7), bus.pending.value)

        bus.consume(StreakEvent.Milestone(7))
        assertNull(bus.pending.value)
    }

    @Test
    fun `qualified today does not hide a pending milestone`() {
        bus.publish(StreakEvent.Milestone(7))
        bus.publish(StreakEvent.QualifiedToday(8))

        assertEquals(StreakEvent.Milestone(7), bus.pending.value)
    }

    @Test
    fun `a newer milestone replaces the pending event`() {
        bus.publish(StreakEvent.QualifiedToday(6))
        bus.publish(StreakEvent.Milestone(7))

        assertEquals(StreakEvent.Milestone(7), bus.pending.value)
    }

    @Test
    fun `consuming a replaced event keeps the new one`() {
        bus.publish(StreakEvent.QualifiedToday(4))
        bus.publish(StreakEvent.Milestone(7))

        bus.consume(StreakEvent.QualifiedToday(4))

        assertEquals(StreakEvent.Milestone(7), bus.pending.value)
    }
}
