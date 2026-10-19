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
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

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
        viewModel = CrossfadeEditorViewModel(fakePlaymixManager, fakePresetManager, testDispatcher)
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

    @Test
    fun `onPresetSelected does not issue manual update after apply`() = runTest {
        fakePlaymixManager.waveformResponse = createWaveformResponse()
        fakePresetManager.applyResponse = createTransitionDTO(
            presetCode = "smooth-fade",
            presetName = "Smooth Fade"
        )

        viewModel.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()

        viewModel.onPresetSelected("smooth-fade")
        advanceUntilIdle()

        assertEquals(0, fakePlaymixManager.updateRequests.size)
        assertFalse(viewModel.state.value!!.isPresetModified)
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
    fun `saving modified preset clears presetCode and keeps manual mix mode`() = runTest {
        fakePlaymixManager.waveformResponse = createWaveformResponse(
            presetCode = "smooth-fade",
            presetName = "Smooth Fade"
        )

        viewModel.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()

        viewModel.setMixMode("club_drop")
        assertTrue(viewModel.state.value!!.isPresetModified)

        viewModel.saveTransition()
        advanceUntilIdle()

        val update = fakePlaymixManager.updateRequests.single()
        assertEquals("club_drop", update.mixMode)
        assertNull(update.presetCode)
        assertNull(viewModel.state.value!!.activePresetCode)
    }

    @Test
    fun `saving a studio transition shows the studio message and keeps the edit`() = runTest {
        fakePlaymixManager.waveformResponse = createWaveformResponse()
        fakePlaymixManager.updateError = HttpException(
            Response.error<Any>(409, """{"code":"TRANSITION_STUDIO_MANAGED"}""".toResponseBody(null))
        )
        viewModel.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()
        viewModel.setExitPoint(190000)

        viewModel.saveTransition()
        advanceUntilIdle()

        val state = viewModel.state.value!!
        assertEquals(StudioErrors.STUDIO_MANAGED_MESSAGE, state.error)
        assertFalse(state.isSaving)
        assertEquals(190000, state.exitPointMs)
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

    @Test
    fun `setCrossfadeDurationWithAutoFit moves exit earlier when measure preset needs more room`() = runTest {
        fakePlaymixManager.waveformResponse = createWaveformResponse(exitPointMs = 220000, entryPointMs = 0)

        viewModel.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()

        viewModel.setCrossfadeDurationWithAutoFit(60000)

        val state = viewModel.state.value!!
        assertEquals(180000, state.exitPointMs)
        assertEquals(0, state.entryPointMs)
        assertEquals(60000, state.crossfadeDurationMs)
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

    // ─── Blend automático / transición avanzada ─────────

    @Test
    fun `switching to smart keeps the manual points and saves mixMode smart`() = runTest {
        fakePlaymixManager.waveformResponse = createWaveformResponse(exitPointMs = 190000, crossfadeDurationMs = 12000)
        viewModel.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()

        viewModel.setSmartMode(true)
        val state = viewModel.state.value!!
        assertEquals("smart", state.mixMode)
        assertEquals(190000, state.exitPointMs)
        assertEquals(12000, state.crossfadeDurationMs)

        viewModel.saveTransition()
        advanceUntilIdle()
        assertEquals("smart", fakePlaymixManager.updateRequests.single().mixMode)
    }

    @Test
    fun `smart on an applied preset counts as a modification and drops the preset`() = runTest {
        fakePlaymixManager.waveformResponse = createWaveformResponse(presetCode = "smooth-fade", presetName = "Smooth Fade")
        viewModel.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()

        viewModel.setSmartMode(true)
        assertTrue(viewModel.state.value!!.isPresetModified)

        viewModel.saveTransition()
        advanceUntilIdle()
        val update = fakePlaymixManager.updateRequests.single()
        assertEquals("smart", update.mixMode)
        assertNull(update.presetCode)
    }

    @Test
    fun `back to advanced restores the previous manual mode`() = runTest {
        fakePlaymixManager.waveformResponse = createWaveformResponse()
        viewModel.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()
        viewModel.setMixMode("club_drop")

        viewModel.setSmartMode(true)
        viewModel.setSmartMode(false)

        assertEquals("club_drop", viewModel.state.value!!.mixMode)
    }

    @Test
    fun `a smart transition opens in advanced as crossfade`() = runTest {
        fakePlaymixManager.waveformResponse = createWaveformResponse(mixMode = "smart")
        viewModel.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()
        assertEquals("smart", viewModel.state.value!!.mixMode)

        viewModel.setSmartMode(false)

        assertEquals("crossfade", viewModel.state.value!!.mixMode)
    }

    // ─── Robustez: recarga, reintento, guardado y presets en caché ─────────

    @Test
    fun `loading the same transition again keeps the unsaved edits`() = runTest {
        fakePlaymixManager.waveformResponse = createWaveformResponse(exitPointMs = 200000)
        viewModel.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()
        viewModel.setExitPoint(150000)

        // La vista se recrea (volver a la pantalla, girar) y vuelve a pedir la carga.
        viewModel.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()

        assertEquals(150000, viewModel.state.value!!.exitPointMs)
        assertEquals(1, fakePlaymixManager.waveformCalls)
    }

    @Test
    fun `a failed load can be retried`() = runTest {
        viewModel.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()
        assertTrue(viewModel.state.value!!.loadFailed)
        assertNull(viewModel.state.value!!.waveformData)

        fakePlaymixManager.waveformResponse = createWaveformResponse()
        viewModel.retryLoad()
        advanceUntilIdle()

        assertFalse(viewModel.state.value!!.loadFailed)
        assertNotNull(viewModel.state.value!!.waveformData)
    }

    @Test
    fun `a second save while the first is running is ignored`() = runTest {
        fakePlaymixManager.waveformResponse = createWaveformResponse()
        viewModel.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()
        viewModel.setExitPoint(150000)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fakePlaymixManager.updateGate = gate

        viewModel.saveTransition()
        viewModel.saveTransition()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, fakePlaymixManager.updateRequests.size)
        assertTrue(viewModel.state.value!!.savedSuccess)
    }

    @Test
    fun `stored presets show without network and a failed refresh is not an error`() = runTest {
        fakePlaymixManager.waveformResponse = createWaveformResponse()
        fakePresetManager.stored = listOf(createPresetDTO("smooth-fade", "Smooth Fade", 1))
        fakePresetManager.shouldFailPresets = true

        viewModel.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()

        val state = viewModel.state.value!!
        assertEquals(listOf("smooth-fade"), state.availablePresets.map { it.code })
        assertNull(state.presetsError)
        assertFalse(state.presetsLoading)
    }

    @Test
    fun `fresh stored presets skip the network`() = runTest {
        fakePlaymixManager.waveformResponse = createWaveformResponse()
        fakePresetManager.stored = listOf(createPresetDTO("smooth-fade", "Smooth Fade", 1))
        fakePresetManager.fresh = true

        viewModel.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()

        assertEquals(0, fakePresetManager.getPresetsCalls)
        assertEquals(1, viewModel.state.value!!.availablePresets.size)
    }

    @Test
    fun `retrying presets forces a network refresh`() = runTest {
        fakePlaymixManager.waveformResponse = createWaveformResponse()
        fakePresetManager.stored = listOf(createPresetDTO("smooth-fade", "Smooth Fade", 1))
        fakePresetManager.fresh = true
        fakePresetManager.presets = listOf(createPresetDTO("smooth-fade", "Smooth Fade", 1), createPresetDTO("hard-cut", "Hard Cut", 2))
        viewModel.loadWaveformData("pmx1", "t1")
        advanceUntilIdle()

        viewModel.retryLoadPresets()
        advanceUntilIdle()

        assertEquals(1, fakePresetManager.getPresetsCalls)
        assertEquals(2, viewModel.state.value!!.availablePresets.size)
    }

    // ─── Helpers ────────────────────────────────

    private fun createWaveformResponse(
        presetCode: String? = null,
        presetName: String? = null,
        isPresetModified: Boolean = false,
        exitPointMs: Int = 200000,
        entryPointMs: Int = 0,
        crossfadeDurationMs: Int = 8000,
        mixMode: String = "crossfade"
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
            isPresetModified = isPresetModified,
            mixMode = mixMode
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

    class FakePlaymixManager : PlaymixManager(null) {
        var waveformResponse: WaveformResponseDTO? = null
        val updateRequests = mutableListOf<PlaymixTransitionUpdateDTO>()
        var updateError: Throwable? = null
        var waveformCalls = 0
        /** Si no es null, el guardado espera a que se complete (para probar toques repetidos). */
        var updateGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        override suspend fun getWaveformData(playmixId: String, transitionId: String): WaveformResponseDTO {
            waveformCalls++
            return waveformResponse ?: throw Exception("No waveform response configured")
        }

        override suspend fun updateTransition(
            playmixId: String, transitionId: String, update: PlaymixTransitionUpdateDTO
        ): PlaymixTransitionDTO {
            updateRequests.add(update)
            updateGate?.await()
            updateError?.let { throw it }
            return PlaymixTransitionDTO(
                id = transitionId, fromPlaymixSongId = "ps1", toPlaymixSongId = "ps2",
                exitPointMs = update.exitPointMs, entryPointMs = update.entryPointMs,
                crossfadeDurationMs = update.crossfadeDurationMs, fadeCurveType = update.fadeCurveType,
                eqSettings = update.eqSettings,
                eqSettingsA = update.eqSettingsA,
                eqSettingsB = update.eqSettingsB,
                mixMode = update.mixMode,
                compatibility = null,
                bandFadeTypes = update.bandFadeTypes,
                presetCode = update.presetCode,
                isPresetModified = update.isPresetModified,
                gapMs = update.gapMs
            )
        }
    }

    class FakeTransitionPresetManager : TransitionPresetManager(null) {
        var presets: List<TransitionPresetDTO> = emptyList()
        var shouldFailPresets = false
        var previewResponse: TransitionPresetPreviewDTO? = null
        var shouldFailPreview = false
        var applyResponse: PlaymixTransitionDTO? = null
        var resetResponse: PlaymixTransitionDTO? = null
        /** Copia guardada en el dispositivo (null = nunca se descargaron). */
        var stored: List<TransitionPresetDTO>? = null
        var fresh = false
        var getPresetsCalls = 0

        override fun peekCachedPresets(): List<TransitionPresetDTO>? = stored

        override fun hasFreshPresets(): Boolean = fresh

        override suspend fun getPresets(forceRefresh: Boolean): List<TransitionPresetDTO> {
            getPresetsCalls++
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
