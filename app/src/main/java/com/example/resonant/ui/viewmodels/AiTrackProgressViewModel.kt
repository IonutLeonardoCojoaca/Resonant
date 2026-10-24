package com.example.resonant.ui.viewmodels

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.network.AiTrackDTO
import com.example.resonant.managers.AiTrackException
import com.example.resonant.managers.AiTrackManager
import com.example.resonant.managers.AiTrackMessages
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Pantalla de generación de una pista: consulta la generación cada [pollIntervalMs] mientras la pantalla está
 * visible ([setVisible]) y para sola cuando termina (completada o fallida).
 */
class AiTrackProgressViewModel(
    private val manager: AiTrackManager,
    private val generationId: String,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS
) : ViewModel() {

    private val _track = MutableLiveData<AiTrackDTO?>()
    val track: LiveData<AiTrackDTO?> get() = _track

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> get() = _error

    private var visible = false
    private var pollJob: Job? = null

    val isPolling: Boolean get() = pollJob?.isActive == true

    fun setVisible(isVisible: Boolean) {
        visible = isVisible
        if (isVisible) startPolling() else stopPolling()
    }

    fun retry() {
        viewModelScope.launch {
            try {
                _track.value = manager.retry(generationId)
                startPolling()
            } catch (e: Exception) {
                _error.value = messageFor(e)
            }
        }
    }

    fun onErrorShown() {
        _error.value = null
    }

    private fun isFinished(track: AiTrackDTO?): Boolean =
        track != null && (track.status == AiTrackDTO.STATUS_COMPLETED || track.isFailed)

    private fun startPolling() {
        if (!visible || isPolling || isFinished(_track.value)) return
        pollJob = viewModelScope.launch {
            while (isActive && visible) {
                try {
                    _track.value = manager.get(generationId)
                } catch (e: Exception) {
                    Log.w(TAG, "Consulta de la generación fallida", e)
                    if (_track.value == null) _error.value = messageFor(e)
                }
                if (isFinished(_track.value)) break
                delay(pollIntervalMs)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun messageFor(e: Exception): String = when (e) {
        is AiTrackException -> AiTrackMessages.forRequestError(e)
        is IOException -> "Sin conexión. Seguimos intentándolo cuando vuelva la red."
        else -> "No se pudo consultar la pista."
    }

    companion object {
        const val POLL_INTERVAL_MS = 3_000L
        private const val TAG = "AiTrackProgressVM"
    }
}

class AiTrackProgressViewModelFactory(
    private val manager: AiTrackManager,
    private val generationId: String
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AiTrackProgressViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AiTrackProgressViewModel(manager, generationId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
