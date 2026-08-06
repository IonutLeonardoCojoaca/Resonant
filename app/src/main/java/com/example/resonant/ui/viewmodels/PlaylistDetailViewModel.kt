package com.example.resonant.ui.viewmodels

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.models.Playlist
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.PlaylistTrackDTO
import com.example.resonant.managers.PlaylistManager
import com.example.resonant.managers.PlaylistOrderConflictException
import com.example.resonant.services.MusicPlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaylistScreenState(
    val isLoading: Boolean = true,
    val playlistDetails: Playlist? = null,
    val songs: List<Song> = emptyList(),
    val tracks: List<PlaylistTrackDTO> = emptyList(),
    val ownerName: String = "",
    val error: String? = null,
    val currentPlayingSongId: String? = null,
    val canReorder: Boolean = false,
    val reorderMode: Boolean = false,
    val savingOrder: Boolean = false
)

class PlaylistDetailViewModel(
    private val playlistManager: PlaylistManager
) : ViewModel() {
    private val _screenState = MutableLiveData(PlaylistScreenState())
    val screenState: LiveData<PlaylistScreenState> = _screenState

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private var currentPlaylistId: String? = null
    private var originalOrder: List<PlaylistTrackDTO>? = null

    fun loadPlaylistScreenData(playlistId: String) {
        if (
            playlistId == currentPlaylistId &&
            _screenState.value?.isLoading == false
        ) {
            return
        }
        currentPlaylistId = playlistId
        refreshPlaylistData(playlistId, showLoading = true)
    }

    private fun refreshPlaylistData(
        playlistId: String,
        showLoading: Boolean = false
    ) {
        if (showLoading) {
            _screenState.value = _screenState.value?.copy(
                isLoading = true,
                error = null
            )
        }
        viewModelScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) {
                    playlistManager.getPlaylistDetail(playlistId)
                }
                _screenState.value = PlaylistScreenState(
                    isLoading = false,
                    playlistDetails = detail.playlist,
                    songs = detail.tracks.map(PlaylistTrackDTO::song),
                    tracks = detail.tracks,
                    ownerName = detail.ownerName,
                    canReorder = detail.supportsReorder &&
                        detail.playlist.canEdit == true
                )
            } catch (error: Exception) {
                Log.e(TAG, "Error cargando playlist $playlistId", error)
                _screenState.value = _screenState.value?.copy(
                    isLoading = false,
                    error = "No se pudieron cargar los datos."
                )
            }
        }
    }

    fun addSongToPlaylist(songId: String, playlistId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    playlistManager.addSongToPlaylist(songId, playlistId)
                }
                refreshPlaylistData(playlistId)
            } catch (error: Exception) {
                _error.value = error.message
            }
        }
    }

    suspend fun addSongToPlaylistAwait(songId: String, playlistId: String) {
        withContext(Dispatchers.IO) {
            playlistManager.addSongToPlaylist(songId, playlistId)
        }
        refreshPlaylistData(playlistId)
    }

    fun removeSongFromPlaylist(
        songId: String,
        playlistId: String,
        context: Context
    ) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    playlistManager.deleteSongFromPlaylist(songId, playlistId)
                }
                notifySongMarkedForDeletion(context, playlistId, songId)
                refreshPlaylistData(playlistId)
            } catch (error: Exception) {
                Log.e(TAG, "Error eliminando canción", error)
                _error.value = "Error al eliminar canción: ${error.message}"
            }
        }
    }

    suspend fun checkSongInPlaylist(songId: String, playlistId: String): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                playlistManager.isSongInPlaylist(songId, playlistId)
            }
        } catch (error: Exception) {
            _error.value = "Error comprobando canción: ${error.message}"
            false
        }
    }

    fun togglePlaylistSaved(
        onSuccess: (Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        val state = _screenState.value ?: return
        val originalPlaylist = state.playlistDetails ?: return
        val playlistId = originalPlaylist.id ?: return
        val nextSaved = !originalPlaylist.isSaved
        _screenState.value = state.copy(
            playlistDetails = originalPlaylist.copy(isSaved = nextSaved)
        )

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    playlistManager.setPlaylistSaved(playlistId, nextSaved)
                }
                onSuccess(nextSaved)
            } catch (error: Exception) {
                _screenState.value = _screenState.value?.copy(
                    playlistDetails = originalPlaylist
                )
                onError(error.message ?: "No se pudo actualizar la biblioteca")
            }
        }
    }

    fun beginReorder() {
        val state = _screenState.value ?: return
        if (!state.canReorder || state.tracks.size < 2) {
            _error.value = "El servidor todavía no permite reordenar esta playlist"
            return
        }
        originalOrder = state.tracks
        _screenState.value = state.copy(reorderMode = true, error = null)
    }

    fun previewTrackMove(fromPosition: Int, toPosition: Int): Boolean {
        val state = _screenState.value ?: return false
        if (
            !state.reorderMode ||
            fromPosition !in state.tracks.indices ||
            toPosition !in state.tracks.indices
        ) {
            return false
        }
        val reordered = state.tracks.toMutableList()
        val moved = reordered.removeAt(fromPosition)
        reordered.add(toPosition, moved)
        val normalized = reordered.mapIndexed { index, track ->
            track.copy(position = index)
        }
        _screenState.value = state.copy(
            tracks = normalized,
            songs = normalized.map(PlaylistTrackDTO::song)
        )
        return true
    }

    fun cancelReorder() {
        val state = _screenState.value ?: return
        val restored = originalOrder ?: state.tracks
        originalOrder = null
        _screenState.value = state.copy(
            tracks = restored,
            songs = restored.map(PlaylistTrackDTO::song),
            reorderMode = false,
            savingOrder = false
        )
    }

    fun saveReorderedTracks(
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val state = _screenState.value ?: return
        val playlist = state.playlistDetails ?: return
        val playlistId = playlist.id ?: return
        if (!state.reorderMode || state.savingOrder) return
        _screenState.value = state.copy(savingOrder = true)

        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    playlistManager.reorderPlaylistTracks(
                        playlistId = playlistId,
                        revision = playlist.revision.orEmpty(),
                        orderedTrackIds = state.tracks.map(
                            PlaylistTrackDTO::playlistTrackId
                        )
                    )
                }
                originalOrder = null
                _screenState.value = PlaylistScreenState(
                    isLoading = false,
                    playlistDetails = saved.playlist,
                    songs = saved.tracks.map(PlaylistTrackDTO::song),
                    tracks = saved.tracks,
                    ownerName = saved.ownerName,
                    canReorder = saved.supportsReorder &&
                        saved.playlist.canEdit == true
                )
                onSuccess()
            } catch (error: Exception) {
                val message = error.message ?: "No se pudo guardar el orden"
                if (error is PlaylistOrderConflictException) {
                    originalOrder = null
                    refreshPlaylistData(playlistId)
                } else {
                    cancelReorder()
                }
                onError(message)
            }
        }
    }

    fun deleteCurrentPlaylist(
        playlistId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    playlistManager.deletePlaylist(playlistId)
                }
                onSuccess()
            } catch (error: Exception) {
                Log.e(TAG, "Error eliminando playlist", error)
                onError(error.message ?: "Error desconocido al borrar")
            }
        }
    }

    fun toggleVisibility(
        playlistId: String,
        currentIsPublic: Boolean,
        onSuccess: (Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val newVisibility = !currentIsPublic
                withContext(Dispatchers.IO) {
                    playlistManager.updatePlaylistVisibility(
                        playlistId,
                        newVisibility
                    )
                }
                val updated = _screenState.value
                    ?.playlistDetails
                    ?.copy(isPublic = newVisibility)
                _screenState.value = _screenState.value?.copy(
                    playlistDetails = updated
                )
                onSuccess(newVisibility)
            } catch (error: Exception) {
                Log.e(TAG, "Error cambiando visibilidad", error)
                onError(error.message ?: "Error al cambiar visibilidad")
            }
        }
    }

    private fun notifySongMarkedForDeletion(
        context: Context,
        playlistId: String,
        songId: String
    ) {
        context.startService(
            Intent(context, MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_SONG_MARKED_FOR_DELETION
                putExtra(MusicPlaybackService.EXTRA_PLAYLIST_ID, playlistId)
                putExtra(MusicPlaybackService.EXTRA_SONG_ID, songId)
            }
        )
    }

    companion object {
        private const val TAG = "PlaylistDetailVM"
    }
}

class PlaylistDetailViewModelFactory(
    private val playlistManager: PlaylistManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PlaylistDetailViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PlaylistDetailViewModel(playlistManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
