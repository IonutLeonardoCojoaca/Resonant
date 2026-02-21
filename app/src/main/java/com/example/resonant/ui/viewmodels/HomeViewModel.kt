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

    private val songManager = SongManager(application)

    // --- CONFIGURACIÓN DE EXPIRACIÓN ---
    private val DATA_EXPIRATION_TIME = 15 * 60 * 1000L

    private var lastSongsFetchTime: Long = 0
    private var lastHistoryFetchTime: Long = 0
    private var lastArtistsFetchTime: Long = 0
    private var lastAlbumsFetchTime: Long = 0

    // --- SECCIÓN CANCIONES ---
    private val _songs = MutableLiveData<List<Song>>()
    val songs: LiveData<List<Song>> get() = _songs
    private val _songsTitle = MutableLiveData<String>()
    val songsTitle: LiveData<String> get() = _songsTitle
    private val _songsLoading = MutableLiveData<Boolean>()
    val songsLoading: LiveData<Boolean> get() = _songsLoading
    private val _songsError = MutableLiveData<String?>()
    val songsError: LiveData<String?> get() = _songsError

    // --- SECCIÓN HISTORIAL ---
    private val _history = MutableLiveData<List<Song>>()
    val history: LiveData<List<Song>> get() = _history
    private val _historyLoading = MutableLiveData<Boolean>()
    val historyLoading: LiveData<Boolean> get() = _historyLoading
    private val _historyError = MutableLiveData<String?>()
    val historyError: LiveData<String?> get() = _historyError

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


    fun loadSongs(forceRefresh: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        val isExpired = (currentTime - lastSongsFetchTime) > DATA_EXPIRATION_TIME
        val hasData = _songs.value != null

        if (hasData && !forceRefresh && !isExpired) return

        val showLoading = !hasData

        if (showLoading) _songsLoading.value = true
        _songsError.value = null

        viewModelScope.launch {
            try {
                val result = songManager.getRecommendedSongs(count = 15)

                if (result != null && result.items.isNotEmpty()) {
                    _songsTitle.value = result.title ?: "Recomendado para ti"
                    _songs.value = result.items
                    lastSongsFetchTime = System.currentTimeMillis()
                } else {
                    if (!hasData) _songsError.value = "No se encontraron canciones"
                }
            } catch (e: Exception) {
                if (!hasData) _songsError.value = "Error de conexión"
            } finally {
                _songsLoading.value = false
            }
        }
    }

    fun loadHistory(forceRefresh: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        val isExpired = (currentTime - lastHistoryFetchTime) > DATA_EXPIRATION_TIME
        val hasData = _history.value != null

        // Refresh always if forceRefresh is true, otherwise respect cache
        if (hasData && !forceRefresh && !isExpired) return

        val showLoading = !hasData
        if (showLoading) _historyLoading.value = true
        _historyError.value = null

        viewModelScope.launch {
            try {
                // Fetch from SongManager
                val songs = songManager.getPlaybackHistory(limit = 6)

                _history.value = songs
                if (songs.isNotEmpty()) {
                    lastHistoryFetchTime = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                if (!hasData) _historyError.value = "Error al cargar historial"
            } finally {
                _historyLoading.value = false
            }
        }
    }

    fun loadArtists(forceRefresh: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        val isExpired = (currentTime - lastArtistsFetchTime) > DATA_EXPIRATION_TIME
        val hasData = _artists.value != null

        if (hasData && !forceRefresh && !isExpired) return

        val showLoading = !hasData
        if (showLoading) _artistsLoading.value = true
        _artistsError.value = null

        viewModelScope.launch {
            try {
                val result = ArtistManager.getRecommendedArtists(getApplication(), count = 6)
                if (result != null && result.items.isNotEmpty()) {
                    _artistsTitle.value = result.title ?: "Recomendado para ti"
                    _artists.value = result.items
                    lastArtistsFetchTime = System.currentTimeMillis()
                } else {
                    if (!hasData) _artistsError.value = "No se encontraron artistas"
                }
            } catch (e: Exception) {
                if (!hasData) _artistsError.value = "Error al cargar artistas"
            } finally {
                _artistsLoading.value = false
            }
        }
    }

    fun loadAlbums(forceRefresh: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        val isExpired = (currentTime - lastAlbumsFetchTime) > DATA_EXPIRATION_TIME
        val hasData = _albums.value != null

        if (hasData && !forceRefresh && !isExpired) return

        val showLoading = !hasData
        if (showLoading) _albumsLoading.value = true
        _albumsError.value = null

        viewModelScope.launch {
            try {
                val result = AlbumManager.getRecommendedAlbums(getApplication(), count = 3)
                if (result != null && result.items.isNotEmpty()) {
                    _albumsTitle.value = result.title
                    _albums.value = result.items
                    lastAlbumsFetchTime = System.currentTimeMillis()
                } else {
                    if (!hasData) _albumsError.value = "No se encontraron álbumes"
                }
            } catch (e: Exception) {
                if (!hasData) _albumsError.value = "Error al cargar álbumes"
            } finally {
                _albumsLoading.value = false
            }
        }
    }

}