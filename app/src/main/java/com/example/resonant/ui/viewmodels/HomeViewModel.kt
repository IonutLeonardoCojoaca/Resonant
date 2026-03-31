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
    private var lastRecentArtistsFetchTime: Long = 0
    private var lastRecentAlbumsFetchTime: Long = 0
    private var lastTopSongsFetchTime: Long = 0
    private var lastTopArtistsFetchTime: Long = 0
    private var lastTopAlbumsFetchTime: Long = 0

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

    // --- SECCIÓN ARTISTAS RECIENTEMENTE AÑADIDOS ---
    private val _recentArtists = MutableLiveData<List<Artist>>()
    val recentArtists: LiveData<List<Artist>> get() = _recentArtists
    private val _recentArtistsLoading = MutableLiveData<Boolean>()
    val recentArtistsLoading: LiveData<Boolean> get() = _recentArtistsLoading
    private val _recentArtistsError = MutableLiveData<String?>()
    val recentArtistsError: LiveData<String?> get() = _recentArtistsError

    // --- SECCIÓN ÁLBUMES RECIENTEMENTE AÑADIDOS ---
    private val _recentAlbums = MutableLiveData<List<Album>>()
    val recentAlbums: LiveData<List<Album>> get() = _recentAlbums
    private val _recentAlbumsLoading = MutableLiveData<Boolean>()
    val recentAlbumsLoading: LiveData<Boolean> get() = _recentAlbumsLoading
    private val _recentAlbumsError = MutableLiveData<String?>()
    val recentAlbumsError: LiveData<String?> get() = _recentAlbumsError

    // --- SECCIÓN TUS CANCIONES MÁS ESCUCHADAS ---
    private val _topSongs = MutableLiveData<List<Song>>()
    val topSongs: LiveData<List<Song>> get() = _topSongs
    private val _topSongsLoading = MutableLiveData<Boolean>()
    val topSongsLoading: LiveData<Boolean> get() = _topSongsLoading
    private val _topSongsError = MutableLiveData<String?>()
    val topSongsError: LiveData<String?> get() = _topSongsError

    // --- SECCIÓN TUS ARTISTAS MÁS ESCUCHADOS ---
    private val _topArtists = MutableLiveData<List<Artist>>()
    val topArtists: LiveData<List<Artist>> get() = _topArtists
    private val _topArtistsLoading = MutableLiveData<Boolean>()
    val topArtistsLoading: LiveData<Boolean> get() = _topArtistsLoading
    private val _topArtistsError = MutableLiveData<String?>()
    val topArtistsError: LiveData<String?> get() = _topArtistsError

    // --- SECCIÓN TUS ÁLBUMES MÁS ESCUCHADOS ---
    private val _topAlbums = MutableLiveData<List<Album>>()
    val topAlbums: LiveData<List<Album>> get() = _topAlbums
    private val _topAlbumsLoading = MutableLiveData<Boolean>()
    val topAlbumsLoading: LiveData<Boolean> get() = _topAlbumsLoading
    private val _topAlbumsError = MutableLiveData<String?>()
    val topAlbumsError: LiveData<String?> get() = _topAlbumsError


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
                val songs = songManager.getPlaybackHistory(limit = 8)

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

    fun loadRecentArtists(forceRefresh: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        val isExpired = (currentTime - lastRecentArtistsFetchTime) > DATA_EXPIRATION_TIME
        val hasData = _recentArtists.value != null

        if (hasData && !forceRefresh && !isExpired) return

        if (!hasData) _recentArtistsLoading.value = true
        _recentArtistsError.value = null

        viewModelScope.launch {
            try {
                val result = ArtistManager.getRecentlyAddedArtists(getApplication(), limit = 20)
                _recentArtists.value = result
                if (result.isNotEmpty()) lastRecentArtistsFetchTime = System.currentTimeMillis()
                else if (!hasData) _recentArtistsError.value = "No hay artistas recientes"
            } catch (e: Exception) {
                if (!hasData) _recentArtistsError.value = "Error al cargar artistas recientes"
            } finally {
                _recentArtistsLoading.value = false
            }
        }
    }

    fun loadRecentAlbums(forceRefresh: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        val isExpired = (currentTime - lastRecentAlbumsFetchTime) > DATA_EXPIRATION_TIME
        val hasData = _recentAlbums.value != null

        if (hasData && !forceRefresh && !isExpired) return

        if (!hasData) _recentAlbumsLoading.value = true
        _recentAlbumsError.value = null

        viewModelScope.launch {
            try {
                val result = AlbumManager.getNewReleaseAlbums(getApplication(), limit = 20)
                _recentAlbums.value = result
                if (result.isNotEmpty()) lastRecentAlbumsFetchTime = System.currentTimeMillis()
                else if (!hasData) _recentAlbumsError.value = "No hay álbumes recientes"
            } catch (e: Exception) {
                if (!hasData) _recentAlbumsError.value = "Error al cargar álbumes recientes"
            } finally {
                _recentAlbumsLoading.value = false
            }
        }
    }

    fun loadTopSongs(forceRefresh: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        val isExpired = (currentTime - lastTopSongsFetchTime) > DATA_EXPIRATION_TIME
        val hasData = _topSongs.value != null

        if (hasData && !forceRefresh && !isExpired) return

        if (!hasData) _topSongsLoading.value = true
        _topSongsError.value = null

        viewModelScope.launch {
            try {
                val result = songManager.getMostListenedSongs(limit = 20)
                _topSongs.value = result
                if (result.isNotEmpty()) lastTopSongsFetchTime = System.currentTimeMillis()
                else if (!hasData) _topSongsError.value = "No hay canciones escuchadas"
            } catch (e: Exception) {
                if (!hasData) _topSongsError.value = "Error al cargar tus más escuchadas"
            } finally {
                _topSongsLoading.value = false
            }
        }
    }

    fun loadTopArtists(forceRefresh: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        val isExpired = (currentTime - lastTopArtistsFetchTime) > DATA_EXPIRATION_TIME
        val hasData = _topArtists.value != null

        if (hasData && !forceRefresh && !isExpired) return

        if (!hasData) _topArtistsLoading.value = true
        _topArtistsError.value = null

        viewModelScope.launch {
            try {
                val result = ArtistManager.getMostListenedArtists(getApplication(), limit = 20)
                _topArtists.value = result
                if (result.isNotEmpty()) lastTopArtistsFetchTime = System.currentTimeMillis()
                else if (!hasData) _topArtistsError.value = "No hay artistas escuchados"
            } catch (e: Exception) {
                if (!hasData) _topArtistsError.value = "Error al cargar tus artistas más escuchados"
            } finally {
                _topArtistsLoading.value = false
            }
        }
    }

    fun loadTopAlbums(forceRefresh: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        val isExpired = (currentTime - lastTopAlbumsFetchTime) > DATA_EXPIRATION_TIME
        val hasData = _topAlbums.value != null

        if (hasData && !forceRefresh && !isExpired) return

        if (!hasData) _topAlbumsLoading.value = true
        _topAlbumsError.value = null

        viewModelScope.launch {
            try {
                val result = AlbumManager.getMostListenedAlbums(getApplication(), limit = 20)
                _topAlbums.value = result
                if (result.isNotEmpty()) lastTopAlbumsFetchTime = System.currentTimeMillis()
                else if (!hasData) _topAlbumsError.value = "No hay álbumes escuchados"
            } catch (e: Exception) {
                if (!hasData) _topAlbumsError.value = "Error al cargar tus álbumes más escuchados"
            } finally {
                _topAlbumsLoading.value = false
            }
        }
    }

}