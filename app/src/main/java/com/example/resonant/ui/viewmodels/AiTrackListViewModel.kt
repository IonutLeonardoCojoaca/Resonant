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
 * Lista de generaciones "Crear con IA". Mientras la pantalla está visible ([setVisible]) y hay alguna
 * generación en cola o en curso, vuelve a pedir la lista cada [pollIntervalMs]; para sola cuando todas
 * están completadas o fallidas.
 */
class AiTrackListViewModel(
    private val manager: AiTrackManager,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS
) : ViewModel() {

    private val _items = MutableLiveData<List<AiTrackDTO>>()
    val items: LiveData<List<AiTrackDTO>> get() = _items

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> get() = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> get() = _error

    private var visible = false
    private var pollJob: Job? = null

    val isPolling: Boolean get() = pollJob?.isActive == true

    fun load() {
        _isLoading.value = _items.value == null
        viewModelScope.launch {
            try {
                fetch()
            } catch (e: Exception) {
                _error.value = messageFor(e)
                if (_items.value == null) _items.value = emptyList()
            } finally {
                _isLoading.value = false
                ensurePolling()
            }
        }
    }

    fun setVisible(isVisible: Boolean) {
        visible = isVisible
        if (isVisible) ensurePolling() else stopPolling()
    }

    fun retry(id: String) {
        viewModelScope.launch {
            try {
                val updated = manager.retry(id)
                _items.value = _items.value.orEmpty().map { if (it.id == id) updated else it }
                ensurePolling()
            } catch (e: Exception) {
                _error.value = messageFor(e)
            }
        }
    }

    fun onErrorShown() {
        _error.value = null
    }

    private suspend fun fetch() {
        _items.value = manager.list().items
    }

    private fun ensurePolling() {
        if (!visible || isPolling || _items.value.orEmpty().none { it.isOpen }) return
        pollJob = viewModelScope.launch {
            while (isActive && visible) {
                delay(pollIntervalMs)
                try {
                    fetch()
                } catch (e: Exception) {
                    Log.w(TAG, "Consulta de generaciones fallida", e)
                }
                if (_items.value.orEmpty().none { it.isOpen }) break
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun messageFor(e: Exception): String = when (e) {
        is AiTrackException -> AiTrackMessages.forRequestError(e)
        is IOException -> "Sin conexión. Revisa la red e inténtalo de nuevo."
        else -> {
            Log.e(TAG, "Error en la lista de pistas con IA", e)
            "No se pudieron cargar tus pistas."
        }
    }

    companion object {
        const val POLL_INTERVAL_MS = 5_000L
        private const val TAG = "AiTrackListVM"
    }
}

class AiTrackListViewModelFactory(private val manager: AiTrackManager) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AiTrackListViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AiTrackListViewModel(manager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
