package com.example.resonant.ui.viewmodels

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.PlaymixDetailDTO
import com.example.resonant.data.network.PlaymixSongDTO
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.managers.PlaymixManager
import com.example.resonant.managers.SongManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaymixDetailScreenState(
    val isLoading: Boolean = true,
    val detail: PlaymixDetailDTO? = null,
    val error: String? = null
)

class PlaymixDetailViewModel(private val playmixManager: PlaymixManager) : ViewModel() {

    private val _screenState = MutableLiveData(PlaymixDetailScreenState())
    val screenState: LiveData<PlaymixDetailScreenState> get() = _screenState

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> get() = _error

    private var currentPlaymixId: String? = null

    fun loadPlaymixDetail(playmixId: String, forceReload: Boolean = false) {
        if (playmixId == currentPlaymixId && !forceReload && _screenState.value?.isLoading == false) {
            return
        }
        currentPlaymixId = playmixId
        refreshDetail(playmixId, showLoading = true)
    }

    private fun refreshDetail(playmixId: String, showLoading: Boolean = false) {
        if (showLoading) {
            _screenState.value = _screenState.value?.copy(isLoading = true, error = null)
        }

        viewModelScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) {
                    playmixManager.getPlaymixDetail(playmixId)
                }
                _screenState.postValue(
                    PlaymixDetailScreenState(
                        isLoading = false,
                        detail = detail,
                        error = null
                    )
                )
            } catch (e: Exception) {
                Log.e("PlaymixDetailVM", "Error loading detail", e)
                _screenState.postValue(
                    _screenState.value?.copy(isLoading = false, error = "No se pudieron cargar los datos.")
                )
            }
        }
    }

    fun addSongToPlaymix(songId: String) {
        val playmixId = currentPlaymixId ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    playmixManager.addSongToPlaymix(playmixId, songId)
                }
                refreshDetail(playmixId, showLoading = false)
            } catch (e: Exception) {
                _error.postValue("Error al añadir canción: ${e.message}")
            }
        }
    }

    fun removeSong(playmixSongId: String) {
        val playmixId = currentPlaymixId ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    playmixManager.removeSongFromPlaymix(playmixId, playmixSongId)
                }
                refreshDetail(playmixId, showLoading = false)
            } catch (e: Exception) {
                _error.postValue("Error al eliminar canción: ${e.message}")
            }
        }
    }

    fun reorderSongs(orderedSongIds: List<String>) {
        val playmixId = currentPlaymixId ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    playmixManager.reorderSongs(playmixId, orderedSongIds)
                }
                refreshDetail(playmixId, showLoading = false)
            } catch (e: Exception) {
                _error.postValue("Error al reordenar: ${e.message}")
            }
        }
    }

    fun deletePlaymix(onSuccess: () -> Unit, onError: (String) -> Unit) {
        val playmixId = currentPlaymixId ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    playmixManager.deletePlaymix(playmixId)
                }
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                Log.e("PlaymixDetailVM", "Error deleting playmix", e)
                withContext(Dispatchers.Main) { onError(e.message ?: "Error desconocido al borrar") }
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

    suspend fun fetchSongsForPlayback(
        songManager: SongManager,
        startSongId: String? = null
    ): Pair<List<Song>, Int>? {
        val detail = _screenState.value?.detail ?: return null
        val sortedPlaymixSongs = detail.songs.sortedBy { it.position }

        val indexedSongs: List<Pair<Int, Song>> = withContext(Dispatchers.IO) {
            sortedPlaymixSongs.mapIndexed { idx, ps ->
                async {
                    try {
                        val song = songManager.getSongById(ps.songId)
                        if (song != null) Pair(idx, song) else null
                    } catch (e: Exception) {
                        Log.e("PlaymixDetailVM", "Failed to fetch song ${ps.songId}", e)
                        null
                    }
                }
            }.mapNotNull { it.await() }
        }

        if (indexedSongs.isEmpty()) return null

        val songs = indexedSongs.map { it.second }
        val startIdx = if (startSongId != null) {
            val pmSongIdx = sortedPlaymixSongs.indexOfFirst { it.songId == startSongId }
            indexedSongs.indexOfFirst { it.first == pmSongIdx }.takeIf { it >= 0 } ?: 0
        } else {
            0
        }
        return Pair(songs, startIdx)
    }
}

class PlaymixDetailViewModelFactory(private val playmixManager: PlaymixManager) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PlaymixDetailViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PlaymixDetailViewModel(playmixManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
