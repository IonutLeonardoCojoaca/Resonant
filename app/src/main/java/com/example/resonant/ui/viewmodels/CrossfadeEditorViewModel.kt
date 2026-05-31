package com.example.resonant.ui.viewmodels

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.network.BandFadeTypesDTO
import com.example.resonant.data.network.EqBandDTO
import com.example.resonant.data.network.EqSettingsDTO
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.data.network.PlaymixTransitionUpdateDTO
import com.example.resonant.data.network.TransitionPresetDTO
import com.example.resonant.data.network.WaveformResponseDTO
import com.example.resonant.managers.PlaymixManager
import com.example.resonant.managers.TransitionPresetManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


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
    val prelistenTarget: String? = null // "songA" or "songB" or null
)

fun defaultEq() = EqSettingsDTO(
    bands = listOf(60, 250, 1000, 4000, 12000).map { EqBandDTO(it, 0.0) }
)

class CrossfadeEditorViewModel(
    private val playmixManager: PlaymixManager,
    private val presetManager: TransitionPresetManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
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

        viewModelScope.launch {
            try {
                val data = withContext(ioDispatcher) {
                    playmixManager.getWaveformData(playmixId, transitionId)
                }
                val transition = data.transition
                val bA = data.songA.bpm ?: 0.0
                val bB = data.songB.bpm ?: 0.0

                _state.value = _state.value?.copy(
                    isLoading = false,
                    waveformData = data,
                    exitPointMs = transition.exitPointMs,
                    entryPointMs = transition.entryPointMs,
                    crossfadeDurationMs = transition.crossfadeDurationMs,
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
                    error = "Error al aplicar el preset."
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
                    error = "Error al resetear el preset."
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
        val moved = current.copy(exitPointMs = ms.coerceAtLeast(0))
        _state.value = moved.copy(
            crossfadeDurationMs = coerceCrossfadeDuration(moved.crossfadeDurationMs, moved)
        )
        checkPresetModified()
    }

    fun setEntryPoint(ms: Int) {
        val current = _state.value ?: return
        val moved = current.copy(entryPointMs = ms.coerceAtLeast(0))
        _state.value = moved.copy(
            crossfadeDurationMs = coerceCrossfadeDuration(moved.crossfadeDurationMs, moved)
        )
        checkPresetModified()
    }

    fun setPrelistenTarget(target: String?) {
        _state.value = _state.value?.copy(prelistenTarget = target)
    }

    fun setCrossfadeDuration(ms: Int) {
        val current = _state.value ?: return
        val clampedMs = coerceCrossfadeDuration(ms, current)
        _state.value = current.copy(
            crossfadeDurationMs = clampedMs,
            showGapSlider = clampedMs == 0 || current.gapMs > 0
        )
        checkPresetModified()
    }

    fun setCrossfadeDurationWithAutoFit(ms: Int) {
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
                _state.value = stateToSave.copy(isSaving = false, error = "Error al guardar: ${e.message}")
            }
        }
    }

    fun onSaveHandled() {
        _state.value = _state.value?.copy(savedSuccess = false, error = null)
    }
}

class CrossfadeEditorViewModelFactory(
    private val playmixManager: PlaymixManager,
    private val presetManager: TransitionPresetManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CrossfadeEditorViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CrossfadeEditorViewModel(playmixManager, presetManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
