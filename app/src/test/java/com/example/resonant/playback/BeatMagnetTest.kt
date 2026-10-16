package com.example.resonant.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatMagnetTest {

    /** Rejilla a 120 BPM (500 ms) que empieza en 250 ms. */
    private val grid120 = List(40) { 250 + it * 500 }

    @Test
    fun `nearest index picks the closest beat on both sides`() {
        assertEquals(0, BeatMagnet.nearestIndex(0, grid120))
        assertEquals(0, BeatMagnet.nearestIndex(499, grid120))     // 250 está a 249, 750 a 251
        assertEquals(1, BeatMagnet.nearestIndex(501, grid120))
        assertEquals(39, BeatMagnet.nearestIndex(999_999, grid120))
        assertEquals(-1, BeatMagnet.nearestIndex(100, emptyList()))
    }

    @Test
    fun `empty grid returns the input untouched`() {
        assertEquals(1_234, BeatMagnet.nearestBeat(1_234, emptyList()))
        assertEquals(BeatMagnet.Snap(1_234, false), BeatMagnet.snap(1_234, emptyList()))
    }

    @Test
    fun `snaps when within the capture fraction of the local interval`() {
        // Umbral a 120 BPM: 500 * 0.35 = 175 ms.
        assertEquals(BeatMagnet.Snap(2_250, true), BeatMagnet.snap(2_250 + 170, grid120))
        assertEquals(BeatMagnet.Snap(2_250, true), BeatMagnet.snap(2_250 - 170, grid120))
    }

    @Test
    fun `does not snap when outside the capture fraction`() {
        val result = BeatMagnet.snap(2_250 + 200, grid120)
        assertFalse(result.snapped)
        assertEquals(2_450, result.ms)
    }

    @Test
    fun `capture threshold scales with tempo`() {
        val grid170 = List(40) { it * 353 } // ~170 BPM
        val grid70 = List(40) { it * 857 }  // ~70 BPM
        assertEquals((353 * 0.35f).toInt(), BeatMagnet.captureThresholdMs(5, grid170))
        assertEquals((857 * 0.35f).toInt(), BeatMagnet.captureThresholdMs(5, grid70))
        // A 70 BPM un desvío de 200 ms engancha; a 170 BPM no.
        assertTrue(BeatMagnet.snap(857 * 5 + 200, grid70).snapped)
        assertFalse(BeatMagnet.snap(353 * 5 + 200, grid170).snapped)
    }

    @Test
    fun `irregular grid uses the shorter neighbouring interval`() {
        val grid = listOf(0, 500, 700, 1_200)
        // El beat en 500 tiene 500 antes y 200 después: el umbral sale del de 200 (70 ms).
        assertEquals(70, BeatMagnet.captureThresholdMs(1, grid))
        assertFalse(BeatMagnet.snap(500 + 100, grid).snapped)
        assertTrue(BeatMagnet.snap(500 + 60, grid).snapped)
    }

    @Test
    fun `single beat grid uses the fallback threshold`() {
        val grid = listOf(10_000)
        assertEquals(BeatMagnet.FALLBACK_CAPTURE_MS, BeatMagnet.captureThresholdMs(0, grid))
        assertTrue(BeatMagnet.snap(10_000 + 100, grid).snapped)
        assertFalse(BeatMagnet.snap(10_000 + 400, grid).snapped)
    }

    @Test
    fun `beyond the last beat the marker is free`() {
        val lastBeat = grid120.last()
        assertFalse(BeatMagnet.snap(lastBeat + 5_000, grid120).snapped)
        assertEquals(lastBeat + 5_000, BeatMagnet.snap(lastBeat + 5_000, grid120).ms)
    }

    // ── Niveles (Fase 4) ─────────────────────────────────────────────────────

    private val beats = (0 until 64).map { 500 + it * 500 }                 // 120 BPM
    private val downbeats = beats.filterIndexed { i, _ -> i % 4 == 0 }       // compás 2 000 ms
    private val phrases = downbeats.filterIndexed { i, _ -> i % 8 == 0 }     // frase 16 000 ms

    @Test
    fun `phrase start wins over a closer beat`() {
        // 16 500 es inicio de frase; 16 900 está a 400 ms de él y a 100 ms del beat 17 000.
        val snap = BeatMagnet.snapLevels(16_900, beats, downbeats, phrases)
        assertEquals(BeatMagnet.LevelSnap(16_500, BeatMagnet.Level.PHRASE), snap)
    }

    @Test
    fun `downbeat when far from phrases`() {
        // 8 500 es downbeat (no frase); 8 900 está a 400 ms (< 1/3 de compás).
        assertEquals(BeatMagnet.LevelSnap(8_500, BeatMagnet.Level.BAR), BeatMagnet.snapLevels(8_900, beats, downbeats, phrases))
    }

    @Test
    fun `beat when far from bars`() {
        // 9 500 es beat; 9 600 está a 1 100 ms del downbeat 8 500 y a 900 del 10 500.
        assertEquals(BeatMagnet.LevelSnap(9_500, BeatMagnet.Level.BEAT), BeatMagnet.snapLevels(9_600, beats, downbeats, phrases))
    }

    @Test
    fun `without v2 data levels behave like the beat magnet`() {
        assertEquals(BeatMagnet.LevelSnap(9_500, BeatMagnet.Level.BEAT), BeatMagnet.snapLevels(9_600, beats, emptyList(), emptyList()))
        assertEquals(BeatMagnet.LevelSnap(9_750, BeatMagnet.Level.NONE), BeatMagnet.snapLevels(9_750, beats, emptyList(), emptyList()))
    }
}
