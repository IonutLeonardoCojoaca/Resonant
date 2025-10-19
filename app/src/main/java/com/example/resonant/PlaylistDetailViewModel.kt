package com.example.resonant

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaylistScreenState(
    val isLoading: Boolean = true,
    val playlistDetails: Playlist? = null,
    val songs: List<Song> = emptyList(),
    val ownerName: String = "",
    val collageBitmaps: List<Bitmap?> = emptyList(),
    val error: String? = null,
    val currentPlayingSongId: String? = null // ¡Añadir este campo!
)

class PlaylistDetailViewModel(private val playlistManager: PlaylistManager) : ViewModel() {

    private val _screenState = MutableLiveData<PlaylistScreenState>(PlaylistScreenState())
    val screenState: LiveData<PlaylistScreenState> get() = _screenState

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> get() = _error

    private var currentPlaylistId: String? = null

    private data class RefreshedPlaylistData(
        val playlist: Playlist,
        val songs: List<Song>,
        val ownerName: String,
        val collageBitmaps: List<Bitmap?>
    )

    fun loadPlaylistScreenData(playlistId: String, context: Context) {
        // Evita recargas innecesarias si ya se cargó o se está cargando
        if (playlistId == currentPlaylistId && !_screenState.value!!.isLoading) {
            return
        }
        currentPlaylistId = playlistId

        // CORRECCIÓN: No emitas un nuevo estado aquí. `refreshPlaylistData` se encargará
        // de mostrar el loader y el resultado final. Simplemente lánzalo.
        refreshPlaylistData(playlistId, context, showLoading = true)
    }

    // El showLoading es para diferenciar la carga inicial de una actualización en segundo plano
    private fun refreshPlaylistData(playlistId: String, context: Context, showLoading: Boolean = false) {
        if (showLoading) {
            _screenState.value = _screenState.value?.copy(isLoading = true, error = null)
        }

        viewModelScope.launch {
            try {
                val refreshedData = withContext(Dispatchers.IO) {
                    val p = playlistManager.getPlaylistById(playlistId)
                    val s = playlistManager.getSongsByPlaylistId(context, playlistId)
                    val owner = p.userId?.let {
                        try { playlistManager.getUserById(it).name ?: "" } catch (_: Exception) { "" }
                    } ?: ""

                    val service = ApiClient.getService(context)
                    val coverUrls = getCoverUrlsForSongs(s.take(4), service)
                    val bitmaps = getBitmapsWithGlide(context, coverUrls)

                    RefreshedPlaylistData(p, s, owner, bitmaps)
                }

                // Actualización atómica en el hilo principal.
                _screenState.postValue(
                    _screenState.value?.copy(
                        isLoading = false,
                        playlistDetails = refreshedData.playlist,
                        songs = refreshedData.songs,
                        ownerName = refreshedData.ownerName,
                        collageBitmaps = refreshedData.collageBitmaps,
                        error = null
                    ) ?: PlaylistScreenState() // Fallback por si acaso
                )

            } catch (e: Exception) {
                Log.e("PlaylistDetailVM", "Error refrescando datos", e)
                _screenState.postValue(
                    _screenState.value?.copy(isLoading = false, error = "No se pudieron cargar los datos.")
                )
            }
        }
    }

    suspend fun checkSongInPlaylist(songId: String, playlistId: String): Boolean {
        return try {
            withContext(Dispatchers.IO) { playlistManager.isSongInPlaylist(songId, playlistId) }
        } catch (e: Exception) {
            _error.postValue("Error comprobando canción en playlist: ${e.message}")
            false
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

    fun addSongToPlaylist(songId: String, playlistId: String, context: Context) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { playlistManager.addSongToPlaylist(songId, playlistId) }
                loadPlaylistScreenData(playlistId, context)
            } catch (e: Exception) {
                _error.postValue(e.message)
            }
        }
    }

    private fun notifySongMarkedForDeletion(context: Context, playlistId: String, songId: String) {
        val intent = Intent(context, MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_SONG_MARKED_FOR_DELETION
            putExtra(MusicPlaybackService.EXTRA_PLAYLIST_ID, playlistId)
            putExtra(MusicPlaybackService.EXTRA_SONG_ID, songId)
        }
        context.startService(intent)
    }

    // 3. La función de eliminar ahora es MUCHO más simple.
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
                // 2. Recarga los datos de esta pantalla.
                refreshPlaylistData(playlistId, context)

            } catch (e: Exception) {
                Log.e("PlaylistDetailVM", "Error al eliminar canción", e)
                _error.postValue("Error al eliminar canción: ${e.message}")
            }
        }
    }

    // ✅ AÑADE ESTA NUEVA FUNCIÓN, MUCHO MÁS SIMPLE
    private fun notifyPlaylistChange(context: Context, playlistId: String) {
        val intent = Intent(context, MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_PLAYLIST_MODIFIED
            putExtra(MusicPlaybackService.EXTRA_PLAYLIST_ID, playlistId)
        }
        context.startService(intent)
    }

    private suspend fun getCoverUrlsForSongs(songs: List<Song>, service: ApiResonantService): List<String> {
        val coversRequest = songs.mapNotNull { song ->
            song.imageFileName?.takeIf { it.isNotBlank() }?.let { fn ->
                song.albumId.takeIf { it.isNotBlank() }?.let { aid -> fn to aid }
            }
        }
        if (coversRequest.isNotEmpty()) {
            val (fileNames, albumIds) = coversRequest.unzip()
            val coverResponses = withContext(Dispatchers.IO) {
                service.getMultipleSongCoverUrls(fileNames, albumIds)
            }
            val coverMap = coverResponses.associateBy({ it.imageFileName to it.albumId }, { it.url })
            songs.forEach { song ->
                song.coverUrl = coverMap[song.imageFileName to song.albumId]
            }
        }
        return songs.mapNotNull { it.coverUrl }
    }

    private suspend fun getBitmapsWithGlide(context: Context, urls: List<String>): List<Bitmap?> {
        val bitmaps = MutableList<Bitmap?>(4) { null }
        coroutineScope {
            urls.take(4).forEachIndexed { index, url ->
                launch(Dispatchers.IO) {
                    try {
                        bitmaps[index] = Glide.with(context)
                            .asBitmap()
                            .load(url)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .submit()
                            .get()
                    } catch (e: Exception) {
                        Log.e("PlaylistDetailVM", "Fallo al descargar imagen: $url", e)
                    }
                }
            }
        }
        return bitmaps
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