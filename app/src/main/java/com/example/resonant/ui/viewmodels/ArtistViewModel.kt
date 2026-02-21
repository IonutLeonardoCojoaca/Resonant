package com.example.resonant.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.Song
// [CAMBIO] Ya no necesitamos ApiClient aquí, el Manager se encarga
// import com.example.resonant.data.network.ApiClient
import com.example.resonant.managers.ArtistManager // [CAMBIO] Importamos el Singleton
import com.example.resonant.managers.GenreManager
import com.example.resonant.managers.SongManager
import com.example.resonant.data.models.Genre
import kotlinx.coroutines.launch

import com.example.resonant.data.models.ArtistSmartPlaylist

class ArtistViewModel(application: Application) : AndroidViewModel(application) {

    private val songManager = SongManager(application)
    private val genreManager = GenreManager(application)

    // LiveData (IGUAL QUE ANTES)
    private val _artist = MutableLiveData<Artist?>()
    val artist: LiveData<Artist?> get() = _artist

    private val _featuredAlbum = MutableLiveData<List<Album>>()
    val featuredAlbum: LiveData<List<Album>> get() = _featuredAlbum

    private val _normalAlbums = MutableLiveData<List<Album>>()
    val normalAlbums: LiveData<List<Album>> get() = _normalAlbums

    private val _topSongs = MutableLiveData<List<Song>>()
    val topSongs: LiveData<List<Song>> get() = _topSongs

    private val _artistPlaylists = MutableLiveData<List<ArtistSmartPlaylist>>()
    val artistPlaylists: LiveData<List<ArtistSmartPlaylist>> get() = _artistPlaylists

    private val _artistImages = MutableLiveData<List<String>>()
    val artistImages: LiveData<List<String>> get() = _artistImages

    private val _artistGenres = MutableLiveData<List<Genre>>()
    val artistGenres: LiveData<List<Genre>> get() = _artistGenres

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> get() = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> get() = _errorMessage

    private var currentArtistId: String? = null

    fun loadData(artistId: String) {
        // [CAMBIO] Eliminamos el chequeo estricto para permitir que ArtistManager decida si refrescar.
        // Si el Manager devuelve datos cacheados, será instantáneo.
        
        currentArtistId = artistId
        
        // Solo mostramos loading si no tenemos nada (evitar parpadeo)
        if (_artist.value == null) {
            _isLoading.value = true
        }

        viewModelScope.launch {
            try {
                val result = ArtistManager.getFullArtistData(
                    getApplication(),
                    artistId,
                    songManager
                )

                // Fetch Playlists 
                val playlistsList = ArtistManager.getArtistSmartPlaylists(getApplication(), artistId)
                _artistPlaylists.value = playlistsList

                // Fetch Images (Gallery)
                val imagesList = ArtistManager.getArtistImages(getApplication(), artistId)
                _artistImages.value = imagesList

                // Fetch Genres
                val genresList = genreManager.getGenresByArtistId(artistId)
                _artistGenres.value = genresList

                val (artistObj, albumsList, songsList) = result
                val artistNameStr = artistObj.name ?: "Desconocido"

                // Asignar nombres si faltan
                albumsList.forEach { it.artistName = artistNameStr }
                songsList.forEach { if (it.artistName.isNullOrEmpty()) it.artistName = artistNameStr }

                // Separar Destacado vs Normal
                val sortedAlbums = albumsList.sortedByDescending { it.releaseYear ?: 0 }

                if (sortedAlbums.isNotEmpty()) {
                    _featuredAlbum.value = listOf(sortedAlbums.first())
                    _normalAlbums.value = sortedAlbums.drop(1)
                } else {
                    _featuredAlbum.value = emptyList()
                    _normalAlbums.value = emptyList()
                }

                // Publicar el resto de datos
                _artist.value = artistObj
                _topSongs.value = songsList

            } catch (e: Exception) {
                _errorMessage.value = "Error al cargar datos: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
