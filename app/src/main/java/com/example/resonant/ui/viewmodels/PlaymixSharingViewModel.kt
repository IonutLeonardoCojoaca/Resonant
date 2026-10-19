package com.example.resonant.ui.viewmodels

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.network.PlaymixDetailDTO
import com.example.resonant.data.network.PlaymixSongDTO
import com.example.resonant.managers.PlaymixSharingManager
import kotlinx.coroutines.launch
import retrofit2.HttpException

/** Me gusta de la Playmix abierta: estado optimista (se revierte si falla). */
data class PlaymixLikeState(val liked: Boolean, val count: Int, val pending: Boolean = false)

/**
 * Compartir y descubrir en el detalle de una Playmix (playmix-sharing-discovery): me gusta, visibilidad,
 * duplicar y fusionar. Los resultados que navegan (copia, fusión) llegan por callback en el hilo principal.
 */
class PlaymixSharingViewModel(private val manager: PlaymixSharingManager) : ViewModel() {

    private val _like = MutableLiveData<PlaymixLikeState?>(null)
    val like: LiveData<PlaymixLikeState?> get() = _like

    private val _message = MutableLiveData<String?>()
    val message: LiveData<String?> get() = _message

    /** Toma el estado del detalle salvo que haya un cambio de me gusta en curso. */
    fun bind(detail: PlaymixDetailDTO) {
        if (_like.value?.pending == true) return
        _like.value = PlaymixLikeState(detail.isLiked, detail.likeCount)
    }

    fun toggleLike(playmixId: String) {
        val current = _like.value ?: return
        if (current.pending) return
        val liked = !current.liked
        val optimistic = PlaymixLikeState(liked, (current.count + if (liked) 1 else -1).coerceAtLeast(0), pending = true)
        _like.value = optimistic
        viewModelScope.launch {
            try {
                manager.setLiked(playmixId, liked)
                _like.value = optimistic.copy(pending = false)
            } catch (e: Exception) {
                Log.w(TAG, "like failed: ${e.javaClass.simpleName}")
                _like.value = current
                _message.value = if (liked) "No se pudo dar me gusta" else "No se pudo quitar el me gusta"
            }
        }
    }

    fun setVisibility(playmixId: String, visibility: String, onDone: (PlaymixDetailDTO) -> Unit) {
        viewModelScope.launch {
            try {
                onDone(manager.setVisibility(playmixId, visibility))
            } catch (e: Exception) {
                Log.w(TAG, "visibility failed: ${e.javaClass.simpleName}")
                _message.value = "No se pudo cambiar la visibilidad"
            }
        }
    }

    fun copy(playmixId: String, name: String?, onDone: (PlaymixDetailDTO) -> Unit) {
        viewModelScope.launch {
            try {
                onDone(manager.copyPlaymix(playmixId, name))
            } catch (e: Exception) {
                Log.w(TAG, "copy failed: ${e.javaClass.simpleName}")
                _message.value = when ((e as? HttpException)?.code()) {
                    404 -> "Esta Playmix no está disponible"
                    422 -> "El nombre no es válido (máximo 120 caracteres)"
                    else -> "No se pudo copiar la Playmix"
                }
            }
        }
    }

    fun merge(playmixIds: List<String>, name: String, onDone: (PlaymixDetailDTO) -> Unit) {
        viewModelScope.launch {
            try {
                onDone(manager.mergePlaymixes(playmixIds, name))
            } catch (e: Exception) {
                Log.w(TAG, "merge failed: ${e.javaClass.simpleName}")
                _message.value = when ((e as? HttpException)?.code()) {
                    404 -> "Alguna Playmix ya no está disponible"
                    422 -> "No se pueden fusionar: entre 2 y 5 Playmix y como mucho 200 canciones"
                    else -> "No se pudo fusionar"
                }
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private companion object {
        const val TAG = "PlaymixSharingVM"
    }
}

/** Ordenar una Playmix: vista previa del servidor y "Aplicar" con el mismo criterio (y semilla en aleatorio). */
data class PlaymixSortState(
    val criterion: String? = null,
    val direction: String = "asc",
    val isLoading: Boolean = false,
    val preview: List<PlaymixSongDTO> = emptyList(),
    val seed: Int? = null,
    val applied: Boolean = false,
    val error: String? = null
)

class PlaymixSortViewModel(private val manager: PlaymixSharingManager) : ViewModel() {

    private val _state = MutableLiveData(PlaymixSortState())
    val state: LiveData<PlaymixSortState> get() = _state

    /** Pide el orden propuesto; no guarda nada. En aleatorio, cada vista previa trae una semilla nueva. */
    fun preview(playmixId: String, criterion: String, direction: String) {
        val base = PlaymixSortState(criterion = criterion, direction = direction, isLoading = true)
        _state.value = base
        viewModelScope.launch {
            try {
                val result = manager.sortPlaymix(playmixId, criterion, direction, preview = true)
                _state.value = base.copy(isLoading = false, preview = result.songs.orEmpty(), seed = result.seed)
            } catch (e: Exception) {
                Log.w("PlaymixSortVM", "preview failed: ${e.javaClass.simpleName}")
                _state.value = base.copy(isLoading = false, error = "No se pudo calcular el orden")
            }
        }
    }

    /** Guarda el orden de la última vista previa (mismo criterio, dirección y semilla). */
    fun apply(playmixId: String) {
        val current = _state.value ?: return
        val criterion = current.criterion ?: return
        if (current.isLoading || current.preview.isEmpty()) return
        _state.value = current.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                manager.sortPlaymix(playmixId, criterion, current.direction, preview = false, seed = current.seed)
                _state.value = current.copy(isLoading = false, applied = true)
            } catch (e: Exception) {
                Log.w("PlaymixSortVM", "apply failed: ${e.javaClass.simpleName}")
                _state.value = current.copy(isLoading = false, error = "No se pudo aplicar el orden")
            }
        }
    }
}

class PlaymixSharingViewModelFactory(private val manager: PlaymixSharingManager) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return when {
            modelClass.isAssignableFrom(PlaymixSharingViewModel::class.java) -> PlaymixSharingViewModel(manager) as T
            modelClass.isAssignableFrom(PlaymixSortViewModel::class.java) -> PlaymixSortViewModel(manager) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
