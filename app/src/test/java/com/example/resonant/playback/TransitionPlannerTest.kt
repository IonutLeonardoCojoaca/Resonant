package com.example.resonant.playback

import com.example.resonant.data.models.AudioAnalysis
import com.example.resonant.playback.TransitionPlanner.Mode
import com.example.resonant.playback.TransitionPlanner.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionPlannerTest {

    private fun track(
        durationMs: Long = 200_000,
        audioStartMs: Long = 0,
        audioEndMs: Long = 198_000,
        mixInMs: Long? = null,
        mixOutMs: Long? = null,
        grid: PhaseAlignment.Grid? = null
    ) = Track(durationMs, audioStartMs, audioEndMs, mixInMs, mixOutMs, grid)

    private fun assertConstantPower(plan: TransitionPlanner.Plan) {
        val doc = plan.doc
        for (step in 0..20) {
            val t = doc.durationMs * step / 20.0
            val a = MixLanes.gain(doc, 'a', t)
            val b = MixLanes.gain(doc, 'b', t)
            assertEquals("potencia en t=$t", 1.0, a * a + b * b, 1e-9)
        }
    }

    // ── Suave ──

    @Test
    fun `soft ends where A's audio ends and B starts after its silence`() {
        val plan = TransitionPlanner.plan(Mode.SOFT, track(), track(audioStartMs = 500), 6_000)!!
        assertEquals(192_000, plan.doc.a.startMs)
        assertEquals(6_000, plan.doc.durationMs)
        assertEquals(500, plan.doc.b.startMs)
        assertEquals("soft", plan.label)
        assertFalse(plan.hasEq)
        assertFalse(plan.beatSync)
        assertConstantPower(plan)
    }

    @Test
    fun `soft snaps its start to A's previous bar when it is close`() {
        val grid = PhaseAlignment.Grid(bpm = 120.0, firstDownbeatMs = 0, beatsPerBar = 4) // compás de 2 s
        val plan = TransitionPlanner.plan(Mode.SOFT, track(grid = grid), track(), 5_000)!!
        assertEquals(192_000, plan.doc.a.startMs)
        assertEquals(6_000, plan.doc.durationMs) // sigue terminando en el final del audio de A
    }

    @Test
    fun `soft with zero seconds is no transition`() {
        assertNull(TransitionPlanner.plan(Mode.SOFT, track(), track(), 0))
    }

    // ── Corte ──

    @Test
    fun `cut without grid splices at the end of A's audio`() {
        val plan = TransitionPlanner.plan(Mode.CUT, track(), track(audioStartMs = 500), 5_000)!!
        assertEquals(198_000 - 60, plan.doc.a.startMs)
        assertEquals(TransitionPlanner.CUT_SPLICE_MS.toInt(), plan.doc.durationMs)
        assertEquals(440, plan.doc.b.startMs)
        assertEquals("cut", plan.label)
        assertConstantPower(plan)
    }

    @Test
    fun `cut with grids lands B's first downbeat on A's bar`() {
        val gridA = PhaseAlignment.Grid(bpm = 120.0, firstDownbeatMs = 0, beatsPerBar = 4)
        val gridB = PhaseAlignment.Grid(bpm = 120.0, firstDownbeatMs = 1_200, beatsPerBar = 4)
        val plan = TransitionPlanner.plan(
            Mode.CUT,
            track(audioEndMs = 198_500, grid = gridA),
            track(audioStartMs = 200, grid = gridB),
            5_000
        )!!
        // Downbeat de A más cercano a 198 500 = 198 000; B tarda 1 000 ms en su primer downbeat.
        assertEquals("cut-beat", plan.label)
        assertEquals(197_000 - 60, plan.doc.a.startMs)
        assertEquals(140, plan.doc.b.startMs)
    }

    // ── Inteligente ──

    @Test
    fun `smart beatmatches A's outro over B's intro with a bass swap and tempo sync`() {
        val gridA = PhaseAlignment.Grid(bpm = 128.0, firstDownbeatMs = 100, beatsPerBar = 4)  // compás 1 875 ms
        val gridB = PhaseAlignment.Grid(bpm = 126.0, firstDownbeatMs = 300, beatsPerBar = 4)
        val plan = TransitionPlanner.plan(
            Mode.SMART,
            track(durationMs = 240_000, audioEndMs = 238_000, mixOutMs = 210_100, grid = gridA),
            track(durationMs = 200_000, audioEndMs = 199_000, mixInMs = 30_776, grid = gridB),
            0
        )!!
        // Outro de A = 27,9 s: 16 compases (30 s) no caben, 8 sí. Termina en el último compás de A.
        assertEquals("smart-beatmatch-8bars", plan.label)
        assertEquals(221_350, plan.doc.a.startMs)
        assertEquals(15_000, plan.doc.durationMs)
        assertEquals(300, plan.doc.b.startMs) // primer downbeat de B: no se salta su intro
        assertTrue(plan.beatSync)
        assertEquals(128.0 / 126.0, plan.bpmRatio, 1e-9)
        assertTrue(plan.hasEq)

        val doc = plan.doc
        assertEquals(1.0, MixLanes.gain(doc, 'a', 7_500.0), 1e-9)       // A entera hasta la mitad
        assertEquals(1.0, MixLanes.gain(doc, 'b', 7_500.0), 1e-9)       // B ya entera en la mitad
        assertEquals(0.0, MixLanes.gain(doc, 'a', 15_000.0), 1e-9)
        assertEquals(0.0, MixLanes.eqDb(doc, 'a', "low", 7_000.0), 1e-9) // graves de A hasta un beat antes
        assertEquals(TransitionPlanner.BASS_KILL_DB, MixLanes.eqDb(doc, 'a', "low", 7_500.0), 1e-9)
        assertEquals(TransitionPlanner.BASS_KILL_DB, MixLanes.eqDb(doc, 'b', "low", 7_000.0), 1e-9)
        assertEquals(0.0, MixLanes.eqDb(doc, 'b', "low", 7_500.0), 1e-9) // intercambio de graves
    }

    @Test
    fun `smart without compatible tempo blends over A's outro from B's start`() {
        val plan = TransitionPlanner.plan(
            Mode.SMART,
            track(mixOutMs = 189_000),
            track(audioStartMs = 300, mixInMs = 8_000),
            0
        )!!
        assertEquals("smart-blend", plan.label)
        assertEquals(189_000, plan.doc.a.startMs)
        assertEquals(9_000, plan.doc.durationMs)
        assertEquals(300, plan.doc.b.startMs)
        assertFalse(plan.beatSync)
        assertConstantPower(plan)
    }

    @Test
    fun `smart blend uses the default length and never skips B's intro`() {
        val plan = TransitionPlanner.plan(Mode.SMART, track(), track(audioStartMs = 300, mixInMs = 40_000), 0)!!
        assertEquals(198_000 - 8_000, plan.doc.a.startMs)
        assertEquals(8_000, plan.doc.durationMs)
        assertEquals(300, plan.doc.b.startMs)
    }

    @Test
    fun `beatmatch ratio rejects tempos too far apart`() {
        val a = track(grid = PhaseAlignment.Grid(128.0, 0, 4))
        assertNotNull(TransitionPlanner.beatmatchRatio(a, track(grid = PhaseAlignment.Grid(124.0, 0, 4))))
        assertNull(TransitionPlanner.beatmatchRatio(a, track(grid = PhaseAlignment.Grid(100.0, 0, 4))))
        assertNull(TransitionPlanner.beatmatchRatio(a, track()))
    }

    // ── Pistas ──

    @Test
    fun `trackOf keeps cue points only inside the audio`() {
        val analysis = AudioAnalysis(
            durationMs = 200_000, audioStartMs = 400, audioEndMs = 0,
            bpmPrecise = 120.0, firstDownbeatMs = 500, beatsPerBar = 4,
            mixInMs = 300, mixOutMs = 180_000
        )
        val t = TransitionPlanner.trackOf(analysis, exoDurationMs = -1)!!
        assertEquals(200_000L, t.durationMs)
        assertEquals(200_000L, t.audioEndMs)   // sin audioEnd: la duración
        assertNull(t.mixInMs)                   // antes del inicio del audio
        assertEquals(180_000L, t.mixOutMs)
        assertNotNull(t.grid)
        assertNull(TransitionPlanner.trackOf(null, exoDurationMs = -1))
    }

    @Test
    fun `trackOf falls back to v1 optimal points without cue points`() {
        val analysis = AudioAnalysis(
            durationMs = 200_000, audioStartMs = 400, audioEndMs = 198_000,
            optimalStartPointMs = 6_000, optimalExitPointMs = 170_000
        )
        val t = TransitionPlanner.trackOf(analysis, exoDurationMs = -1)!!
        assertEquals(6_000L, t.mixInMs)
        assertEquals(170_000L, t.mixOutMs)
        assertNull(t.grid)
    }
}
