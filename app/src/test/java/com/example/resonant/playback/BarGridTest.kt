package com.example.resonant.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BarGridTest {

    /** 90 BPM exactos, 4/4: beat 666,67 ms, compás 2 666,67 ms; primer downbeat en 300 ms. */
    private val exact90 = PhaseAlignment.Grid(bpm = 90.0, firstDownbeatMs = 300, beatsPerBar = 4)
    private val bar90 = 60_000.0 / 90.0 * 4

    /** Rejilla en bruto cuantizada a 20 ms de una canción a 90 BPM (como la del análisis). */
    private val rawBeats90 = List(200) { ((300 + it * 666.6667) / 20).toInt() * 20 }

    private fun exactGrid(phrases: List<Int>? = null) =
        BarGrid.of(exact90, rawBeats90, null, phrases, 4, durationMs = 180_000)

    // ── Rejilla exacta ──

    @Test
    fun `exact grid places bar k at first downbeat plus k bars without drift`() {
        val grid = exactGrid()
        assertTrue(grid.isExact)
        assertTrue(grid.hasRealDownbeats)
        // Compás 60 (≈ 2:40): con la rejilla en bruto se habrían acumulado hasta 20 ms de error.
        val bar60 = 300 + 60 * bar90
        assertEquals(bar60, grid.downbeatAtOrBefore(bar60 + 500)!!, 1e-6)
        assertEquals(bar60, grid.downbeatAtOrAfter(bar60 - 500)!!, 1e-6)
        assertEquals(bar60, grid.nearestDownbeat(bar60 + 1_000)!!, 1e-6)
        assertEquals(bar60 + bar90, grid.nearestDownbeat(bar60 + 1_500)!!, 1e-6)
    }

    @Test
    fun `rounded downbeat positions are treated as the downbeat itself`() {
        val grid = exactGrid()
        val bar10 = 300 + 10 * bar90 // 26 966,67
        val stored = 26_966.0          // guardado como ms entero, redondeado hacia abajo
        assertEquals(bar10, grid.downbeatAtOrBefore(stored)!!, 1e-6)
        assertEquals(bar10, grid.downbeatAtOrAfter(stored + 1)!!, 1e-6)
    }

    @Test
    fun `no downbeats outside the song`() {
        val grid = exactGrid()
        assertNull(grid.downbeatAtOrBefore(100.0)) // antes del primer compás no hay ninguno ≥ 0 a la izquierda
        assertEquals(300.0, grid.nearestDownbeat(0.0)!!, 1e-9)
        assertNull(grid.downbeatAtOrAfter(179_900.0))
    }

    @Test
    fun `downbeats and beats between two instants`() {
        val grid = exactGrid()
        val bars = grid.downbeatsBetween(0.0, 300 + 3 * bar90)
        assertEquals(4, bars.size)
        assertEquals(300.0, bars[0], 1e-9)
        assertEquals(300 + 3 * bar90, bars[3], 1e-6)

        val beats = grid.beatsBetween(300.0, 300 + bar90 - 1)
        assertEquals(4, beats.size)
        val offBeats = grid.beatsBetween(300.0, 300 + bar90 - 1, includeDownbeats = false)
        assertEquals(3, offBeats.size)
        assertEquals(300 + 60_000.0 / 90.0, offBeats[0], 1e-6)
    }

    @Test
    fun `downbeat indices are stable while scrolling`() {
        val grid = exactGrid()
        val indices = mutableListOf<Int>()
        grid.forEachDownbeat(300 + 5 * bar90 - 10, 300 + 7 * bar90 + 10) { index, _ -> indices += index }
        assertEquals(listOf(5, 6, 7), indices)
    }

    @Test
    fun `bar phase uses the exact grid`() {
        val grid = exactGrid()
        assertEquals(0.0, grid.barPhase(300.0)!!, 1e-3)
        assertEquals(0.25, grid.barPhase(300 + bar90 / 4)!!, 1e-3)
        assertEquals(0.5, grid.barPhase(300 + 40 * bar90 + bar90 / 2)!!, 1e-3)
    }

    @Test
    fun `phrase starts are pulled onto exact downbeats`() {
        val rawPhrase = 300 + (16 * bar90).toInt() + 14 // la frase del análisis cae 14 ms tarde
        val grid = exactGrid(phrases = listOf(rawPhrase))
        assertEquals(listOf(Math.round(300 + 16 * bar90).toInt()), grid.phraseStarts)
    }

    @Test
    fun `snap to bar never leaves the marker between bars`() {
        val grid = exactGrid()
        val bar5 = Math.round(300 + 5 * bar90).toInt()
        val snap = grid.snapToBar(bar5 + 1_200) // casi medio compás después
        assertEquals(BeatMagnet.Level.BAR, snap.level)
        assertEquals(bar5, snap.ms)
    }

    @Test
    fun `snap to bar prefers a phrase start within half a bar`() {
        val phrase = Math.round(300 + 16 * bar90).toInt()
        val grid = exactGrid(phrases = listOf(phrase))
        val snap = grid.snapToBar(phrase + 1_000) // dentro de medio compás de la frase: se engancha a ella
        assertEquals(BeatMagnet.Level.PHRASE, snap.level)
        assertEquals(phrase, snap.ms)
        val far = grid.snapToBar(phrase - 2_000) // a más de medio compás: el downbeat más cercano (15)
        assertEquals(BeatMagnet.Level.BAR, far.level)
        assertEquals(Math.round(300 + 15 * bar90).toInt(), far.ms)
    }

    // ── Rejilla en bruto ──

    @Test
    fun `without exact grid it falls back to the raw downbeats`() {
        val downbeats = listOf(500, 2_500, 4_500, 6_500)
        val grid = BarGrid.of(null, List(16) { 500 + it * 500 }, downbeats, null, 4, durationMs = 10_000)
        assertFalse(grid.isExact)
        assertTrue(grid.hasRealDownbeats)
        assertEquals(2_500.0, grid.downbeatAtOrBefore(3_000.0)!!, 1e-9)
        assertEquals(4_500.0, grid.downbeatAtOrAfter(3_000.0)!!, 1e-9)
        assertEquals(2_500.0, grid.nearestDownbeat(3_400.0)!!, 1e-9)
        assertEquals(2_000.0, grid.barMsAt(3_000.0), 1e-9)
        assertEquals(500.0, grid.beatMsAt(3_000.0), 1e-9)
        assertEquals(0.25, grid.barPhase(3_000.0)!!, 1e-9)
        assertEquals(listOf(2_500.0, 4_500.0), grid.downbeatsBetween(2_000.0, 5_000.0))
        assertEquals(listOf(3_000.0, 3_500.0, 4_000.0), grid.beatsBetween(2_600.0, 4_400.0, includeDownbeats = false))
        assertEquals(BeatMagnet.LevelSnap(2_500, BeatMagnet.Level.BAR), grid.snapToBar(3_400))
    }

    @Test
    fun `with beats only it draws every fourth beat as a bar but does not lock to it`() {
        val grid = BarGrid.of(null, List(16) { 500 + it * 500 }, null, null, null, durationMs = 10_000)
        assertFalse(grid.hasRealDownbeats)
        assertTrue(grid.hasBars)
        assertEquals(listOf(500.0, 2_500.0, 4_500.0), grid.downbeatsBetween(0.0, 5_000.0))
        assertNull(grid.barPhase(3_000.0))
        assertEquals(BeatMagnet.LevelSnap(3_400, BeatMagnet.Level.NONE), grid.snapToBar(3_400))
    }

    @Test
    fun `empty grid has nothing`() {
        val grid = BarGrid.EMPTY
        assertFalse(grid.hasBars)
        assertNull(grid.nearestDownbeat(1_000.0))
        assertTrue(grid.beatsBetween(0.0, 10_000.0).isEmpty())
        assertEquals(0.0, grid.barMsAt(1_000.0), 1e-9)
    }

    // ── Bloqueo de tempo ──

    @Test
    fun `tempo lock ratio needs exact grids with compatible tempos`() {
        val a = BarGrid.of(PhaseAlignment.Grid(90.0, 0, 4), null, null, null, 4, 200_000)
        val b = BarGrid.of(PhaseAlignment.Grid(91.0, 0, 4), null, null, null, 4, 200_000)
        assertEquals(90.0 / 91.0, BarGrid.tempoLockRatio(a, b)!!, 1e-12)
        val far = BarGrid.of(PhaseAlignment.Grid(140.0, 0, 4), null, null, null, 4, 200_000)
        assertNull(BarGrid.tempoLockRatio(a, far))
        val raw = BarGrid.of(null, List(10) { it * 500 }, null, null, 4, 200_000)
        assertNull(BarGrid.tempoLockRatio(a, raw))
        assertNotNull(BarGrid.tempoLockRatio(a, a))
    }
}
