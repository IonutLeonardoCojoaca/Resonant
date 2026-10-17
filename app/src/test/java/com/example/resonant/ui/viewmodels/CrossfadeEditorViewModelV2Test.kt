package com.example.resonant.ui.viewmodels

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.resonant.data.network.MixDocumentDTO
import com.example.resonant.data.network.MixLaneOptionDTO
import com.example.resonant.data.network.MixLaneOptionsDTO
import com.example.resonant.data.network.MixSideADTO
import com.example.resonant.data.network.MixSideBDTO
import com.example.resonant.data.network.StudioSongDTO
import com.example.resonant.data.network.StudioTransitionDTO
import com.example.resonant.data.network.TransitionPresetDTO
import com.example.resonant.data.network.WaveformResponseDTO
import com.example.resonant.managers.PlaymixManager
import com.example.resonant.managers.StudioManager
import com.example.resonant.managers.TransitionPresetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Editor v2 en el ViewModel (spotify-style-transition-engine 9.3 y 9.4). */
@OptIn(ExperimentalCoroutinesApi::class)
class CrossfadeEditorViewModelV2Test {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var studio: FakeStudioManager
    private lateinit var presets: FakePresetManager

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        studio = FakeStudioManager()
        presets = FakePresetManager()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(v2Enabled: Boolean = true) = CrossfadeEditorViewModel(
        FakePlaymixManager(), presets, dispatcher, studio, v2Enabled = v2Enabled, bandsLoader = { null }
    )

    @Test
    fun `with the v2 engine the editor builds an Automatica document`() = runTest {
        val vm = viewModel()
        vm.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()

        val state = vm.state.value!!
        assertNotNull(state.v2)
        val v2 = state.v2!!
        assertEquals(MixEditorV2.AUTOMATIC, v2.presetCode)
        assertTrue(state.document!!.isV2)
        assertEquals(v2.document, state.document)
        assertEquals("Superposición del servidor", v2.laneOptions.volume.first().name)
    }

    @Test
    fun `without the v2 engine the legacy editor is untouched`() = runTest {
        val vm = viewModel(v2Enabled = false)
        vm.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()

        assertNull(vm.state.value!!.v2)
        assertEquals(1, vm.state.value!!.document!!.version)
    }

    @Test
    fun `changing a lane option updates the document and switches to Personalizada`() = runTest {
        val vm = viewModel()
        vm.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()

        vm.v2SelectPreset("fundido")
        vm.v2SelectLaneOption(MixEditorV2.LANE_FILTER, "lpf-out")

        val state = vm.state.value!!
        assertEquals(MixEditorV2.CUSTOM, state.v2!!.presetCode)
        assertEquals(MixEditorV2.CUSTOM, state.styleCode)
        assertEquals("lpf-out", state.document!!.selection!!.filter)
        assertTrue(state.document!!.lanes!!.containsKey("a.filter"))
    }

    @Test
    fun `bars and window arrows regenerate the document`() = runTest {
        val vm = viewModel()
        vm.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()
        vm.v2SelectPreset("fundido")
        val before = vm.state.value!!.document!!

        vm.v2SetBars(16)
        vm.v2MoveWindow(-2)

        val after = vm.state.value!!.document!!
        assertEquals(16, after.bars)
        assertTrue(after.durationMs > before.durationMs)
        assertEquals(after.a.startMs, vm.state.value!!.exitPointMs)
    }

    @Test
    fun `save sends the v2 document`() = runTest {
        val vm = viewModel()
        vm.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()
        vm.v2SelectPreset("corte")
        val edited = vm.state.value!!.document!!

        vm.saveTransition()
        advanceUntilIdle()

        assertEquals(listOf(edited), studio.saved)
        assertTrue(vm.state.value!!.savedSuccess)
        assertFalse(vm.state.value!!.v2Dirty)
        assertEquals("corte", vm.state.value!!.v2!!.presetCode)
    }

    @Test
    fun `preview plays the unsaved document from two bars before the window`() = runTest {
        val vm = viewModel()
        vm.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()
        vm.v2SelectLaneOption(MixEditorV2.LANE_FX, "echo-out")

        assertTrue(vm.state.value!!.v2Dirty)
        val request = vm.v2PreviewRequest()!!
        val document = vm.state.value!!.document!!
        assertEquals(document, request.document)
        assertNotEquals(studio.transition.document, request.document)
        assertTrue(studio.saved.isEmpty())
        assertEquals("a", request.fromSongId)
        val bar = 60000.0 / 126 * 4
        assertTrue(kotlin.math.abs(document.a.startMs - 2 * bar - request.startPositionMs) < 3)
    }

    // ─── Fakes ──────────────────────────────────

    private fun song(id: String, bpm: Double, durationMs: Int, firstDownbeatMs: Int): StudioSongDTO {
        val bar = 60000.0 / bpm * 4
        val downbeats = generateSequence(firstDownbeatMs.toDouble()) { it + bar }.takeWhile { it < durationMs }.map { it.toInt() }.toList()
        return StudioSongDTO(
            songId = id, durationMs = durationMs, bpm = bpm, beatsPerBar = 4, downbeats = downbeats, camelot = "8A",
            bpmPrecise = bpm, firstDownbeatMs = firstDownbeatMs, audioStartMs = 0, audioEndMs = durationMs - 1500,
            bpmStability = 0.97
        )
    }

    inner class FakeStudioManager : StudioManager(null) {
        val transition = StudioTransitionDTO(
            transitionId = "t1", revision = 1, derived = true, canEdit = true,
            document = MixDocumentDTO(1, 8000, MixSideADTO(220_000), MixSideBDTO(0)),
            songA = song("a", 126.0, 240_000, 500), songB = song("b", 128.0, 200_000, 300)
        )
        val saved = mutableListOf<MixDocumentDTO>()

        override suspend fun get(playmixId: String, transitionId: String) = transition

        override suspend fun save(playmixId: String, transitionId: String, revision: Int, document: MixDocumentDTO): StudioTransitionDTO {
            saved += document
            return transition.copy(revision = revision + 1, derived = false, document = document)
        }
    }

    class FakePresetManager : TransitionPresetManager(null) {
        override suspend fun getPresets(forceRefresh: Boolean): List<TransitionPresetDTO> = MixEditorV2.DEFAULT_PRESETS

        override suspend fun getLaneOptions(): MixLaneOptionsDTO = MixEditorV2.DEFAULT_LANE_OPTIONS.copy(
            volume = listOf(MixLaneOptionDTO("overlap", "Superposición del servidor")) + MixEditorV2.DEFAULT_LANE_OPTIONS.volume.drop(1)
        )
    }

    class FakePlaymixManager : PlaymixManager(null) {
        override suspend fun getWaveformData(playmixId: String, transitionId: String): WaveformResponseDTO =
            throw Exception("sin ondas en el test")
    }
}
