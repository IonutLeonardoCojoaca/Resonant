package com.example.resonant.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhaseAlignmentTest {

    private val grid120 = PhaseAlignment.Grid(bpm = 120.0, firstDownbeatMs = 500, beatsPerBar = 4) // compás 2 000 ms

    @Test
    fun `missing data gives no grid`() {
        assertNull(PhaseAlignment.gridOf(null, 500, 4))
        assertNull(PhaseAlignment.gridOf(120.0, null, 4))
        assertEquals(4, PhaseAlignment.gridOf(120.0, 0, null)!!.beatsPerBar)
    }

    @Test
    fun `bar phase is measured from the first downbeat`() {
        assertEquals(0.0, PhaseAlignment.barPhase(500, grid120), 1e-9)
        assertEquals(0.25, PhaseAlignment.barPhase(1_000, grid120), 1e-9)
        assertEquals(0.75, PhaseAlignment.barPhase(0, grid120), 1e-9) // antes del primer downbeat
    }

    @Test
    fun `b is moved to the same point of its bar as a`() {
        val gridB = PhaseAlignment.Grid(bpm = 120.0, firstDownbeatMs = 300, beatsPerBar = 4)
        // A en el 25 % de su compás; B en el 10 %: hay que adelantar B 0,15 compases = 300 ms.
        val correction = PhaseAlignment.entryCorrectionMs(positionAMs = 1_000, gridA = grid120, positionBMs = 500, gridB = gridB)
        assertEquals(300L, correction)
        assertEquals(PhaseAlignment.barPhase(1_000, grid120), PhaseAlignment.barPhase(500 + correction, gridB), 1e-9)
    }

    @Test
    fun `correction never exceeds half a bar`() {
        val gridB = PhaseAlignment.Grid(bpm = 128.0, firstDownbeatMs = 0, beatsPerBar = 4)
        val correction = PhaseAlignment.entryCorrectionMs(positionAMs = 500 + 1_900, gridA = grid120, positionBMs = 100, gridB = gridB)
        assert(kotlin.math.abs(correction) <= PhaseAlignment.barMs(gridB) / 2)
        assertEquals(-194L, correction) // A al 95 %, B al 5,33 %: se retrasa B un 10,33 % de su compás (1 875 ms)
    }
}
