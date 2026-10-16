package com.example.resonant.recap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecapStoryStateTest {

    @Test
    fun `tap right and left navigate`() {
        val state = RecapStoryState(9)
        state.next(); state.next() // tarjeta 3 de 9
        state.next()
        assertEquals(3, state.index)
        state.previous(); state.previous()
        assertEquals(1, state.index)
    }

    @Test
    fun `previous on the first card restarts it`() {
        val state = RecapStoryState(3)
        state.updateProgress(0.5f)
        state.previous()
        assertEquals(0, state.index)
        assertEquals(0f, state.progress)
    }

    @Test
    fun `timer advances but the last card does not`() {
        val state = RecapStoryState(2)
        state.onTimerFinished()
        assertEquals(1, state.index)
        assertEquals(0f, state.progress)
        state.onTimerFinished()
        assertEquals(1, state.index)
        assertEquals(1f, state.progress)
        assertFalse(state.shouldRun)
        assertFalse(state.next())
    }

    @Test
    fun `pause and resume keep progress`() {
        val state = RecapStoryState(3)
        state.updateProgress(0.4f)
        state.pause()
        assertFalse(state.shouldRun)
        state.resume()
        assertTrue(state.shouldRun)
        assertEquals(0.4f, state.progress)
        assertEquals(3_600L, state.remainingMs)
    }
}
