package com.example.resonant.ui.viewmodels

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.resonant.managers.PlaylistManager
import com.example.resonant.data.models.Playlist
import com.example.resonant.data.models.Song
import com.example.resonant.services.MusicPlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 1. Estado limpio: Ya no necesitamos Bitmaps aquí.
data class PlaylistScreenState(
    val isLoading: Boolean = true,
    val playlistDetails: Playlist? = null,
    val songs: List<Song> = emptyList(),
    val ownerName: String = "",
    val error: String? = null,
    val currentPlayingSongId: String? = null
)

class PlaylistDetailViewModel(private val playlistManager: PlaylistManager) : ViewModel() {

    private val _screenState = MutableLiveData<PlaylistScreenState>(PlaylistScreenState())
    val screenState: LiveData<PlaylistScreenState> get() = _screenState

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> get() = _error

    private var currentPlaylistId: String? = null

    // Helper interno simplificado
    private data class RefreshedPlaylistData(
        val playlist: Playlist,
        val songs: List<Song>,
        val ownerName: String
    )

    // 2. Ya no necesitamos 'Context' para cargar datos (porque no usamos Glide aquí)
    fun loadPlaylistScreenData(playlistId: String) {
        if (playlistId == currentPlaylistId && !_screenState.value!!.isLoading) {
            return
        }
        currentPlaylistId = playlistId
        refreshPlaylistData(playlistId, showLoading = true)
    }

    private fun refreshPlaylistData(playlistId: String, showLoading: Boolean = false) {
        if (showLoading) {
            _screenState.value = _screenState.value?.copy(isLoading = true, error = null)
        }

        viewModelScope.launch {
            try {
                val refreshedData = withContext(Dispatchers.IO) {
                    // A) Obtenemos la playlist actualizada (el Backend ya habrá generado la imageUrl)
                    val p = playlistManager.getPlaylistById(playlistId)

                    // B) Obtenemos las canciones
                    val s = playlistManager.getSongsByPlaylistId(playlistId)

                    // C) Obtenemos el nombre del dueño
                    val owner = p.userId?.let {
                        try { playlistManager.getUserById(it).name ?: "" } catch (_: Exception) { "" }
                    } ?: ""

                    RefreshedPlaylistData(p, s, owner)
                }

                _screenState.postValue(
                    _screenState.value?.copy(
                        isLoading = false,
                        playlistDetails = refreshedData.playlist, // Aquí viene la nueva URL
                        songs = refreshedData.songs,
                        ownerName = refreshedData.ownerName,
                        error = null
                    ) ?: PlaylistScreenState()
                )

            } catch (e: Exception) {
                Log.e("PlaylistDetailVM", "Error refrescando datos", e)
                _screenState.postValue(
                    _screenState.value?.copy(isLoading = false, error = "No se pudieron cargar los datos.")
                )
            }
        }
    }

    // --- Lógica de añadir/borrar canciones ---

    fun addSongToPlaylist(songId: String, playlistId: String) {
        viewModelScope.launch {
            try {
                // 1. Llamada a la API (El backend añade la canción y regenera la imagen)
                withContext(Dispatchers.IO) {
                    playlistManager.addSongToPlaylist(songId, playlistId)
                }

                // 2. Recargar datos para obtener la nueva imagen y la lista actualizada
                refreshPlaylistData(playlistId, showLoading = false)
            } catch (e: Exception) {
                _error.postValue(e.message)
            }
        }
    }

    fun removeSongFromPlaylist(
        songId: String,
        playlistId: String,
        context: Context // Contexto necesario solo para el Servicio de música
    ) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    playlistManager.deleteSongFromPlaylist(songId, playlistId)
                }

                // Notificar al servicio de música si se está reproduciendo
                notifySongMarkedForDeletion(context, playlistId, songId)

                // Recargar datos para obtener la nueva imagen
                refreshPlaylistData(playlistId, showLoading = false)

            } catch (e: Exception) {
                Log.e("PlaylistDetailVM", "Error al eliminar canción", e)
                _error.postValue("Error al eliminar canción: ${e.message}")
            }
        }
    }

    // --- Helpers ---

    private fun notifySongMarkedForDeletion(context: Context, playlistId: String, songId: String) {
        val intent = Intent(context, MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_SONG_MARKED_FOR_DELETION
            putExtra(MusicPlaybackService.EXTRA_PLAYLIST_ID, playlistId)
            putExtra(MusicPlaybackService.EXTRA_SONG_ID, songId)
        }
        context.startService(intent)
    }

    suspend fun checkSongInPlaylist(songId: String, playlistId: String): Boolean {
        return try {
            withContext(Dispatchers.IO) { playlistManager.isSongInPlaylist(songId, playlistId) }
        } catch (e: Exception) {
            _error.postValue("Error comprobando canción en playlist: ${e.message}")
            false
        }
    }

    fun deleteCurrentPlaylist(playlistId: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                // Usamos el mismo manager que ya tienes inyectado
                playlistManager.deletePlaylist(playlistId)

                // Volvemos al hilo principal para avisar a la vista
                withContext(Dispatchers.Main) {
                    onSuccess()
                }
            } catch (e: Exception) {
                Log.e("PlaylistDetailVM", "Error deleting playlist", e)
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Error desconocido al borrar")
                }
            }
        }
    }

    suspend fun getArtistsForSong(songId: String): String {
        return try {
            val artists = withContext(Dispatchers.IO) { playlistManager.getArtistsBySongId(songId) }
            artists.joinToString(", ") { it.name }
        } catch (e: Exception) {
            Log.e("PlaylistDetailVM", "Error obteniendo artistas para la canción $songId", e)
            ""
        }
    }
}

class PlaylistDetailViewModelFactory(private val playlistManager: PlaylistManager) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PlaylistDetailViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PlaylistDetailViewModel(playlistManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}