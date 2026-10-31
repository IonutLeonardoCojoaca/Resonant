package com.example.resonant.playback

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.round

/**
 * Mismos casos que `TempoGridTests` del backend (playmix-local-tempo-grid), con los análisis reales de Niagara Falls
 * (~97 BPM y ~104 en el outro, `bpmPrecise` 96,77 sin fase) y Try Me (~92) leídos del fixture `local-tempo-outro`.
 */
class TempoGridTest {

    private val gson = Gson()

    private data class FixtureSong(
        val durationMs: Int, val audioStartMs: Int, val audioEndMs: Int, val bpm: Double?, val bpmPrecise: Double?,
        val bpmStability: Double?, val beatsPerBar: Int?, val firstDownbeatMs: Int?, val downbeatGridJson: List<Int>?,
        val mixInMs: Int?, val mixOutMs: Int?, val dropMs: Int?, val keyCamelot: String?, val downbeatPhaseMs: Int?
    ) {
        fun toSong() = MixComposer.Song(
            durationMs, audioStartMs, audioEndMs, bpm, bpmPrecise, bpmStability, beatsPerBar, firstDownbeatMs,
            downbeatGridJson, mixInMs, mixOutMs, dropMs, keyCamelot, downbeatPhaseMs
        )
    }

    private val fixture: JsonObject by lazy {
        val text = requireNotNull(javaClass.classLoader!!.getResourceAsStream("mix-v2/local-tempo-outro.json"))
            .readBytes().toString(Charsets.UTF_8)
        gson.fromJson(text, JsonObject::class.java)
    }

    private val niagara: MixComposer.Song get() = gson.fromJson(fixture.get("from"), FixtureSong::class.java).toSong()
    private val tryMe: MixComposer.Song get() = gson.fromJson(fixture.get("to"), FixtureSong::class.java).toSong()

    /** 90,00 BPM reales con downbeats en pasos de 20 ms y el `bpmPrecise` cuantizado de antes (90,91), sin fase. */
    private fun quantized90(extra: Int? = null): MixComposer.Song {
        val bar = 60000.0 / 90 * 4
        val downbeats = (0 until 85).map { (round((300 + it * bar) / 20) * 20).toInt() } + listOfNotNull(extra)
        return MixComposer.Song(
            durationMs = 230_000, audioStartMs = 0, audioEndMs = 229_000, bpmPrecise = 90.91, bpmStability = 0.7,
            beatsPerBar = 4, firstDownbeatMs = downbeats[0], downbeats = downbeats
        )
    }

    private fun assertOnDetected(grid: TempoGrid.Grid, downbeats: List<Int>, from: Double, to: Double, tolerance: Double) {
        val first = grid.firstMs!!
        for (d in downbeats.filter { it >= from && it <= to }) {
            val nearest = first + round((d - first) / grid.barMs) * grid.barMs
            assertTrue("downbeat $d a ${abs(d - nearest)} ms", abs(d - nearest) <= tolerance)
        }
    }

    @Test
    fun disabledReturnsTheGlobalGrid() {
        assertEquals(TempoGrid.global(niagara), TempoGrid.at(niagara, 252_040.0, 261_270.0, localEnabled = false))
    }

    @Test
    fun niagaraOutroGetsALocalGridAtAbout104Bpm() {
        val grid = TempoGrid.at(niagara, 252_040.0, 261_270.0, localEnabled = true)!!

        assertEquals(GridKind.LOCAL, grid.kind)
        assertTrue("${grid.bpm}", grid.bpm!! in 103.6..104.2)
        assertEquals(60000.0 * 4 / grid.bpm!!, grid.barMs, 1e-6)
        assertOnDetected(grid, niagara.downbeats!!, 252_040.0, 261_270.0, 25.0)
    }

    @Test
    fun niagaraBeforeTheTempoChangeIgnoresTheDownbeatsAfterIt() {
        val grid = TempoGrid.at(niagara, 197_420.0, 217_160.0, localEnabled = true)!!

        assertEquals(GridKind.LOCAL, grid.kind)
        assertTrue("${grid.bpm}", grid.bpm!! in 97.1..97.3)
        assertOnDetected(grid, niagara.downbeats!!, 197_420.0, 217_160.0, 25.0)
    }

