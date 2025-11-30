package com.example.resonant.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.Song
import com.example.resonant.managers.AlbumManager
import com.example.resonant.managers.ArtistManager
import com.example.resonant.managers.SongManager
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    // Instancias de Managers
    private val songManager = SongManager(application)
    // Asumiendo que has convertido ArtistManager y AlbumManager en 'object' (Singleton)
    // Si no, usa: private val artistManager = ArtistManager(application)

    // --- SECCIÓN CANCIONES ---
    private val _songs = MutableLiveData<List<Song>>()
    val songs: LiveData<List<Song>> get() = _songs
    private val _songsTitle = MutableLiveData<String>()
    val songsTitle: LiveData<String> get() = _songsTitle
    private val _songsLoading = MutableLiveData<Boolean>()
    val songsLoading: LiveData<Boolean> get() = _songsLoading
    private val _songsError = MutableLiveData<String?>()
    val songsError: LiveData<String?> get() = _songsError

    // --- SECCIÓN ARTISTAS ---
    private val _artists = MutableLiveData<List<Artist>>()
    val artists: LiveData<List<Artist>> get() = _artists
    private val _artistsTitle = MutableLiveData<String>()
    val artistsTitle: LiveData<String> get() = _artistsTitle
    private val _artistsLoading = MutableLiveData<Boolean>()
    val artistsLoading: LiveData<Boolean> get() = _artistsLoading
    private val _artistsError = MutableLiveData<String?>()
    val artistsError: LiveData<String?> get() = _artistsError

    // --- SECCIÓN ÁLBUMES ---
    private val _albums = MutableLiveData<List<Album>>()
    val albums: LiveData<List<Album>> get() = _albums
    private val _albumsTitle = MutableLiveData<String>()
    val albumsTitle: LiveData<String> get() = _albumsTitle
    private val _albumsLoading = MutableLiveData<Boolean>()
    val albumsLoading: LiveData<Boolean> get() = _albumsLoading
    private val _albumsError = MutableLiveData<String?>()
    val albumsError: LiveData<String?> get() = _albumsError


    // --- FUNCIONES DE CARGA ---

    fun loadSongs(userId: String) {
        // CACHÉ: Si ya hay canciones cargadas, no hacemos nada
        if (_songs.value != null) return

        _songsLoading.value = true
        _songsError.value = null

        viewModelScope.launch {
            try {
                // Llamada al Manager
                val result = songManager.getRecommendedSongs(userId, count = 7)

                if (result != null && result.items.isNotEmpty()) {
                    _songsTitle.value = result.title
                    _songs.value = result.items
                } else {
                    _songsError.value = "No se encontraron canciones"
                }
            } catch (e: Exception) {
                _songsError.value = "Error de conexión"
            } finally {
                _songsLoading.value = false
            }
        }
    }

    fun loadArtists(userId: String) {
        if (_artists.value != null) return // CACHÉ

        _artistsLoading.value = true
        _artistsError.value = null

        viewModelScope.launch {
            try {
                // Usamos el Manager Singleton o instanciado
                val result = ArtistManager.getRecommendedArtists(getApplication(), userId, count = 6)

                if (result != null && result.items.isNotEmpty()) {
                    _artistsTitle.value = result.title
                    _artists.value = result.items
                } else {
                    _artistsError.value = "No se encontraron artistas"
                }
            } catch (e: Exception) {
                _artistsError.value = "Error al cargar artistas"
            } finally {
                _artistsLoading.value = false
            }
        }
    }

    fun loadAlbums(userId: String) {
        if (_albums.value != null) return // CACHÉ

        _albumsLoading.value = true
        _albumsError.value = null

        viewModelScope.launch {
            try {
                // Suponiendo que AlbumManager es similar a ArtistManager
                val result = AlbumManager.getRecommendedAlbums(getApplication(), userId, count = 3)

                if (result != null && result.items.isNotEmpty()) {
                    _albumsTitle.value = result.title
                    _albums.value = result.items
                } else {
                    _albumsError.value = "No se encontraron álbumes"
                }
            } catch (e: Exception) {
                _albumsError.value = "Error al cargar álbumes"
            } finally {
                _albumsLoading.value = false
            }
        }
    }
}