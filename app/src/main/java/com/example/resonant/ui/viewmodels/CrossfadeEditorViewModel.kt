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
    private val presetManager: TransitionPresetManager
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
        val gapMs: Int
    )

    fun loadWaveformData(playmixId: String, transitionId: String) {
        this.playmixId = playmixId
        this.transitionId = transitionId

        _state.value = _state.value?.copy(isLoading = true, error = null)

        // Load presets in parallel
        loadPresets()

        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
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
                        gapMs = transition.gapMs
                    )
                }
            } catch (e: Exception) {
                Log.e("CrossfadeEditorVM", "Error loading waveform data", e)
                _state.value = _state.value?.copy(isLoading = false, error = "Error al cargar datos: ${e.message}")
            }
        }
    }

    // ─── Preset Loading ─────────────────────────

    private fun loadPresets() {
        _state.value = _state.value?.copy(presetsLoading = true, presetsError = null)
        viewModelScope.launch {
            try {
                val presets = withContext(Dispatchers.IO) {
                    presetManager.getPresets()
                }
                _state.value = _state.value?.copy(
                    availablePresets = presets,
                    presetsLoading = false,
                    presetsError = null
                )
            } catch (e: Exception) {
                Log.e("CrossfadeEditorVM", "Error loading presets", e)
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
                val updated = withContext(Dispatchers.IO) {
                    presetManager.applyPreset(playmixId, transitionId, presetCode)
                }
                applyTransitionToState(updated)
                saveAfterApply()
            } catch (e: Exception) {
                Log.e("CrossfadeEditorVM", "Error applying preset", e)
                _state.value = _state.value?.copy(
                    presetsLoading = false,
                    error = "Error al aplicar el preset."
                )
            }
        }
    }

    fun onDismissPreview() { /* no-op – preview step removed */ }

    fun onApplyPreset(presetCode: String) = onPresetSelected(presetCode)

    private fun saveAfterApply() {
        val current = _state.value ?: return
        viewModelScope.launch {
            try {
                val update = com.example.resonant.data.network.PlaymixTransitionUpdateDTO(
                    exitPointMs = current.exitPointMs,
                    entryPointMs = current.entryPointMs,
                    crossfadeDurationMs = current.crossfadeDurationMs,
                    fadeCurveType = current.fadeCurveType,
                    eqSettings = current.eqSettings,
                    eqSettingsA = current.eqSettingsA,
                    eqSettingsB = current.eqSettingsB,
                    mixMode = current.mixMode ?: "crossfade",
                    bandFadeTypes = current.bandFadeTypes,
                    gapMs = current.gapMs,
                    presetCode = current.activePresetCode,
                    isPresetModified = current.isPresetModified
                )
                withContext(Dispatchers.IO) {
                    playmixManager.updateTransition(playmixId, transitionId, update)
                }
                _state.value = _state.value?.copy(presetsLoading = false, savedSuccess = true)
            } catch (e: Exception) {
                Log.e("CrossfadeEditorVM", "Error saving after apply", e)
                _state.value = _state.value?.copy(presetsLoading = false)
            }
        }
    }

    fun onResetPreset() {
        val current = _state.value ?: return
        if (current.activePresetCode == null) return

        _state.value = current.copy(presetsLoading = true)
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    presetManager.resetPreset(playmixId, transitionId)
                }
                applyTransitionToState(updated)
                saveAfterApply()
            } catch (e: Exception) {
                Log.e("CrossfadeEditorVM", "Error resetting preset", e)
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
                gapMs = transition.gapMs
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
                current.gapMs != snapshot.gapMs
        if (modified != current.isPresetModified) {
            _state.value = current.copy(isPresetModified = modified)
        }
    }

    fun setExitPoint(ms: Int) {
        _state.value = _state.value?.copy(exitPointMs = ms)
        checkPresetModified()
    }

    fun setEntryPoint(ms: Int) {
        _state.value = _state.value?.copy(entryPointMs = ms)
        checkPresetModified()
    }

    fun setPrelistenTarget(target: String?) {
        _state.value = _state.value?.copy(prelistenTarget = target)
    }

    fun setCrossfadeDuration(ms: Int) {
        _state.value = _state.value?.copy(
            crossfadeDurationMs = ms,
            showGapSlider = ms == 0 || (_state.value?.gapMs ?: 0) > 0
        )
        checkPresetModified()
    }

    fun setGapMs(ms: Int) {
        _state.value = _state.value?.copy(gapMs = ms)
        checkPresetModified()
    }

    fun setMixMode(mode: String) {
        _state.value = _state.value?.copy(mixMode = mode)
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
        _state.value = current.copy(isSaving = true, savedSuccess = false)

        viewModelScope.launch {
            try {
                val update = PlaymixTransitionUpdateDTO(
                    exitPointMs = current.exitPointMs,
                    entryPointMs = current.entryPointMs,
                    crossfadeDurationMs = current.crossfadeDurationMs,
                    fadeCurveType = current.fadeCurveType,
                    eqSettings = current.eqSettings,
                    eqSettingsA = current.eqSettingsA,
                    eqSettingsB = current.eqSettingsB,
                    mixMode = current.mixMode,
                    bandFadeTypes = current.bandFadeTypes,
                    gapMs = current.gapMs,
                    presetCode = current.activePresetCode,
                    isPresetModified = current.isPresetModified
                )
                withContext(Dispatchers.IO) {
                    playmixManager.updateTransition(playmixId, transitionId, update)
                }
                _state.value = current.copy(isSaving = false, savedSuccess = true)
            } catch (e: Exception) {
                Log.e("CrossfadeEditorVM", "Error saving transition", e)
                _state.value = current.copy(isSaving = false, error = "Error al guardar: ${e.message}")
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
