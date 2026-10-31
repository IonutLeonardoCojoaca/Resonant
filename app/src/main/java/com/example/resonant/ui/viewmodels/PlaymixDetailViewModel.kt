package com.example.resonant.ui.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.PlaymixDetailDTO
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.managers.PlaymixManager
import com.example.resonant.playback.OfflineCollectionStore
import com.example.resonant.playback.PlaymixSongMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaymixDetailScreenState(
    val isLoading: Boolean = true,
    val detail: PlaymixDetailDTO? = null,
    val error: String? = null,
    /** 404: no existe o no se puede ver (privada ajena, enlace retirado). La pantalla muestra "no disponible". */
    val notFound: Boolean = false
)

class PlaymixDetailViewModel(
    private val playmixManager: PlaymixManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _screenState = MutableLiveData(PlaymixDetailScreenState())
    val screenState: LiveData<PlaymixDetailScreenState> get() = _screenState

    /** Mensaje de error pendiente de mostrar; la pantalla lo consume con [consumeError]. */
    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> get() = _error

    fun consumeError() {
        _error.value = null
    }

    private var currentPlaymixId: String? = null

    /** Única carga del detalle en vuelo: la siguiente cancela la anterior, así nunca gana una respuesta vieja. */
    private var refreshJob: Job? = null

    /** Contexto para leer la Playmix descargada sin conexión (lo fija la pantalla). */
    var offlineContext: Context? = null

    /**
     * Pinta el último detalle conocido al instante y revalida solo si caducó (60 s en el manager) o se fuerza.
     * Volver del editor ya no se salta la revalidación: lo que se guarda invalida la caché del manager.
     */
    fun loadPlaymixDetail(playmixId: String, forceReload: Boolean = false) {
        if (playmixId != currentPlaymixId) refreshJob?.cancel()
        currentPlaymixId = playmixId
        val shown = _screenState.value?.detail?.takeIf { it.id == playmixId }
        val cached = playmixManager.cachedDetail(playmixId)
        if (shown == null && cached != null) {
            _screenState.value = PlaymixDetailScreenState(isLoading = false, detail = cached)
        }
        val hasDetail = shown != null || cached != null
        if (!forceReload && hasDetail && playmixManager.isDetailFresh(playmixId)) return
        if (!forceReload && refreshJob?.isActive == true) return
        refreshDetail(playmixId, showLoading = !hasDetail)
    }

    private fun refreshDetail(playmixId: String, showLoading: Boolean = false) {
        if (showLoading) {
            _screenState.value = _screenState.value?.copy(isLoading = true, error = null)
        }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                val detail = withContext(ioDispatcher) {
                    playmixManager.getPlaymixDetail(playmixId, forceRefresh = true)
                }
                _screenState.value = PlaymixDetailScreenState(isLoading = false, detail = detail, error = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PlaymixDetailVM", "Error loading detail", e)
                if (e is retrofit2.HttpException && (e.code() == 404 || e.code() == 403)) { // privada ajena: como en CollectionSyncDiff
                    _screenState.value = PlaymixDetailScreenState(isLoading = false, notFound = true)
                    return@launch
                }
                // Sin red: el detalle guardado de una Playmix descargada (collection-queue-offline D5).
                val offline = offlineContext?.let { ctx ->
                    withContext(ioDispatcher) { OfflineCollectionStore.playmixDetail(ctx, playmixId) }
                }
                if (offline != null) {
                    Log.d("PlaymixDetailVM", "Playmix $playmixId abierta desde la descarga")
                    _screenState.value = PlaymixDetailScreenState(isLoading = false, detail = offline, error = null)
                    return@launch
                }
                _screenState.value = _screenState.value?.copy(isLoading = false, error = LOAD_ERROR_MESSAGE)
                _error.value = LOAD_ERROR_MESSAGE
            }
        }
    }

    fun addSongToPlaymix(songId: String) {
        val playmixId = currentPlaymixId ?: return
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { playmixManager.addSongToPlaymix(playmixId, songId) }
                refreshDetail(playmixId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Error al añadir canción: ${e.message}"
            }
        }
    }

    fun removeSong(playmixSongId: String, onSuccess: () -> Unit = {}) {
        val playmixId = currentPlaymixId ?: return
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { playmixManager.removeSongFromPlaymix(playmixId, playmixSongId) }
                onSuccess()
                refreshDetail(playmixId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Error al eliminar canción: ${e.message}"
            }
        }
    }

    fun reorderSongs(orderedSongIds: List<String>) {
        val playmixId = currentPlaymixId ?: return
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { playmixManager.reorderSongs(playmixId, orderedSongIds) }
                refreshDetail(playmixId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Error al reordenar: ${e.message}"
            }
        }
    }

    fun deletePlaymix(onSuccess: () -> Unit, onError: (String) -> Unit) {
        val playmixId = currentPlaymixId ?: return
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { playmixManager.deletePlaymix(playmixId) }
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PlaymixDetailVM", "Error deleting playmix", e)
                onError(e.message ?: "Error desconocido al borrar")
            }
        }
    }

    fun renamePlaymix(newName: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val playmixId = currentPlaymixId ?: return
        viewModelScope.launch {
            try {
                val updated = withContext(ioDispatcher) { playmixManager.renamePlaymix(playmixId, newName) }
                _screenState.value = _screenState.value?.copy(detail = updated)
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PlaymixDetailVM", "Error renaming playmix", e)
                onError(e.message ?: "Error desconocido al renombrar")
            }
        }
    }

    // Pair-based: transitions between pairs (0-1, 2-3, 4-5, ...)
    fun getTransitionForPair(pairIndex: Int): PlaymixTransitionDTO? {
        val detail = _screenState.value?.detail ?: return null
        val songs = detail.songs.sortedBy { it.position }
        val transitions = detail.transitions
        val firstIdx = pairIndex * 2
        val fromSong = songs.getOrNull(firstIdx) ?: return null
        val toSong = songs.getOrNull(firstIdx + 1) ?: return null
        return transitions.find {
            it.fromPlaymixSongId == fromSong.playmixSongId &&
                    it.toPlaymixSongId == toSong.playmixSongId
        }
    }

    /**
     * Cola para reproducir: las canciones salen del detalle ya cargado ([PlaymixSongMapper]), sin pedir cada una
     * al servidor (antes, 2 peticiones por canción antes de sonar). Descargada y sin red: lo guardado.
     */
    suspend fun fetchSongsForPlayback(startSongId: String? = null): Pair<List<Song>, Int>? {
        val detail = _screenState.value?.detail ?: return null
        val sortedPlaymixSongs = detail.songs.sortedBy { it.position }
        if (sortedPlaymixSongs.isEmpty()) return null

        // Descargada y sin red: canciones y análisis guardados, sin pedir nada al servidor (collection-queue-offline D5).
        offlineContext?.let { ctx ->
            if (!OfflineCollectionStore.isOnline(ctx)) {
                val offline = withContext(ioDispatcher) { OfflineCollectionStore.playmixSongs(ctx, detail.id) }
                if (!offline.isNullOrEmpty()) {
                    val startIdx = startSongId?.let { id -> offline.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0
                    return Pair(offline, startIdx)
                }
            }
        }

        val songs = PlaymixSongMapper.toSongs(sortedPlaymixSongs)
        val startIdx = startSongId?.let { id -> songs.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0
        return Pair(songs, startIdx)
    }
}

private const val LOAD_ERROR_MESSAGE = "No se pudieron cargar los datos."

class PlaymixDetailViewModelFactory(private val playmixManager: PlaymixManager) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PlaymixDetailViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PlaymixDetailViewModel(playmixManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
