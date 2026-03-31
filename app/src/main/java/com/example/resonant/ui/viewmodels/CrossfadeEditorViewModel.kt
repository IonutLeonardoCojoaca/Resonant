package com.example.resonant.ui.viewmodels

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.network.EqBandDTO
import com.example.resonant.data.network.EqSettingsDTO
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.data.network.PlaymixTransitionUpdateDTO
import com.example.resonant.data.network.WaveformResponseDTO
import com.example.resonant.managers.PlaymixManager
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
    val isSaving: Boolean = false,
    val error: String? = null,
    val savedSuccess: Boolean = false,
    // BPM matching
    val bpmA: Double = 0.0,
    val bpmB: Double = 0.0,
    val targetBpm: Double = 0.0,
    val bpmSynced: Boolean = false,
    val beatAlignScore: Int = 0
)

fun defaultEq() = EqSettingsDTO(
    bands = listOf(60, 250, 1000, 4000, 12000).map { EqBandDTO(it, 0.0) }
)

class CrossfadeEditorViewModel(private val playmixManager: PlaymixManager) : ViewModel() {

    private val _state = MutableLiveData(CrossfadeEditorState())
    val state: LiveData<CrossfadeEditorState> get() = _state

    private var playmixId: String = ""
    private var transitionId: String = ""

