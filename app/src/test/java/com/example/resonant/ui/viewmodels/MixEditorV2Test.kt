package com.example.resonant.ui.viewmodels

import com.example.resonant.data.network.MixDocumentDTO
import com.example.resonant.data.network.MixSideADTO
import com.example.resonant.data.network.MixSideBDTO
import com.example.resonant.data.network.StudioSongDTO
import com.example.resonant.data.network.StudioTransitionDTO
import com.example.resonant.playback.GridKind
import com.example.resonant.playback.MixComposer
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MixEditorV2Test {

    private fun song(id: String, bpm: Double, durationMs: Int, firstDownbeatMs: Int, mixInMs: Int? = null): StudioSongDTO {
        val bar = 60000.0 / bpm * 4
        val downbeats = generateSequence(firstDownbeatMs.toDouble()) { it + bar }.takeWhile { it < durationMs }.map { it.toInt() }.toList()
        return StudioSongDTO(
            songId = id, title = id, durationMs = durationMs, bpm = bpm, beatsPerBar = 4, downbeats = downbeats,
            camelot = "8A", mixInMs = mixInMs, bpmPrecise = bpm, firstDownbeatMs = firstDownbeatMs,
            audioStartMs = 0, audioEndMs = durationMs - 1500, bpmStability = 0.97
        )
    }

    private val songA = song("a", 126.0, 240_000, 500)
    private val songB = song("b", 128.0, 200_000, 300, mixInMs = 15_300)
    private val barA = 60000.0 / 126 * 4

    private fun v1Studio() = StudioTransitionDTO(
        transitionId = "t1", revision = 3, derived = true, canEdit = true,
        document = MixDocumentDTO(1, 8000, MixSideADTO(220_000), MixSideBDTO(0)),
        songA = songA, songB = songB
    )

    private fun initial() = MixEditorV2.initial(v1Studio())!!

    @Test
    fun `without a v2 document the editor starts on Automatica`() {
        val state = initial()
        assertEquals(MixEditorV2.AUTOMATIC, state.presetCode)
        assertTrue(state.document.isV2)
        assertEquals(state.window.bars, state.document.bars)
    }

    @Test
    fun `a saved v2 document is kept as is`() {
        val saved = MixEditorV2.selectPreset(initial(), "corte").document
        val state = MixEditorV2.initial(v1Studio().copy(document = saved, derived = false))!!
        assertEquals("corte", state.presetCode)
        assertEquals(saved, state.document)
        assertEquals(saved.a.startMs, state.window.aStartMs)
    }

    @Test
    fun `changing a lane option switches to Personalizada and regenerates the document`() {
        val state = MixEditorV2.selectLaneOption(MixEditorV2.selectPreset(initial(), "fusion"), MixEditorV2.LANE_EQ, "bass-cut-fast")
        assertEquals(MixEditorV2.CUSTOM, state.presetCode)
        assertEquals("bass-cut-fast", state.selection.eq)
        assertEquals("bass-cut-fast", state.document.selection?.eq)
        assertEquals(MixEditorV2.CUSTOM, state.document.preset?.code)
    }

    @Test
    fun `lane options matching a preset select that preset again`() {
        val corte = MixEditorV2.selectPreset(initial(), "corte")
        val custom = MixEditorV2.selectLaneOption(corte, MixEditorV2.LANE_VOLUME, "overlap")
        assertEquals(MixEditorV2.CUSTOM, custom.presetCode)
        assertEquals("corte", MixEditorV2.selectLaneOption(custom, MixEditorV2.LANE_VOLUME, "cut-center").presetCode)
    }

    @Test
    fun `choosing a preset keeps the window`() {
        val auto = initial()
        val fundido = MixEditorV2.selectPreset(auto, "fundido")
        assertEquals(auto.window, fundido.window)
        assertEquals("smooth-crossfade", fundido.document.selection?.volume)
    }

    @Test
    fun `bars change the window length`() {
        val state = MixEditorV2.setBars(MixEditorV2.selectPreset(initial(), "fundido"), 8)
        assertEquals(8, state.document.bars)
        assertTrue(abs(state.document.durationMs - 8 * barA) < 5)
        assertEquals(state, MixEditorV2.setBars(state, 3))
    }

    @Test
    fun `window arrows move both songs by one bar`() {
        val state = MixEditorV2.selectPreset(initial(), "fundido")
        val moved = MixEditorV2.moveWindow(state, -1)
        assertTrue(abs(state.document.a.startMs - moved.document.a.startMs - barA) < 3)
        assertTrue(moved.document.b.startMs <= state.document.b.startMs)
        assertEquals("fundido", moved.presetCode)
    }

    @Test
    fun `dragging A snaps to the nearest bar and switches to Personalizada`() {
        val state = MixEditorV2.selectPreset(initial(), "fundido")
        val target = state.document.a.startMs - (3.4 * barA).toInt()
        val dragged = MixEditorV2.dragA(state, target)
        assertEquals(MixEditorV2.CUSTOM, dragged.presetCode)
        val bars = (dragged.document.a.startMs - 500) / barA
        assertTrue("A en compás: $bars", abs(bars - Math.rint(bars)) * barA < 3)
        assertTrue(abs(dragged.document.a.startMs - (state.document.a.startMs - 3 * barA)) < 3)
    }

    @Test
    fun `dragging with zoom snaps to the beat`() {
        val state = MixEditorV2.selectPreset(initial(), "fundido")
        val beat = barA / 4
        val target = state.document.b.startMs + (2.1 * beat).toInt()
        val dragged = MixEditorV2.dragB(state, target, toBeat = true)
        val beatB = 60000.0 / 128
        val beats = (dragged.document.b.startMs - 300) / beatB
        assertTrue(abs(beats - Math.rint(beats)) * beatB < 3)
        assertNotEquals(state.document.b.startMs, dragged.document.b.startMs)
    }

    @Test
    fun `preview uses the unsaved document from two bars before the window`() {
        val state = MixEditorV2.selectLaneOption(initial(), MixEditorV2.LANE_FILTER, "hpf-out")
        val preview = MixEditorV2.preview(state)
        assertEquals("a", preview.fromSongId)
        assertEquals("b", preview.toSongId)
        assertEquals(state.document, preview.document)
        assertTrue(abs(state.document.a.startMs - 2 * barA - preview.startPositionMs) < 3)
    }

    @Test
    fun `server presets are used only when they carry v2 selections`() {
        assertEquals(MixEditorV2.DEFAULT_PRESETS, MixEditorV2.presetsOrDefault(emptyList()))
        val server = MixEditorV2.DEFAULT_PRESETS.map { it.copy(name = it.name + "!") }
        assertEquals(server, MixEditorV2.presetsOrDefault(server))
    }

    @Test
    fun `default presets match the seed selections`() {
        val codes = MixEditorV2.DEFAULT_PRESETS.map { it.code }
        assertEquals(11, codes.size)
        assertEquals(MixComposer.AUTOMATIC_CODE, codes.first())
        assertTrue(MixEditorV2.DEFAULT_LANE_OPTIONS.volume.any { it.code == "smooth-crossfade" })
    }

    @Test
    fun `status label tells unsaved changes and the render state apart`() {
        assertEquals("● Cambios sin guardar", MixEditorV2.statusLabel(dirty = true, renderStatus = "ready"))
        assertEquals("✓ Guardada · mezcla renderizada", MixEditorV2.statusLabel(false, "ready"))
        assertEquals("◌ Guardada · renderizando la mezcla…", MixEditorV2.statusLabel(false, "pending"))
        assertEquals("Guardada · suena en directo", MixEditorV2.statusLabel(false, null))
    }

    @Test
    fun `custom mixes are described by their lane options`() {
        val state = MixEditorV2.selectLaneOption(MixEditorV2.selectPreset(initial(), "corte"), MixEditorV2.LANE_FX, "echo-out")
        assertEquals("Corte central · Eco de salida", MixEditorV2.describe(state))
        assertEquals("cut-center", MixEditorV2.optionOf(state.selection, MixEditorV2.LANE_VOLUME))
    }

    @Test
    fun `beatmatching can be forced when only the stability blocks it`() {
        // Tempos a ~1 % pero B "poco estable" (como After Hours, con outro ambiental): Automática no sincroniza,
        // el interruptor sí puede forzarlo, y volver a Automática recupera el valor por defecto.
        val unstableB = song("b", 127.0, 200_000, 300, mixInMs = 15_300).copy(bpmStability = 0.72)
        val studio = v1Studio().copy(songB = unstableB)
        val auto = MixEditorV2.initial(studio)!!
        assertEquals(false, auto.document.beatSync)

        val forced = MixEditorV2.setBeatSync(auto, true)
        assertEquals(true, forced.document.beatSync)
        assertEquals(false, MixEditorV2.selectPreset(forced, MixEditorV2.AUTOMATIC).document.beatSync)
    }

    // ─── playmix-local-tempo-grid ─────────────────────────────────────────────────────────────────

    private val gson = Gson()

    private data class FixtureSong(
        val durationMs: Int, val audioStartMs: Int, val audioEndMs: Int, val bpm: Double?, val bpmPrecise: Double?,
        val bpmStability: Double?, val beatsPerBar: Int?, val firstDownbeatMs: Int?, val downbeatGridJson: List<Int>?,
        val mixInMs: Int?, val mixOutMs: Int?, val dropMs: Int?, val keyCamelot: String?, val downbeatPhaseMs: Int?
    ) {
        fun toStudio(id: String) = StudioSongDTO(
            songId = id, title = id, durationMs = durationMs, bpm = bpm, beatsPerBar = beatsPerBar, downbeats = downbeatGridJson,
            camelot = keyCamelot, mixInMs = mixInMs, mixOutMs = mixOutMs, dropMs = dropMs, bpmPrecise = bpmPrecise,
            firstDownbeatMs = firstDownbeatMs, downbeatPhaseMs = downbeatPhaseMs, audioStartMs = audioStartMs,
            audioEndMs = audioEndMs, bpmStability = bpmStability
        )
    }

    private fun fixture(name: String): JsonObject {
        val text = requireNotNull(javaClass.classLoader!!.getResourceAsStream("mix-v2/$name.json"))
            .readBytes().toString(Charsets.UTF_8)
        return gson.fromJson(text, JsonObject::class.java)
    }

    /** Niagara Falls (~97 BPM, outro a ~104, sin fase) → Try Me (~92), con los análisis reales de los fixtures. */
    private fun niagaraStudio(document: MixDocumentDTO? = null): StudioTransitionDTO {
        val f = fixture("local-tempo-outro")
        return StudioTransitionDTO(
            transitionId = "nt", revision = 1, derived = document == null, canEdit = true,
            document = document ?: MixDocumentDTO(1, 8000, MixSideADTO(250_000), MixSideBDTO(0)),
            songA = gson.fromJson(f.get("from"), FixtureSong::class.java).toStudio("niagara"),
            songB = gson.fromJson(f.get("to"), FixtureSong::class.java).toStudio("tryme")
        )
    }

    private fun niagara(localTempo: Boolean = true) = MixEditorV2.initial(niagaraStudio(), localTempo = localTempo)!!

    @Test
    fun `without the capability a stable pair is edited exactly as before`() {
        val off = initial()
        val on = MixEditorV2.initial(v1Studio(), localTempo = true)!!
        assertEquals(off.document, on.document)
        assertNull(on.document.a.bpm)
        val target = off.document.a.startMs - (3.4 * barA).toInt()
        assertEquals(MixEditorV2.dragA(off, target).document, MixEditorV2.dragA(on, target, captureMs = 40).document)
        assertEquals(MixEditorV2.moveWindow(off, 2).document, MixEditorV2.moveWindow(on, 2).document)
    }

    @Test
    fun `without the capability Niagara Falls still snaps to the global beat`() {
        val dragged = MixEditorV2.dragA(niagara(localTempo = false), 252_040, captureMs = 40)
        assertEquals(251_731, dragged.document.a.startMs)
    }

    @Test
    fun `without beatmatching A stays on the real downbeat it is dropped on`() {
        val state = niagara()
        assertEquals(false, state.document.beatSync)
        assertEquals(252_040, MixEditorV2.dragA(state, 252_040, captureMs = 40).document.a.startMs)
    }

    @Test
    fun `without beatmatching a drop far from a downbeat is kept to the millisecond`() {
        val state = niagara()
        val dragged = MixEditorV2.dragA(state, 252_160, captureMs = 200)
        assertEquals(252_160, dragged.document.a.startMs)
        assertEquals(MixEditorV2.CUSTOM, dragged.presetCode)
        assertFalse(MixEditorV2.dragSnaps(state, isA = true, ms = 252_160, toBeat = false, captureMs = 200))
    }

    @Test
    fun `without beatmatching a drop close to a downbeat sticks to it`() {
        val state = niagara()
        assertEquals(252_040, MixEditorV2.dragA(state, 252_080, captureMs = 60).document.a.startMs)
        assertTrue(MixEditorV2.dragSnaps(state, isA = true, ms = 252_080, toBeat = false, captureMs = 60))
        // Fuera del radio de pantalla no engancha aunque esté a menos de 1/8 de beat.
        assertEquals(252_080, MixEditorV2.dragA(state, 252_080, captureMs = 30).document.a.startMs)
    }

    @Test
    fun `with beatmatching the editor uses the window tempos and grid`() {
        val saved = gson.fromJson(fixture("local-tempo-before-change").get("document"), MixDocumentDTO::class.java)
        val state = MixEditorV2.initial(niagaraStudio(saved), localTempo = true)!!
        assertEquals(true, state.document.beatSync)
        assertEquals(GridKind.LOCAL, state.grids.a?.kind)
        assertEquals(MixComposer.rate(97.209, 91.992), state.rateB, 1e-9)
        assertEquals(97.209, state.windowTempos.a!!, 1e-9)
        assertEquals(60000.0 * 4 / 97.209, state.barMsA!!, 1e-6)

        val dragged = MixEditorV2.dragA(state, saved.a.startMs + 700, captureMs = 40)
        assertTrue("A en ${dragged.document.a.startMs}", abs(dragged.document.a.startMs - saved.a.startMs) <= 3)
        assertEquals(true, dragged.document.beatSync)
        assertEquals(97.209, dragged.document.a.bpm!!, 0.05)
    }
}
