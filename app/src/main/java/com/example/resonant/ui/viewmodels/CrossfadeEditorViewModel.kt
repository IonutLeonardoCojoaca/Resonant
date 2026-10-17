package com.example.resonant.ui.viewmodels

import android.util.Log
import com.example.resonant.playback.PlaymixMixMode
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.network.MixDocumentDTO
import com.example.resonant.data.network.MixPresetRefDTO
import com.example.resonant.data.network.MixSideADTO
import com.example.resonant.data.network.MixSideBDTO
import com.example.resonant.data.network.StudioErrors
import com.example.resonant.data.network.StudioTransitionDTO
import com.example.resonant.managers.StudioManager
import retrofit2.HttpException
import com.example.resonant.data.network.BandFadeTypesDTO
import com.example.resonant.data.network.EqBandDTO
import com.example.resonant.data.network.EqSettingsDTO
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.data.network.PlaymixTransitionUpdateDTO
import com.example.resonant.data.network.TransitionPresetDTO
import com.example.resonant.data.network.WaveformResponseDTO
import com.example.resonant.data.network.WaveformSongDTO
import com.example.resonant.playback.BarGrid
import com.example.resonant.playback.WaveformBands
import com.example.resonant.playback.PhaseAlignment
import com.example.resonant.managers.PlaymixManager
import com.example.resonant.managers.TransitionPresetManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt


data class CrossfadeEditorState(
    val isLoading: Boolean = true,
    val waveformData: WaveformResponseDTO? = null,
    val exitPointMs: Int = 0,
    val entryPointMs: Int = 0,
    val crossfadeDurationMs: Int = 8000,
    val fadeCurveType: String = "linear",
    val eqSettings: EqSettingsDTO = defaultEq(),
    val eqSettingsA: EqSettingsDTO = defaultEq(),
    val eqSettingsB: EqSettingsDTO = defaultEq(),
    val mixMode: String = "crossfade",
    val isSaving: Boolean = false,
    val error: String? = null,
    val savedSuccess: Boolean = false,
    // BPM matching
    val bpmA: Double = 0.0,
    val bpmB: Double = 0.0,
    // ─── Preset fields ─────────────────────────
    val availablePresets: List<TransitionPresetDTO> = emptyList(),
    val presetsLoading: Boolean = false,
    val presetsError: String? = null,
    val activePresetCode: String? = null,
    val activePresetName: String? = null,
    val isPresetModified: Boolean = false,
    val gapMs: Int = 0,
    val showGapSlider: Boolean = false,
    val bandFadeTypes: BandFadeTypesDTO = BandFadeTypesDTO(),
    // ─── Pre-listen state ─────────────────────────
    val prelistenTarget: String? = null, // "songA" or "songB" or null
    // ─── Resonant Studio: documento de mezcla (add-rhythmic-mix-styles D5) ─────
    /** Transición en Studio (documento guardado o derivado, revisión, datos de las canciones). null si Studio no respondió. */
    val studio: StudioTransitionDTO? = null,
    /** Documento que se edita: estilo + compases + puntos. Es lo que se dibuja y lo que se guarda. */
    val document: MixDocumentDTO? = null,
    val revision: Int = 0,
    /** Estilo (código del preset) del documento; null si es personalizado. */
    val styleCode: String? = null,
    /** Compases elegidos (2, 4, 8, 16); null = los nativos del estilo. */
    val bars: Int? = null,
    val styleLoading: Boolean = false,
    /** Studio apagado o inaccesible: el editor solo puede guardar puntos y el blend automático. */
    val studioUnavailable: Boolean = false,
    /** Editor v2 (carriles, ventana y presets estilo Spotify); null con el motor v1 o sin documento v2 posible. */
    val v2: MixEditorV2State? = null,
    /** Documento v2 guardado en el servidor (null si aún no hay): distinto del de [v2] = cambios sin guardar. */
    val v2Saved: MixDocumentDTO? = null
) {
    val v2Dirty: Boolean get() = v2 != null && v2.document != v2Saved
    /** Estado del clip renderizado del servidor (pending, rendering, ready, failed…). */
    val renderStatus: String? get() = studio?.render?.status
}

fun defaultEq() = EqSettingsDTO(
    bands = listOf(60, 250, 1000, 4000, 12000).map { EqBandDTO(it, 0.0) }
)

