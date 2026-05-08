package com.example.resonant.ui.viewmodels

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.resonant.data.network.*
import com.example.resonant.managers.PlaymixManager
import com.example.resonant.managers.TransitionPresetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class CrossfadeEditorViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var viewModel: CrossfadeEditorViewModel
    private lateinit var fakePlaymixManager: FakePlaymixManager
    private lateinit var fakePresetManager: FakeTransitionPresetManager

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakePlaymixManager = FakePlaymixManager()
        fakePresetManager = FakeTransitionPresetManager()
        viewModel = CrossfadeEditorViewModel(fakePlaymixManager, fakePresetManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─── Preset Loading ─────────────────────────

    @Test
    fun `loadWaveformData sets preset fields from transition`() = runTest {
        fakePlaymixManager.waveformResponse = createWaveformResponse(
            presetCode = "smooth-fade",
            presetName = "Smooth Fade",
            isPresetModified = false
        )
        fakePresetManager.presets = listOf(
            createPresetDTO("smooth-fade", "Smooth Fade", 1)
        )

        viewModel.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()

        val state = viewModel.state.value!!
        assertFalse(state.isLoading)
        assertEquals("smooth-fade", state.activePresetCode)
        assertEquals("Smooth Fade", state.activePresetName)
        assertFalse(state.isPresetModified)
    }

    @Test
    fun `loadWaveformData loads available presets`() = runTest {
        fakePlaymixManager.waveformResponse = createWaveformResponse()
        fakePresetManager.presets = listOf(
            createPresetDTO("smooth-fade", "Smooth Fade", 1),
            createPresetDTO("beat-match", "Beat Match", 2),
            createPresetDTO("hard-cut", "Hard Cut", 3)
        )

        viewModel.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()

        val state = viewModel.state.value!!
        assertEquals(3, state.availablePresets.size)
        assertFalse(state.presetsLoading)
        assertNull(state.presetsError)
    }

    @Test
    fun `preset loading error sets presetsError`() = runTest {
        fakePlaymixManager.waveformResponse = createWaveformResponse()
        fakePresetManager.shouldFailPresets = true

        viewModel.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()

        val state = viewModel.state.value!!
        assertNotNull(state.presetsError)
        assertFalse(state.presetsLoading)
    }

    // ─── Preset Selection & Preview ─────────────

    @Test
    fun `onPresetSelected applies preset directly`() = runTest {
        fakePlaymixManager.waveformResponse = createWaveformResponse()
        fakePresetManager.presets = listOf(createPresetDTO("smooth-fade", "Smooth Fade", 1))
        fakePresetManager.applyResponse = createTransitionDTO(
            presetCode = "smooth-fade",
            presetName = "Smooth Fade",
            exitPointMs = 180000,
            entryPointMs = 500,
            crossfadeDurationMs = 5200
        )

        viewModel.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()

        viewModel.onPresetSelected("smooth-fade")
        advanceUntilIdle()

        val state = viewModel.state.value!!
        assertEquals("smooth-fade", state.activePresetCode)
    }

    // ─── Apply Preset ───────────────────────────

    @Test
    fun `onApplyPreset updates state with returned transition`() = runTest {
        fakePlaymixManager.waveformResponse = createWaveformResponse()
        fakePresetManager.applyResponse = createTransitionDTO(
            presetCode = "beat-match",
            presetName = "Beat Match",
            exitPointMs = 180000,
            entryPointMs = 500,
            crossfadeDurationMs = 5200
        )

        viewModel.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()

        viewModel.onApplyPreset("beat-match")
        advanceUntilIdle()

        val state = viewModel.state.value!!
        assertEquals("beat-match", state.activePresetCode)
        assertEquals("Beat Match", state.activePresetName)
        assertEquals(180000, state.exitPointMs)
        assertEquals(500, state.entryPointMs)
        assertEquals(5200, state.crossfadeDurationMs)
        assertFalse(state.isPresetModified)
    }

    // ─── isPresetModified Detection ─────────────

    @Test
    fun `editing values after preset apply sets isPresetModified`() = runTest {
        fakePlaymixManager.waveformResponse = createWaveformResponse()
        fakePresetManager.applyResponse = createTransitionDTO(
            presetCode = "smooth-fade",
            presetName = "Smooth Fade",
            exitPointMs = 200000,
            entryPointMs = 0,
            crossfadeDurationMs = 8000
        )

        viewModel.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()
        viewModel.onApplyPreset("smooth-fade")
        advanceUntilIdle()

        assertFalse(viewModel.state.value!!.isPresetModified)

        // Manual edit
        viewModel.setExitPoint(190000)

        assertTrue(viewModel.state.value!!.isPresetModified)
    }

    @Test
    fun `switching to Manual clears activePresetCode`() = runTest {
        fakePlaymixManager.waveformResponse = createWaveformResponse(presetCode = "smooth-fade")
        fakePresetManager.presets = listOf(createPresetDTO("smooth-fade", "Smooth Fade", 1))

        viewModel.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()

        assertEquals("smooth-fade", viewModel.state.value!!.activePresetCode)

        viewModel.onManualSelected()

        assertNull(viewModel.state.value!!.activePresetCode)
        assertFalse(viewModel.state.value!!.isPresetModified)
    }

    // ─── Reset Preset ───────────────────────────

    @Test
    fun `onResetPreset reloads transition from backend`() = runTest {
        fakePlaymixManager.waveformResponse = createWaveformResponse(
            presetCode = "smooth-fade",
            exitPointMs = 200000
        )
        fakePresetManager.resetResponse = createTransitionDTO(
            presetCode = "smooth-fade",
            presetName = "Smooth Fade",
            exitPointMs = 200000,
            entryPointMs = 0,
            crossfadeDurationMs = 8000
        )

        viewModel.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()

        // Modify manually
        viewModel.setExitPoint(150000)
        assertTrue(viewModel.state.value!!.isPresetModified)

        viewModel.onResetPreset()
        advanceUntilIdle()

        val state = viewModel.state.value!!
        assertEquals(200000, state.exitPointMs)
        assertFalse(state.isPresetModified)
    }

    // ─── Gap Slider ─────────────────────────────

    @Test
    fun `setGapMs updates state and checks preset modified`() {
        viewModel.setGapMs(500)
        assertEquals(500, viewModel.state.value!!.gapMs)
    }

    @Test
    fun `toggleGapSlider resets gap when hidden`() {
        viewModel.setGapMs(1000)
        viewModel.toggleGapSlider(false)
        assertEquals(0, viewModel.state.value!!.gapMs)
        assertFalse(viewModel.state.value!!.showGapSlider)
    }

    // ─── Helpers ────────────────────────────────

    private fun createWaveformResponse(
        presetCode: String? = null,
        presetName: String? = null,
        isPresetModified: Boolean = false,
        exitPointMs: Int = 200000,
        entryPointMs: Int = 0,
        crossfadeDurationMs: Int = 8000
    ): WaveformResponseDTO {
        val transition = PlaymixTransitionDTO(
            id = "t1",
            fromPlaymixSongId = "ps1",
            toPlaymixSongId = "ps2",
            exitPointMs = exitPointMs,
            entryPointMs = entryPointMs,
            crossfadeDurationMs = crossfadeDurationMs,
            fadeCurveType = "linear",
            eqSettings = null,
            compatibility = createCompatibility(),
            presetCode = presetCode,
            presetName = presetName,
            isPresetModified = isPresetModified
        )
        return WaveformResponseDTO(
            transition = transition,
            songA = WaveformSongDTO("s1", "Song A", "Artist A", 240000, 128.0, "C", -14.0, null, null),
            songB = WaveformSongDTO("s2", "Song B", "Artist B", 210000, 130.0, "Am", -12.0, null, null),
            compatibility = createCompatibility()
        )
    }

    private fun createCompatibility() = CompatibilityDTO(
        bpmDelta = 2.0, bpmDeltaPercent = 1.5, keyCompatibility = "compatible",
        keyRelationship = "relative_minor", loudnessDeltaLufs = 2.0,
        overallScore = 85, verdict = "good", suggestions = emptyList()
    )

    private fun createPresetDTO(code: String, name: String, order: Int) = TransitionPresetDTO(
        id = "preset_$code", code = code, name = name, description = "Description for $name",
        iconName = "wave", category = "standard", isSystem = true, sortOrder = order
    )

    private fun createPreviewDTO(code: String, name: String) = TransitionPresetPreviewDTO(
        presetCode = code, presetName = name,
        calculatedValues = CalculatedTransitionValues(
            exitPointMs = 180000, entryPointMs = 500, crossfadeDurationMs = 5200,
            fadeCurveType = "exponential", eqSettingsJson = null, gapMs = 0
        ),
        metadata = TransitionMetadata(128.0, 130.0, 2.0, "compatible", 2.0),
        warnings = emptyList(), appliedFallbacks = emptyList()
    )

    private fun createTransitionDTO(
        presetCode: String? = null,
        presetName: String? = null,
        exitPointMs: Int = 200000,
        entryPointMs: Int = 0,
        crossfadeDurationMs: Int = 8000
    ) = PlaymixTransitionDTO(
        id = "t1", fromPlaymixSongId = "ps1", toPlaymixSongId = "ps2",
        exitPointMs = exitPointMs, entryPointMs = entryPointMs,
        crossfadeDurationMs = crossfadeDurationMs, fadeCurveType = "linear",
        eqSettings = null, compatibility = null,
        presetCode = presetCode, presetName = presetName, isPresetModified = false
    )

    // ─── Fake Managers ──────────────────────────

    class FakePlaymixManager : PlaymixManager(null!!) {
        var waveformResponse: WaveformResponseDTO? = null

        override suspend fun getWaveformData(playmixId: String, transitionId: String): WaveformResponseDTO {
            return waveformResponse ?: throw Exception("No waveform response configured")
        }

        override suspend fun updateTransition(
            playmixId: String, transitionId: String, update: PlaymixTransitionUpdateDTO
        ): PlaymixTransitionDTO {
            return PlaymixTransitionDTO(
                id = transitionId, fromPlaymixSongId = "ps1", toPlaymixSongId = "ps2",
                exitPointMs = update.exitPointMs, entryPointMs = update.entryPointMs,
                crossfadeDurationMs = update.crossfadeDurationMs, fadeCurveType = update.fadeCurveType,
                eqSettings = update.eqSettings, compatibility = null,
                presetCode = update.presetCode, isPresetModified = update.isPresetModified
            )
        }
    }

    class FakeTransitionPresetManager : TransitionPresetManager(null!!) {
        var presets: List<TransitionPresetDTO> = emptyList()
        var shouldFailPresets = false
        var previewResponse: TransitionPresetPreviewDTO? = null
        var shouldFailPreview = false
        var applyResponse: PlaymixTransitionDTO? = null
        var resetResponse: PlaymixTransitionDTO? = null

        override suspend fun getPresets(forceRefresh: Boolean): List<TransitionPresetDTO> {
            if (shouldFailPresets) throw Exception("Network error")
            return presets
        }

        override suspend fun previewPreset(
            playmixId: String, transitionId: String, presetCode: String
        ): TransitionPresetPreviewDTO {
            if (shouldFailPreview) throw Exception("Preview error")
            return previewResponse ?: throw Exception("No preview configured")
        }

        override suspend fun applyPreset(
            playmixId: String, transitionId: String, presetCode: String
        ): PlaymixTransitionDTO {
            return applyResponse ?: throw Exception("No apply response configured")
        }

        override suspend fun resetPreset(playmixId: String, transitionId: String): PlaymixTransitionDTO {
            return resetResponse ?: throw Exception("No reset response configured")
        }
    }
}