    fun loadWaveformData(playmixId: String, transitionId: String) {
        this.playmixId = playmixId
        this.transitionId = transitionId

        _state.value = _state.value?.copy(isLoading = true, error = null)

        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    playmixManager.getWaveformData(playmixId, transitionId)
                }
                val transition = data.transition
                val bA = data.songA.bpm ?: 0.0
                val bB = data.songB.bpm ?: 0.0
                val target = if (bA > 0 && bB > 0) (bA + bB) / 2.0 else bA.coerceAtLeast(bB)
                _state.postValue(
                    CrossfadeEditorState(
                        isLoading = false,
                        waveformData = data,
                        exitPointMs = transition.exitPointMs,
                        entryPointMs = transition.entryPointMs,
                        crossfadeDurationMs = transition.crossfadeDurationMs,
                        fadeCurveType = transition.fadeCurveType,
                        eqSettings = transition.eqSettings ?: defaultEq(),
                        error = null,
                        bpmA = bA,
                        bpmB = bB,
                        targetBpm = target,
                        bpmSynced = false,
                        beatAlignScore = calculateBeatAlignScore(
                            data.songA.beatGrid, data.songB.beatGrid,
                            bA, bB, target,
                            transition.exitPointMs, transition.entryPointMs, transition.crossfadeDurationMs
                        )
                    )
                )
            } catch (e: Exception) {
                Log.e("CrossfadeEditorVM", "Error loading waveform data", e)
                _state.postValue(
                    _state.value?.copy(isLoading = false, error = "Error al cargar datos: ${e.message}")
                )
            }
        }
    }

    fun setExitPoint(ms: Int) {
        _state.value = _state.value?.copy(exitPointMs = ms)
    }

    fun setEntryPoint(ms: Int) {
        _state.value = _state.value?.copy(entryPointMs = ms)
    }

    fun setCrossfadeDuration(ms: Int) {
        _state.value = _state.value?.copy(crossfadeDurationMs = ms)
    }

    fun setFadeCurveType(type: String) {
        _state.value = _state.value?.copy(fadeCurveType = type)
    }

    fun updateEqBand(index: Int, gainDb: Double) {
        val current = _state.value?.eqSettings ?: return
        val updatedBands = current.bands.toMutableList()
        if (index in updatedBands.indices) {
            updatedBands[index] = updatedBands[index].copy(gainDb = gainDb)
            _state.value = _state.value?.copy(eqSettings = EqSettingsDTO(updatedBands))
        }
    }

    fun resetEq() {
        _state.value = _state.value?.copy(eqSettings = defaultEq())
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
                    eqSettings = current.eqSettings
                )
                withContext(Dispatchers.IO) {
                    playmixManager.updateTransition(playmixId, transitionId, update)
                }
                _state.postValue(current.copy(isSaving = false, savedSuccess = true))
            } catch (e: Exception) {
                Log.e("CrossfadeEditorVM", "Error saving transition", e)
                _state.postValue(current.copy(isSaving = false, error = "Error al guardar: ${e.message}"))
            }
        }
    }

    fun onSaveHandled() {
        _state.value = _state.value?.copy(savedSuccess = false, error = null)
    }

    // ─── BPM Matching ───────────────────────────

    fun setTargetBpm(bpm: Double) {
        val current = _state.value ?: return
        val synced = current.bpmA > 0 && current.bpmB > 0
        _state.value = current.copy(targetBpm = bpm, bpmSynced = synced)
        recalcBeatAlign()
    }

    fun syncBpmToA() {
        val current = _state.value ?: return
        if (current.bpmA > 0) setTargetBpm(current.bpmA)
    }

    fun syncBpmToB() {
        val current = _state.value ?: return
        if (current.bpmB > 0) setTargetBpm(current.bpmB)
    }

    fun autoMatch() {
        val current = _state.value ?: return
        val data = current.waveformData ?: return
        val beatsA = data.songA.beatGrid ?: return
        val beatsB = data.songB.beatGrid ?: return
        if (current.bpmA <= 0 || current.bpmB <= 0) return

        // Find the target BPM and exit/entry that maximise beat alignment
        val bpmMin = minOf(current.bpmA, current.bpmB) * 0.95
        val bpmMax = maxOf(current.bpmA, current.bpmB) * 1.05
        val durationA = data.songA.durationMs
        val durationB = data.songB.durationMs
        val cfDuration = current.crossfadeDurationMs

        var bestScore = -1
        var bestBpm = current.targetBpm
        var bestExitMs = current.exitPointMs
        var bestEntryMs = current.entryPointMs

        // Test BPM in 0.5 increments
        var testBpm = bpmMin
        while (testBpm <= bpmMax) {
            val ratioA = (testBpm / current.bpmA).toFloat()
            val ratioB = (testBpm / current.bpmB).toFloat()

            // Scale beat grids
            val scaledA = beatsA.map { (it / ratioA).toInt() }
            val scaledB = beatsB.map { (it / ratioB).toInt() }

            // Try exit points at each beat of A in the last 30%
            val exitStartMs = (durationA * 0.6).toInt()
            for (exitBeat in scaledA) {
                if (exitBeat < exitStartMs) continue
                if (exitBeat + cfDuration > durationA) continue

                // Try entry points at each beat of B in the first 30%
                val entryEndMs = (durationB * 0.35).toInt()
                for (entryBeat in scaledB) {
                    if (entryBeat > entryEndMs) break

                    val score = countAlignedBeats(
                        scaledA, scaledB,
                        exitBeat, entryBeat, cfDuration
                    )
                    if (score > bestScore) {
                        bestScore = score
                        bestBpm = testBpm
                        bestExitMs = exitBeat
                        bestEntryMs = entryBeat
                    }
                }
            }
            testBpm += 0.5
        }

        _state.value = current.copy(
            targetBpm = bestBpm,
            exitPointMs = bestExitMs,
            entryPointMs = bestEntryMs,
            bpmSynced = true,
            beatAlignScore = bestScore.coerceIn(0, 100)
        )
    }

    private fun recalcBeatAlign() {
        val current = _state.value ?: return
        val data = current.waveformData ?: return
        val score = calculateBeatAlignScore(
            data.songA.beatGrid, data.songB.beatGrid,
            current.bpmA, current.bpmB, current.targetBpm,
            current.exitPointMs, current.entryPointMs, current.crossfadeDurationMs
        )
        _state.value = current.copy(beatAlignScore = score)
    }

    private fun calculateBeatAlignScore(
        beatsA: List<Int>?, beatsB: List<Int>?,
        bpmA: Double, bpmB: Double, targetBpm: Double,
        exitMs: Int, entryMs: Int, cfMs: Int
    ): Int {
        if (beatsA.isNullOrEmpty() || beatsB.isNullOrEmpty() || bpmA <= 0 || bpmB <= 0 || targetBpm <= 0) return 0

        val ratioA = (targetBpm / bpmA).toFloat()
        val ratioB = (targetBpm / bpmB).toFloat()
        val scaledA = beatsA.map { (it / ratioA).toInt() }
        val scaledB = beatsB.map { (it / ratioB).toInt() }

        return countAlignedBeats(scaledA, scaledB, exitMs, entryMs, cfMs)
    }

    private fun countAlignedBeats(
        scaledA: List<Int>, scaledB: List<Int>,
        exitMs: Int, entryMs: Int, cfMs: Int
    ): Int {
        val tolerance = 30 // ms
        var count = 0
        val cfEnd = exitMs + cfMs

        for (beatA in scaledA) {
            if (beatA < exitMs || beatA > cfEnd) continue
            val progress = (beatA - exitMs).toFloat() / cfMs
            val correspondingBMs = entryMs + (progress * cfMs).toInt()

            for (beatB in scaledB) {
                if (kotlin.math.abs(beatB - correspondingBMs) <= tolerance) {
                    count++
                    break
                }
            }
        }
        return count
    }
}

class CrossfadeEditorViewModelFactory(private val playmixManager: PlaymixManager) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CrossfadeEditorViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CrossfadeEditorViewModel(playmixManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