class CrossfadeEditorViewModel(
    private val playmixManager: PlaymixManager,
    private val presetManager: TransitionPresetManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Sin gestor de Studio (tests, Studio apagado) el editor queda en modo reducido: puntos y blend automático. */
    private val studioManager: StudioManager? = null,
    /** Motor de salida único activo: el editor trabaja con documentos v2 (spotify-style-transition-engine D9). */
    private val v2Enabled: Boolean = false,
    private val bandsLoader: suspend (String) -> WaveformBands? = { WaveformBands.download(it) }
) : ViewModel() {

    private val _state = MutableLiveData(CrossfadeEditorState())
    val state: LiveData<CrossfadeEditorState> get() = _state

    private var playmixId: String = ""
    private var transitionId: String = ""

    // Snapshot of values right after applying a preset, used to detect manual edits
    private var presetSnapshot: PresetValuesSnapshot? = null

    private data class PresetValuesSnapshot(
        val exitPointMs: Int,
        val entryPointMs: Int,
        val crossfadeDurationMs: Int,
        val gapMs: Int,
        val mixMode: String,
        val fadeCurveType: String,
        val bandFadeTypes: BandFadeTypesDTO
    )

    /** Mensaje de error de una edición; las transiciones de Resonant Studio no se editan aquí. */
    private fun editError(error: Throwable, fallback: String): String =
        if (StudioErrors.isStudioManaged(error)) StudioErrors.STUDIO_MANAGED_MESSAGE else fallback

    private fun logError(message: String, throwable: Throwable) {
        runCatching { Log.e("CrossfadeEditorVM", message, throwable) }
    }

    private fun coerceCrossfadeDuration(ms: Int, state: CrossfadeEditorState): Int {
        if (ms <= 0) return 0
        val data = state.waveformData ?: return ms.coerceAtLeast(0)
        val remainingA = (data.songA.durationMs - state.exitPointMs).coerceAtLeast(0)
        val remainingB = (data.songB.durationMs - state.entryPointMs).coerceAtLeast(0)
        val maxSafeDuration = minOf(remainingA, remainingB)
        return if (maxSafeDuration > 0) ms.coerceIn(0, maxSafeDuration) else 0
    }

    private fun fitTransitionToDuration(ms: Int, state: CrossfadeEditorState): CrossfadeEditorState {
        val data = state.waveformData ?: return state.copy(
            crossfadeDurationMs = ms.coerceAtLeast(0),
            showGapSlider = ms <= 0 || state.gapMs > 0
        )

        val durationA = data.songA.durationMs.coerceAtLeast(0)
        val durationB = data.songB.durationMs.coerceAtLeast(0)
        val targetDuration = ms.coerceIn(0, minOf(durationA, durationB))
        if (targetDuration == 0) {
            return state.copy(crossfadeDurationMs = 0, showGapSlider = true)
        }

        var adjustedExit = state.exitPointMs.coerceIn(0, durationA)
        var adjustedEntry = state.entryPointMs.coerceIn(0, durationB)

        if (durationA - adjustedExit < targetDuration) {
            adjustedExit = (durationA - targetDuration).coerceAtLeast(0)
        }
        if (durationB - adjustedEntry < targetDuration) {
            adjustedEntry = (durationB - targetDuration).coerceAtLeast(0)
        }

        return state.copy(
            exitPointMs = adjustedExit,
            entryPointMs = adjustedEntry,
            crossfadeDurationMs = targetDuration,
            showGapSlider = state.gapMs > 0
        )
    }

    fun loadWaveformData(playmixId: String, transitionId: String) {
        this.playmixId = playmixId
        this.transitionId = transitionId

        _state.value = _state.value?.copy(isLoading = true, error = null)

        // Load presets in parallel
        loadPresets()
        loadStudio()

        viewModelScope.launch {
            try {
                val data = withContext(ioDispatcher) {
                    playmixManager.getWaveformData(playmixId, transitionId)
                }
                val transition = data.transition
                val bA = data.songA.bpm ?: 0.0
                val bB = data.songB.bpm ?: 0.0

                // Si Studio ya respondió, los puntos y la duración son los del documento, no los campos antiguos.
                val doc = _state.value?.document
                _state.value = _state.value?.copy(
                    isLoading = false,
                    waveformData = data,
                    exitPointMs = doc?.a?.startMs ?: transition.exitPointMs,
                    entryPointMs = doc?.b?.startMs ?: transition.entryPointMs,
                    crossfadeDurationMs = doc?.durationMs ?: transition.crossfadeDurationMs,
                    fadeCurveType = transition.fadeCurveType,
                    eqSettings = transition.eqSettings ?: defaultEq(),
                    eqSettingsA = transition.eqSettingsA ?: (transition.eqSettings ?: defaultEq()),
                    eqSettingsB = transition.eqSettingsB ?: (transition.eqSettings ?: defaultEq()),
                    mixMode = transition.mixMode ?: "crossfade",
                    error = null,
                    bpmA = bA,
                    bpmB = bB,
                    // Preset fields from backend
                    activePresetCode = transition.presetCode,
                    activePresetName = transition.presetName,
                    isPresetModified = transition.isPresetModified,
                    gapMs = transition.gapMs,
                    showGapSlider = transition.gapMs > 0 || transition.crossfadeDurationMs == 0,
                    bandFadeTypes = transition.bandFadeTypes ?: BandFadeTypesDTO()
                )

                // If preset was applied, save snapshot
                if (transition.presetCode != null && !transition.isPresetModified) {
                    presetSnapshot = PresetValuesSnapshot(
                        exitPointMs = transition.exitPointMs,
                        entryPointMs = transition.entryPointMs,
                        crossfadeDurationMs = transition.crossfadeDurationMs,
                        gapMs = transition.gapMs,
                        mixMode = transition.mixMode ?: "crossfade",
                        fadeCurveType = transition.fadeCurveType,
                        bandFadeTypes = transition.bandFadeTypes ?: BandFadeTypesDTO()
                    )
                }
            } catch (e: Exception) {
                logError("Error loading waveform data", e)
                _state.value = _state.value?.copy(isLoading = false, error = "Error al cargar datos: ${e.message}")
            }
        }
    }

    // ─── Resonant Studio: documento, estilo y compases ─────────────────────────

    /** Documento guardado (o derivado de los campos antiguos) y revisión. Sin Studio, el editor queda en modo reducido. */
    private fun loadStudio() {
        val manager = studioManager ?: run {
            _state.value = _state.value?.copy(studioUnavailable = true)
            return
        }
        viewModelScope.launch {
            try {
                val studio = withContext(ioDispatcher) { manager.get(playmixId, transitionId) }
                applyStudio(studio, keepSmart = true)
            } catch (e: Exception) {
                logError("Studio no disponible para la transición", e)
                _state.value = _state.value?.copy(studioUnavailable = true)
            }
        }
    }

    private fun applyStudio(studio: StudioTransitionDTO, keepSmart: Boolean) {
        val current = _state.value ?: return
        val v2 = if (v2Enabled) initialV2(studio, current.v2) else null
        val doc = v2?.document ?: studio.document
        _state.value = current.copy(
            v2 = v2,
            v2Saved = studio.document.takeIf { it.isV2 },
            studio = studio,
            document = doc,
            revision = studio.revision,
            styleCode = doc.preset?.code,
            studioUnavailable = false,
            exitPointMs = doc.a.startMs,
            entryPointMs = doc.b.startMs,
            crossfadeDurationMs = doc.durationMs,
            mixMode = if (v2 != null) PlaymixMixMode.DEFAULT_ADVANCED
                else if (keepSmart && current.mixMode == PlaymixMixMode.SMART) current.mixMode
                else if (current.mixMode == PlaymixMixMode.SMART) PlaymixMixMode.DEFAULT_ADVANCED else current.mixMode
        )
        if (v2 != null && current.v2 == null) loadV2Extras(studio)
        if (v2 != null && studio.render?.status in RENDERING) pollRender()
    }

    private var renderPoll: Job? = null

    /** Tras guardar, el servidor renderiza el clip en segundos: se consulta hasta que termina para enseñarlo. */
    private fun pollRender() {
        val manager = studioManager ?: return
        renderPoll?.cancel()
        renderPoll = viewModelScope.launch {
            repeat(RENDER_POLLS) {
                delay(RENDER_POLL_MS)
                val fresh = runCatching { withContext(ioDispatcher) { manager.get(playmixId, transitionId) } }.getOrNull()
                    ?: return@repeat
                val current = _state.value ?: return@launch
                val studio = current.studio ?: return@launch
                if (fresh.revision != studio.revision) return@launch
                _state.value = current.copy(studio = studio.copy(render = fresh.render))
                if (fresh.render?.status !in RENDERING) return@launch
            }
        }
    }

    // ─── Editor v2 ─────────────────────────

    /** Estado v2 del documento recibido; conserva presets, opciones y ondas ya cargados. */
    private fun initialV2(studio: StudioTransitionDTO, previous: MixEditorV2State?): MixEditorV2State? {
        val presets = MixEditorV2.presetsOrDefault(_state.value?.availablePresets.orEmpty())
        val state = MixEditorV2.initial(studio)?.copy(presets = presets) ?: return null
        return if (previous == null) state
            else state.copy(laneOptions = previous.laneOptions, bandsA = previous.bandsA, bandsB = previous.bandsB)
    }

    /** Opciones de carril y ondas de 3 bandas: si fallan, el editor sigue con el catálogo local y la onda simple. */
    private fun loadV2Extras(studio: StudioTransitionDTO) {
        viewModelScope.launch {
            val options = runCatching { withContext(ioDispatcher) { presetManager.getLaneOptions() } }
                .onFailure { logError("Opciones de carril no disponibles", it) }
                .getOrNull()
            if (options != null && options.volume.isNotEmpty()) updateV2 { it.copy(laneOptions = options) }
        }
        viewModelScope.launch {
            val a = studio.songA.waveformBandsUrl?.let { url -> runCatching { withContext(ioDispatcher) { bandsLoader(url) } }.getOrNull() }
            val b = studio.songB.waveformBandsUrl?.let { url -> runCatching { withContext(ioDispatcher) { bandsLoader(url) } }.getOrNull() }
            updateV2 { it.copy(bandsA = a ?: it.bandsA, bandsB = b ?: it.bandsB) }
        }
    }

    private fun updateV2(change: (MixEditorV2State) -> MixEditorV2State) {
        val current = _state.value ?: return
        val v2 = current.v2 ?: return
        val next = change(v2)
        if (next == v2) return
        val doc = next.document
        _state.value = current.copy(
            v2 = next,
            document = doc,
            styleCode = next.presetCode,
            bars = next.window.bars,
            exitPointMs = doc.a.startMs,
            entryPointMs = doc.b.startMs,
            crossfadeDurationMs = doc.durationMs,
            error = null
        )
    }

    fun v2SelectPreset(code: String) = updateV2 { MixEditorV2.selectPreset(it, code) }

    fun v2SelectLaneOption(lane: String, code: String) = updateV2 { MixEditorV2.selectLaneOption(it, lane, code) }

    fun v2SetBars(bars: Int) = updateV2 { MixEditorV2.setBars(it, bars) }

    fun v2MoveWindow(deltaBars: Int) = updateV2 { MixEditorV2.moveWindow(it, deltaBars) }

    fun v2DragA(aStartMs: Int, toBeat: Boolean = false) = updateV2 { MixEditorV2.dragA(it, aStartMs, toBeat) }

    fun v2DragB(bStartMs: Int, toBeat: Boolean = false) = updateV2 { MixEditorV2.dragB(it, bStartMs, toBeat) }

    fun v2SetBeatSync(enabled: Boolean) = updateV2 { MixEditorV2.setBeatSync(it, enabled) }

    /** Escucha del documento tal como está en el editor (sin guardar); null sin editor v2. */
    fun v2PreviewRequest(): MixPreviewRequest? = _state.value?.v2?.let { MixEditorV2.preview(it) }

    /** Pide al servidor el documento del estilo con los compases elegidos y lo deja como documento actual. */
    fun selectStyle(code: String) {
        val manager = studioManager ?: return
        val current = _state.value ?: return
        _state.value = current.copy(styleLoading = true, error = null)
        viewModelScope.launch {
            try {
                val response = withContext(ioDispatcher) { manager.preset(playmixId, transitionId, code, current.bars) }
                val doc = response.document.copy(preset = response.document.preset ?: MixPresetRefDTO(code))
                val now = _state.value ?: return@launch
                _state.value = now.copy(
                    document = doc,
                    styleCode = code,
                    styleLoading = false,
                    exitPointMs = doc.a.startMs,
                    entryPointMs = doc.b.startMs,
                    crossfadeDurationMs = doc.durationMs,
                    mixMode = if (now.mixMode == PlaymixMixMode.SMART) PlaymixMixMode.DEFAULT_ADVANCED else now.mixMode,
                    activePresetCode = null, activePresetName = null, isPresetModified = false
                )
            } catch (e: Exception) {
                logError("Error pidiendo el estilo $code", e)
                _state.value = _state.value?.copy(
                    styleLoading = false,
                    error = if (e is HttpException && e.code() == 422) "Falta el análisis de audio de una canción para este estilo."
                            else "No se pudo cargar el estilo."
                )
            }
        }
    }

    /** Compases del cruce (2, 4, 8 o 16). Con un estilo elegido vuelve a pedirlo con la duración nueva. */
    fun selectBars(bars: Int) {
        val current = _state.value ?: return
        _state.value = current.copy(bars = bars)
        current.styleCode?.let { selectStyle(it) }
    }

    /** Vuelve a cargar la revisión actual tras un conflicto o un cambio externo. */
    fun reloadStudio() = loadStudio()

    // ─── Preset Loading ─────────────────────────

    private fun loadPresets() {
        _state.value = _state.value?.copy(presetsLoading = true, presetsError = null)
        viewModelScope.launch {
            try {
                val presets = withContext(ioDispatcher) {
                    presetManager.getPresets()
                }
                _state.value = _state.value?.copy(
                    availablePresets = presets,
                    presetsLoading = false,
                    presetsError = null
                )
                updateV2 { it.copy(presets = MixEditorV2.presetsOrDefault(presets)) }
            } catch (e: Exception) {
                logError("Error loading presets", e)
                _state.value = _state.value?.copy(
                    presetsLoading = false,
                    presetsError = "No se pudieron cargar los presets"
                )
            }
        }
    }

    fun retryLoadPresets() {
        loadPresets()
    }

    // ─── Preset Selection & Apply ─────────────

    fun onPresetSelected(presetCode: String) {
        // Skip preview – apply and persist immediately
        _state.value = _state.value?.copy(presetsLoading = true)
        viewModelScope.launch {
            try {
                val updated = withContext(ioDispatcher) {
                    presetManager.applyPreset(playmixId, transitionId, presetCode)
                }
                applyTransitionToState(updated)
                _state.value = _state.value?.copy(
                    presetsLoading = false,
                    savedSuccess = true,
                    error = null
                )
            } catch (e: Exception) {
                logError("Error applying preset", e)
                _state.value = _state.value?.copy(
                    presetsLoading = false,
                    error = editError(e, "Error al aplicar el preset.")
                )
            }
        }
    }

    fun onDismissPreview() { /* no-op – preview step removed */ }

    fun onApplyPreset(presetCode: String) = onPresetSelected(presetCode)

    fun onResetPreset() {
        val current = _state.value ?: return
        if (current.activePresetCode == null) return

        _state.value = current.copy(presetsLoading = true)
        viewModelScope.launch {
            try {
                val updated = withContext(ioDispatcher) {
                    presetManager.resetPreset(playmixId, transitionId)
                }
                applyTransitionToState(updated)
                _state.value = _state.value?.copy(
                    presetsLoading = false,
                    savedSuccess = true,
                    error = null
                )
            } catch (e: Exception) {
                logError("Error resetting preset", e)
                _state.value = _state.value?.copy(
                    presetsLoading = false,
                    error = editError(e, "Error al resetear el preset.")
                )
            }
        }
    }

    fun onManualSelected() {
        _state.value = _state.value?.copy(
            activePresetCode = null,
            activePresetName = null,
            isPresetModified = false
        )
        presetSnapshot = null
    }

    private fun applyTransitionToState(transition: PlaymixTransitionDTO) {
        presetSnapshot = if (transition.presetCode != null) {
            PresetValuesSnapshot(
                exitPointMs = transition.exitPointMs,
                entryPointMs = transition.entryPointMs,
                crossfadeDurationMs = transition.crossfadeDurationMs,
                gapMs = transition.gapMs,
                mixMode = transition.mixMode ?: "crossfade",
                fadeCurveType = transition.fadeCurveType,
                bandFadeTypes = transition.bandFadeTypes ?: BandFadeTypesDTO()
            )
        } else null

        _state.value = _state.value?.copy(
            exitPointMs = transition.exitPointMs,
            entryPointMs = transition.entryPointMs,
            crossfadeDurationMs = transition.crossfadeDurationMs,
            fadeCurveType = transition.fadeCurveType,
            eqSettings = transition.eqSettings ?: defaultEq(),
            eqSettingsA = transition.eqSettingsA ?: (transition.eqSettings ?: defaultEq()),
            eqSettingsB = transition.eqSettingsB ?: (transition.eqSettings ?: defaultEq()),
            mixMode = transition.mixMode ?: "crossfade",
            activePresetCode = transition.presetCode,
            activePresetName = transition.presetName,
            isPresetModified = transition.isPresetModified,
            gapMs = transition.gapMs,
            showGapSlider = transition.gapMs > 0 || transition.crossfadeDurationMs == 0,
            bandFadeTypes = transition.bandFadeTypes ?: BandFadeTypesDTO(),
            isSaving = false
        )
    }

    private fun checkPresetModified() {
        val current = _state.value ?: return
        val snapshot = presetSnapshot ?: return
        if (current.activePresetCode == null) return

        val modified = current.exitPointMs != snapshot.exitPointMs ||
                current.entryPointMs != snapshot.entryPointMs ||
                current.crossfadeDurationMs != snapshot.crossfadeDurationMs ||
                current.gapMs != snapshot.gapMs ||
                current.mixMode != snapshot.mixMode ||
                current.fadeCurveType != snapshot.fadeCurveType ||
                current.bandFadeTypes != snapshot.bandFadeTypes
        if (modified != current.isPresetModified) {
            _state.value = current.copy(isPresetModified = modified)
        }
    }

    fun setExitPoint(ms: Int) {
        val current = _state.value ?: return
        current.document?.let { doc ->
            // Con documento, la salida es `a.startMs`; la duración es la del documento.
            // A tiene que sonar entera durante el cruce: la salida no pasa de (duración de A − cruce).
            val maxStart = ((current.waveformData?.songA?.durationMs ?: Int.MAX_VALUE) - doc.durationMs).coerceAtLeast(0)
            val start = keepOnBar(ms, maxStart, current.waveformData?.songA)
            _state.value = current.copy(exitPointMs = start, document = doc.copy(a = MixSideADTO(start)))
            return
        }
        val moved = current.copy(exitPointMs = ms.coerceAtLeast(0))
        _state.value = moved.copy(
            crossfadeDurationMs = coerceCrossfadeDuration(moved.crossfadeDurationMs, moved)
        )
        checkPresetModified()
    }

    fun setEntryPoint(ms: Int) {
        val current = _state.value ?: return
        current.document?.let { doc ->
            val maxStart = ((current.waveformData?.songB?.durationMs ?: Int.MAX_VALUE) - (doc.durationMs - doc.b.offsetMs)).coerceAtLeast(0)
            val start = keepOnBar(ms, maxStart, current.waveformData?.songB)
            _state.value = current.copy(entryPointMs = start, document = doc.copy(b = MixSideBDTO(start, doc.b.offsetMs)))
            return
        }
        val moved = current.copy(entryPointMs = ms.coerceAtLeast(0))
        _state.value = moved.copy(
            crossfadeDurationMs = coerceCrossfadeDuration(moved.crossfadeDurationMs, moved)
        )
        checkPresetModified()
    }

    /**
     * [ms] dentro de [0, maxStart]. El editor ya entrega puntos en compás; si hay que recortarlos (no cabe el cruce),
     * se lleva al compás más cercano que quepa, para que salida y entrada sigan en downbeat (A y B en fase).
     */
    private fun keepOnBar(ms: Int, maxStart: Int, song: WaveformSongDTO?): Int {
        val clamped = ms.coerceIn(0, maxStart)
        if (clamped == ms || song == null) return clamped
        val exact = PhaseAlignment.gridOf(song.bpmPrecise, song.firstDownbeatMs, song.beatsPerBar)
        val grid = BarGrid.of(exact, song.beatGrid, song.downbeatGrid, null, song.beatsPerBar, song.durationMs)
        if (!grid.hasRealDownbeats) return clamped
        val bar = if (clamped < ms) grid.downbeatAtOrBefore(clamped.toDouble()) else grid.downbeatAtOrAfter(clamped.toDouble())
        return bar?.roundToInt()?.takeIf { it in 0..maxStart } ?: clamped
    }

    fun setPrelistenTarget(target: String?) {
        _state.value = _state.value?.copy(prelistenTarget = target)
    }

    fun setCrossfadeDuration(ms: Int) {
        if (_state.value?.document != null) return // con documento la duración se elige en compases
        val current = _state.value ?: return
        val clampedMs = coerceCrossfadeDuration(ms, current)
        _state.value = current.copy(
            crossfadeDurationMs = clampedMs,
            showGapSlider = clampedMs == 0 || current.gapMs > 0
        )
        checkPresetModified()
    }

    fun setCrossfadeDurationWithAutoFit(ms: Int) {
        if (_state.value?.document != null) return
        val current = _state.value ?: return
        _state.value = fitTransitionToDuration(ms, current)
        checkPresetModified()
    }

    fun setGapMs(ms: Int) {
        _state.value = _state.value?.copy(gapMs = ms)
        checkPresetModified()
    }

    fun setMixMode(mode: String) {
        val current = _state.value ?: return
        current.document?.let { doc ->
            // Con documento solo cambia el modo (smart ↔ transición); los puntos y la duración son los del documento.
            _state.value = current.copy(mixMode = mode, crossfadeDurationMs = doc.durationMs, showGapSlider = false)
            return
        }
        val updatedDuration = when {
            mode == "hard_edit" -> 0
            current.mixMode == "hard_edit" && current.crossfadeDurationMs == 0 -> 4000
            else -> current.crossfadeDurationMs
        }
        val safeDuration = coerceCrossfadeDuration(updatedDuration, current)
        _state.value = current.copy(
            mixMode = mode,
            crossfadeDurationMs = safeDuration,
            showGapSlider = mode == "hard_edit" || safeDuration == 0 || current.gapMs > 0
        )
        checkPresetModified()
    }

    /** Último modo avanzado elegido: al volver de blend automático se recupera tal cual. */
    private var lastAdvancedMixMode = PlaymixMixMode.DEFAULT_ADVANCED

    /**
     * Blend automático ↔ transición avanzada. Pasar a automático solo cambia el `mixMode`
     * (los puntos y curvas se conservan por si el usuario vuelve a avanzada).
     */
    fun setSmartMode(smart: Boolean) {
        val current = _state.value ?: return
        val isSmart = current.mixMode == PlaymixMixMode.SMART
        if (smart == isSmart) return
        if (smart) {
            lastAdvancedMixMode = current.mixMode
            _state.value = current.copy(mixMode = PlaymixMixMode.SMART)
            checkPresetModified()
        } else {
            setMixMode(lastAdvancedMixMode)
        }
    }

    fun setFadeCurveType(type: String) {
        _state.value = _state.value?.copy(
            fadeCurveType = type,
            bandFadeTypes = BandFadeTypesDTO(bass = type, mid = type, treble = type)
        )
        checkPresetModified()
    }

    fun setBandFadeType(band: String, type: String) {
        val current = _state.value ?: return
        val updated = when (band) {
            "bass" -> current.bandFadeTypes.copy(bass = type)
            "mid" -> current.bandFadeTypes.copy(mid = type)
            "treble" -> current.bandFadeTypes.copy(treble = type)
            else -> return
        }
        // Derive the global fadeCurveType from band consensus
        val consensus = if (updated.bass == updated.mid && updated.mid == updated.treble)
            updated.bass else "custom"
        _state.value = current.copy(
            bandFadeTypes = updated,
            fadeCurveType = consensus
        )
        checkPresetModified()
    }

    fun toggleGapSlider(show: Boolean) {
        _state.value = _state.value?.copy(showGapSlider = show, gapMs = if (!show) 0 else _state.value?.gapMs ?: 0)
    }

    fun saveTransition() {
        val current = _state.value ?: return

        val v2 = current.v2
        if (v2 != null && studioManager != null) {
            saveDocument(studioManager, current, v2.document)
            return
        }
        if (current.mixMode == PlaymixMixMode.SMART) {
            saveSmart(current)
            return
        }
        val manager = studioManager
        val document = current.document
        if (manager != null && document != null && current.studio != null) {
            saveDocument(manager, current, document)
            return
        }

        if (current.activePresetCode != null && !current.isPresetModified) {
            _state.value = current.copy(isSaving = false, savedSuccess = true, error = null)
            return
        }

        val safeDuration = coerceCrossfadeDuration(current.crossfadeDurationMs, current)
        val presetCodeToPersist = current.activePresetCode?.takeUnless { current.isPresetModified }
        val stateToSave = current.copy(
            crossfadeDurationMs = safeDuration,
            activePresetCode = presetCodeToPersist,
            activePresetName = current.activePresetName.takeUnless { current.isPresetModified }
        )
        _state.value = stateToSave.copy(isSaving = true, savedSuccess = false)

        viewModelScope.launch {
            try {
                val update = PlaymixTransitionUpdateDTO(
                    exitPointMs = stateToSave.exitPointMs,
                    entryPointMs = stateToSave.entryPointMs,
                    crossfadeDurationMs = stateToSave.crossfadeDurationMs,
                    fadeCurveType = stateToSave.fadeCurveType,
                    eqSettings = stateToSave.eqSettings,
                    eqSettingsA = stateToSave.eqSettingsA,
                    eqSettingsB = stateToSave.eqSettingsB,
                    mixMode = stateToSave.mixMode,
                    bandFadeTypes = stateToSave.bandFadeTypes,
                    gapMs = stateToSave.gapMs,
                    presetCode = stateToSave.activePresetCode,
                    isPresetModified = stateToSave.isPresetModified
                )
                val updated = withContext(ioDispatcher) {
                    playmixManager.updateTransition(playmixId, transitionId, update)
                }
                applyTransitionToState(updated)
                _state.value = _state.value?.copy(isSaving = false, savedSuccess = true, error = null)
            } catch (e: Exception) {
                logError("Error saving transition", e)
                _state.value = stateToSave.copy(isSaving = false, error = editError(e, "Error al guardar: ${e.message}"))
            }
        }
    }

    /** Guarda el documento en Studio. Un 409 (otra revisión) recarga y pide volver a guardar. */
    private fun saveDocument(manager: StudioManager, current: CrossfadeEditorState, document: MixDocumentDTO) {
        _state.value = current.copy(isSaving = true, savedSuccess = false, error = null)
        viewModelScope.launch {
            try {
                val saved = withContext(ioDispatcher) { manager.save(playmixId, transitionId, current.revision, document) }
                applyStudio(saved, keepSmart = false)
                _state.value = _state.value?.copy(isSaving = false, savedSuccess = true, error = null)
            } catch (e: Exception) {
                logError("Error guardando el documento", e)
                val conflict = e is HttpException && e.code() == 409
                if (conflict) loadStudio()
                _state.value = _state.value?.copy(
                    isSaving = false,
                    error = if (conflict) "La transición cambió en otro sitio. Revisa y vuelve a guardar."
                            else "No se pudo guardar la transición."
                )
            }
        }
    }

    /**
     * Blend automático: se guarda con el editor antiguo (`mixMode = "smart"`). Si la transición tenía documento en
     * Studio, primero se suelta (`detach`), porque el PUT antiguo lo rechazaría con 409.
     */
    private fun saveSmart(current: CrossfadeEditorState) {
        _state.value = current.copy(isSaving = true, savedSuccess = false, error = null)
        viewModelScope.launch {
            try {
                val manager = studioManager
                if (manager != null && current.studio?.derived == false) {
                    withContext(ioDispatcher) { manager.detach(playmixId, transitionId) }
                    // Ya sin documento en el servidor: un reintento tras un fallo del PUT no vuelve a soltar.
                    _state.value = _state.value?.copy(studio = current.studio.copy(derived = true, revision = 0), revision = 0, document = null)
                }
                val update = PlaymixTransitionUpdateDTO(
                    exitPointMs = current.exitPointMs,
                    entryPointMs = current.entryPointMs,
                    crossfadeDurationMs = current.crossfadeDurationMs,
                    fadeCurveType = current.fadeCurveType,
                    eqSettings = current.eqSettings,
                    eqSettingsA = current.eqSettingsA,
                    eqSettingsB = current.eqSettingsB,
                    mixMode = PlaymixMixMode.SMART,
                    bandFadeTypes = current.bandFadeTypes,
                    gapMs = 0,
                    presetCode = null,
                    isPresetModified = false
                )
                val updated = withContext(ioDispatcher) { playmixManager.updateTransition(playmixId, transitionId, update) }
                applyTransitionToState(updated)
                _state.value = _state.value?.copy(
                    isSaving = false, savedSuccess = true, error = null,
                    studio = _state.value?.studio?.copy(derived = true, revision = 0), revision = 0
                )
            } catch (e: Exception) {
                logError("Error guardando el blend automático", e)
                _state.value = _state.value?.copy(isSaving = false, error = editError(e, "No se pudo guardar la transición."))
            }
        }
    }

    fun onSaveHandled() {
        _state.value = _state.value?.copy(savedSuccess = false, error = null)
    }
}

private val RENDERING = setOf("pending", "rendering")
private const val RENDER_POLLS = 40
private const val RENDER_POLL_MS = 3_000L

class CrossfadeEditorViewModelFactory(
    private val playmixManager: PlaymixManager,
    private val presetManager: TransitionPresetManager,
    private val studioManager: StudioManager? = null,
    private val v2Enabled: Boolean = false
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CrossfadeEditorViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CrossfadeEditorViewModel(playmixManager, presetManager, studioManager = studioManager, v2Enabled = v2Enabled) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
