package com.example.resonant.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.ArtistStatsDTO
import com.example.resonant.data.models.Song
// [CAMBIO] Ya no necesitamos ApiClient aquí, el Manager se encarga
// import com.example.resonant.data.network.ApiClient
import com.example.resonant.managers.ArtistManager // [CAMBIO] Importamos el Singleton
import com.example.resonant.managers.GenreManager
import com.example.resonant.managers.SongManager
import com.example.resonant.data.models.Genre
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

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

    private val _singles = MutableLiveData<List<Song>>()
    val singles: LiveData<List<Song>> get() = _singles

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> get() = _errorMessage

    private val _artistStats = MutableLiveData<ArtistStatsDTO?>()
    val artistStats: LiveData<ArtistStatsDTO?> get() = _artistStats

    private val _collaborators = MutableLiveData<List<Artist>>()
    val collaborators: LiveData<List<Artist>> get() = _collaborators

    private var currentArtistId: String? = null
    private var loadJob: kotlinx.coroutines.Job? = null
    private var loadedAtMs = 0L

    fun loadData(artistId: String) {
        // Mismo artista recién cargado (vuelta desde otra pantalla): no se relanzan las 7 peticiones.
        if (artistId == currentArtistId && _artist.value != null &&
            System.currentTimeMillis() - loadedAtMs < RELOAD_TTL_MS
        ) return
        if (artistId != currentArtistId) loadJob?.cancel()
        currentArtistId = artistId

        if (_artist.value == null) {
            _isLoading.value = true
        }

        loadJob = viewModelScope.launch {
            try {
                // --- FASE 1: Datos críticos (artista + álbumes + top songs) ---
                // ArtistManager ya lanza estas 3 llamadas en paralelo internamente.
                // Publicamos en la UI en cuanto llegan para que el usuario vea contenido rápido.
                val (artistObj, albumsList, songsList) = ArtistManager.getFullArtistData(
                    getApplication(), artistId, songManager
                )
                val artistNameStr = artistObj.name ?: "Desconocido"

                albumsList.forEach { it.artistName = artistNameStr }
                songsList.forEach { if (it.artistName.isNullOrEmpty()) it.artistName = artistNameStr }

                // Publicar inmediatamente → el usuario ve cabecera y canciones sin esperar Phase 2
                loadedAtMs = System.currentTimeMillis()
                _artist.value = artistObj
                _topSongs.value = songsList
                _isLoading.value = false

                val sortedAlbums = albumsList.sortedByDescending { it.releaseYear ?: 0 }
                if (sortedAlbums.isNotEmpty()) {
                    _featuredAlbum.value = listOf(sortedAlbums.first())
                    _normalAlbums.value = sortedAlbums.drop(1)
                } else {
                    _featuredAlbum.value = emptyList()
                    _normalAlbums.value = emptyList()
                }

                // --- FASE 2: Contenido secundario en paralelo ---
                // Cada sección publica en la UI en cuanto su llamada termina,
                // de forma independiente. Un fallo en una no afecta a las demás.
                supervisorScope {
                    launch {
                        val playlists = ArtistManager.getArtistSmartPlaylists(getApplication(), artistId)
                        _artistPlaylists.postValue(playlists)
                    }
                    launch {
                        val images = ArtistManager.getArtistImages(getApplication(), artistId)
                        _artistImages.postValue(images)
                    }
                    launch {
                        val singles = ArtistManager.getArtistSingles(getApplication(), artistId)
                        singles.forEach { if (it.artistName.isNullOrEmpty()) it.artistName = artistNameStr }
                        _singles.postValue(singles)
                    }
                    launch {
                        val genres = genreManager.getGenresByArtistId(artistId)
                        _artistGenres.postValue(genres)
                    }
                    launch {
                        val stats = ArtistManager.getArtistStats(getApplication(), artistId)
                        _artistStats.postValue(stats)
                    }
                    launch {
                        val collabs = ArtistManager.getArtistCollaborators(getApplication(), artistId)
                        _collaborators.postValue(collabs)
                    }
                }

            } catch (e: Exception) {
                _errorMessage.value = "Error al cargar datos: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private companion object {
        /** Ventana en la que volver a la ficha no vuelve a pedir nada (las cachés de ArtistManager duran 20 min). */
        const val RELOAD_TTL_MS = 5 * 60 * 1000L
    }
}
