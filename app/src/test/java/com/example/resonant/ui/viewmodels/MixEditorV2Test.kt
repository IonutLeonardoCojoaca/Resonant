package com.example.resonant.ui.viewmodels

import com.example.resonant.data.network.MixDocumentDTO
import com.example.resonant.data.network.MixSideADTO
import com.example.resonant.data.network.MixSideBDTO
import com.example.resonant.data.network.StudioSongDTO
import com.example.resonant.data.network.StudioTransitionDTO
import com.example.resonant.playback.MixComposer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
}
