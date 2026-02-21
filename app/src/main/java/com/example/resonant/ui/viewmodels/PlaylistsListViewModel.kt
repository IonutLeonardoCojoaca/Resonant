package com.example.resonant.ui.viewmodels

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.models.Playlist
import com.example.resonant.managers.PlaylistManager
import kotlinx.coroutines.launch

class PlaylistsListViewModel(private val playlistManager: PlaylistManager) : ViewModel() {

    private val _playlists = MutableLiveData<List<Playlist>>()
    val playlists: LiveData<List<Playlist>> get() = _playlists

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> get() = _error

    private val _playlistCreated = MutableLiveData<Boolean>(false)
    val playlistCreated: LiveData<Boolean> get() = _playlistCreated

    fun loadMyPlaylists() {
        viewModelScope.launch {
            try {
                val pls = playlistManager.getMyPlaylists()
                _playlists.postValue(pls)
            } catch (e: Exception) {
                _error.postValue("Error al obtener las playlists: ${e.message}")
                _playlists.postValue(emptyList())
            }
        }
    }

    fun refreshPlaylists() {
        loadMyPlaylists()
    }

    fun deletePlaylist(playlistId: String) {
        val originalList = _playlists.value
        if (originalList == null) {
            _error.postValue("No se pudo borrar, la lista no estaba cargada.")
            return
        }

        val updatedList = originalList.filterNot { it.id == playlistId }
        _playlists.postValue(updatedList)

        viewModelScope.launch {
            try {
                playlistManager.deletePlaylist(playlistId)
            } catch (e: Exception) {
                Log.i("Exception playlist", e.toString())
                _error.postValue("Error al borrar la playlist. Se ha restaurado.")
                _playlists.postValue(originalList)
            }
        }
    }

    fun createPlaylist(playlist: Playlist) {
        viewModelScope.launch {
            try {
                playlistManager.createPlaylist(playlist)
                _playlistCreated.postValue(true)
                refreshPlaylists()
            } catch (e: Exception) {
                _error.postValue("Error al crear la playlist: ${e.message}")
            }
        }
    }

    fun toggleVisibility(
        playlistId: String,
        currentIsPublic: Boolean,
        onSuccess: (newIsPublic: Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        val newVisibility = !currentIsPublic
        viewModelScope.launch {
            try {
                playlistManager.updatePlaylistVisibility(playlistId, newVisibility)
                // Optimistic local update: flip isPublic on the matching item
                val updated = _playlists.value?.map { pl ->
                    if (pl.id == playlistId) pl.copy(isPublic = newVisibility) else pl
                }
                _playlists.postValue(updated ?: emptyList())
                onSuccess(newVisibility)
            } catch (e: Exception) {
                onError(e.message ?: "Error al cambiar visibilidad")
            }
        }
    }

    fun onPlaylistCreationHandled() {
        _playlistCreated.value = false
        _error.value = null
    }
}

class PlaylistsListViewModelFactory(private val playlistManager: PlaylistManager) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PlaylistsListViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PlaylistsListViewModel(playlistManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}