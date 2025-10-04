package com.example.resonant

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class PlaylistsListViewModel(private val playlistManager: PlaylistManager) : ViewModel() {

    private val _playlists = MutableLiveData<List<Playlist>>()
    val playlists: LiveData<List<Playlist>> get() = _playlists

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> get() = _error

    // Lo mantenemos para el flujo de creación
    private val _playlistCreated = MutableLiveData<Boolean>(false)
    val playlistCreated: LiveData<Boolean> get() = _playlistCreated

    // Guardamos el userId para poder refrescar fácilmente
    private var currentUserId: String? = null

    // ...
    fun getPlaylistsByUserId(userId: String) {
        currentUserId = userId
        refreshPlaylists() // Usamos la nueva función de refresco
    }

    /**
     * Nueva función centralizada para refrescar la lista.
     * Puede ser llamada desde el fragment cuando sea necesario.
     */
    fun refreshPlaylists() {
        val userId = currentUserId ?: return // No hagas nada si no hay un usuario
        viewModelScope.launch {
            try {
                val pls = playlistManager.getPlaylistByUserId(userId)
                _playlists.postValue(pls)
            } catch (e: Exception) {
                _error.postValue("Error al obtener las playlists: ${e.message}")
                _playlists.postValue(emptyList())
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            try {
                playlistManager.deletePlaylist(playlistId)
                // ¡Actualización optimista! Eliminamos la playlist de la lista local
                // para que la UI se actualice al instante.
                val updatedList = _playlists.value?.filterNot { it.id == playlistId }
                _playlists.postValue(updatedList)
            } catch (e: Exception) {
                _error.postValue("Error al borrar la playlist: ${e.message}")
            }
        }
    }

    fun createPlaylist(playlist: Playlist) {
        viewModelScope.launch {
            try {
                playlistManager.createPlaylist(playlist)
                _playlistCreated.postValue(true)
                // Después de crear, refrescamos toda la lista para obtener el nuevo item
                refreshPlaylists()
            } catch (e: Exception) {
                _error.postValue("Error al crear la playlist: ${e.message}")
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