    @Test
    fun aWindowAcrossTheTempoChangeHasNoGrid() {
        val grid = TempoGrid.at(niagara, 217_160.0, 236_900.0, localEnabled = true)!!

        assertEquals(GridKind.NONE, grid.kind)
        assertNull(grid.firstMs)
        assertNull(grid.bpm)
        assertEquals(0.0, grid.phaseAt(217_160), 0.0)
    }

    @Test
    fun tryMeIntroKeepsItsGlobalGrid() {
        assertEquals(TempoGrid.global(tryMe), TempoGrid.at(tryMe, 20_900.0, 31_340.0, localEnabled = true))
    }

    @Test
    fun aSongWithFittedPhaseAlwaysUsesItsGlobalGrid() {
        val stable = niagara.copy(downbeatPhaseMs = 0)
        assertEquals(TempoGrid.global(stable), TempoGrid.at(stable, 252_040.0, 261_270.0, localEnabled = true))
    }

    @Test
    fun quantizedBpmIsCorrectedInTheWindow() {
        val grid = TempoGrid.at(quantized90(), 200_000.0, 210_667.0, localEnabled = true)!!

        assertEquals(GridKind.LOCAL, grid.kind)
        assertTrue("${grid.bpm}", grid.bpm!! in 89.9..90.1)
    }

    @Test
    fun aStrayDownbeatMidBarIsIgnored() {
        val clean = TempoGrid.at(quantized90(), 200_000.0, 221_333.0, localEnabled = true)!!
        val stray = quantized90().downbeats!!.first { it > 205_000 } + 1_340
        val grid = TempoGrid.at(quantized90(stray), 200_000.0, 221_333.0, localEnabled = true)!!

        assertEquals(GridKind.LOCAL, grid.kind)
        assertEquals(clean.bpm, grid.bpm)
        assertEquals(clean.firstMs!!, grid.firstMs!!, 1e-6)
    }

    @Test
    fun withoutDownbeatsThereIsNoLocalGrid() {
        val bare = niagara.copy(downbeats = null, firstDownbeatMs = null)
        assertEquals(GridKind.NONE, TempoGrid.at(bare, 252_040.0, 261_270.0, localEnabled = true)!!.kind)
    }

    @Test
    fun syncTempoAndStabilityFollowTheGridKind() {
        val local = TempoGrid.at(niagara, 252_040.0, 261_270.0, localEnabled = true)
        val none = TempoGrid.at(niagara, 217_160.0, 236_900.0, localEnabled = true)
        val global = TempoGrid.at(niagara, 252_040.0, 261_270.0, localEnabled = false)

        assertEquals(local!!.bpm, TempoGrid.tempoOf(local, niagara))
        assertEquals(1.0, TempoGrid.stabilityOf(local, niagara), 0.0)
        assertNull(TempoGrid.tempoOf(none, niagara))
        assertEquals(0.0, TempoGrid.stabilityOf(none, niagara), 0.0)
        assertEquals(96.77, TempoGrid.tempoOf(global, niagara)!!, 0.0)
        assertEquals(0.797, TempoGrid.stabilityOf(global, niagara), 0.0)
    }

    @Test
    fun windowTemposDecideTheSync() {
        assertTrue(MixComposer.canSync(niagara, tryMe, MixComposer.Window(197_420, 20_900, 8), extended = false, localTempo = true))
        assertTrue(MixComposer.tempoClash(niagara, tryMe, MixComposer.Window(252_040, 20_900, 4), extended = false, localTempo = true))
        // Con BpmPrecise (96,77 / 91,992 = 5,2 %) se podía forzar en el outro aunque A suene a 104.
        assertTrue(MixComposer.canSync(niagara, tryMe, MixComposer.Window(252_040, 20_900, 4), extended = false, localTempo = false))
        assertTrue(!MixComposer.canSync(niagara, tryMe, MixComposer.Window(252_040, 20_900, 4), extended = false, localTempo = true))
    }
}
