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
import com.example.resonant.data.network.HomeRepository
import com.example.resonant.data.network.HomeResponseDTO
import com.example.resonant.data.network.HomeSectionDTO
import com.example.resonant.data.network.HomeSectionClassifier
import com.example.resonant.data.network.HomeSectionItemDecoder
import com.example.resonant.data.network.HomeSectionKind
import android.util.Log
import com.example.resonant.managers.UserManager
import kotlinx.coroutines.launch
import retrofit2.HttpException

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val songManager = SongManager(application)
    private val homeRepository = HomeRepository(application)
    private val homeItemDecoder = HomeSectionItemDecoder()
    private var homeV2Loading = false
    private var lastHomeRefreshTime = 0L
    private var loadedOwnerId: String? = null

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
    private val _songs = MutableLiveData<List<Song>?>()
    val songs: LiveData<List<Song>?> get() = _songs
    private val _songsTitle = MutableLiveData<String>()
    val songsTitle: LiveData<String> get() = _songsTitle
    private val _songsLoading = MutableLiveData<Boolean>()
    val songsLoading: LiveData<Boolean> get() = _songsLoading
    private val _songsError = MutableLiveData<String?>()
    val songsError: LiveData<String?> get() = _songsError

    // --- SECCIÓN HISTORIAL ---
    private val _history = MutableLiveData<List<Song>?>()
    val history: LiveData<List<Song>?> get() = _history
    private val _historyLoading = MutableLiveData<Boolean>()
    val historyLoading: LiveData<Boolean> get() = _historyLoading
    private val _historyError = MutableLiveData<String?>()
    val historyError: LiveData<String?> get() = _historyError

    // --- SECCIÓN ARTISTAS ---
    private val _artists = MutableLiveData<List<Artist>?>()
    val artists: LiveData<List<Artist>?> get() = _artists
    private val _artistsTitle = MutableLiveData<String>()
    val artistsTitle: LiveData<String> get() = _artistsTitle
    private val _artistsLoading = MutableLiveData<Boolean>()
    val artistsLoading: LiveData<Boolean> get() = _artistsLoading
    private val _artistsError = MutableLiveData<String?>()
    val artistsError: LiveData<String?> get() = _artistsError

    // --- SECCIÓN ÁLBUMES ---
    private val _albums = MutableLiveData<List<Album>?>()
    val albums: LiveData<List<Album>?> get() = _albums
    private val _albumsTitle = MutableLiveData<String>()
    val albumsTitle: LiveData<String> get() = _albumsTitle
    private val _albumsLoading = MutableLiveData<Boolean>()
    val albumsLoading: LiveData<Boolean> get() = _albumsLoading
    private val _albumsError = MutableLiveData<String?>()
    val albumsError: LiveData<String?> get() = _albumsError

    // --- SECCIÓN ARTISTAS RECIENTEMENTE AÑADIDOS ---
    private val _recentArtists = MutableLiveData<List<Artist>?>()
    val recentArtists: LiveData<List<Artist>?> get() = _recentArtists
    private val _recentArtistsLoading = MutableLiveData<Boolean>()
    val recentArtistsLoading: LiveData<Boolean> get() = _recentArtistsLoading
    private val _recentArtistsError = MutableLiveData<String?>()
    val recentArtistsError: LiveData<String?> get() = _recentArtistsError

    // --- SECCIÓN ÁLBUMES RECIENTEMENTE AÑADIDOS ---
    private val _recentAlbums = MutableLiveData<List<Album>?>()
    val recentAlbums: LiveData<List<Album>?> get() = _recentAlbums
    private val _recentAlbumsLoading = MutableLiveData<Boolean>()
    val recentAlbumsLoading: LiveData<Boolean> get() = _recentAlbumsLoading
    private val _recentAlbumsError = MutableLiveData<String?>()
    val recentAlbumsError: LiveData<String?> get() = _recentAlbumsError

    // --- SECCIÓN TUS CANCIONES MÁS ESCUCHADAS ---
    private val _topSongs = MutableLiveData<List<Song>?>()
    val topSongs: LiveData<List<Song>?> get() = _topSongs
    private val _topSongsLoading = MutableLiveData<Boolean>()
    val topSongsLoading: LiveData<Boolean> get() = _topSongsLoading
    private val _topSongsError = MutableLiveData<String?>()
    val topSongsError: LiveData<String?> get() = _topSongsError

    // --- SECCIÓN TUS ARTISTAS MÁS ESCUCHADOS ---
    private val _topArtists = MutableLiveData<List<Artist>?>()
    val topArtists: LiveData<List<Artist>?> get() = _topArtists
    private val _topArtistsLoading = MutableLiveData<Boolean>()
    val topArtistsLoading: LiveData<Boolean> get() = _topArtistsLoading
    private val _topArtistsError = MutableLiveData<String?>()
    val topArtistsError: LiveData<String?> get() = _topArtistsError

    // --- SECCIÓN TUS ÁLBUMES MÁS ESCUCHADOS ---
    private val _topAlbums = MutableLiveData<List<Album>?>()
    val topAlbums: LiveData<List<Album>?> get() = _topAlbums
    private val _topAlbumsLoading = MutableLiveData<Boolean>()
    val topAlbumsLoading: LiveData<Boolean> get() = _topAlbumsLoading
    private val _topAlbumsError = MutableLiveData<String?>()
    val topAlbumsError: LiveData<String?> get() = _topAlbumsError

    fun loadHome(forceRefresh: Boolean = false) {
        if (homeV2Loading) return
        val ownerId = UserManager(getApplication()).getUserId()
        if (loadedOwnerId != null && loadedOwnerId != ownerId) {
            clearHomeForOwnerChange()
        }
        loadedOwnerId = ownerId

        val now = System.currentTimeMillis()
        val hasVisibleHome = hasAnyHomeData()
        if (
            !forceRefresh &&
            hasVisibleHome &&
            now - lastHomeRefreshTime < DATA_EXPIRATION_TIME
        ) {
            return
        }

        homeV2Loading = true
        markEmptySectionsLoading()

        viewModelScope.launch {
            var populatedFromCache = emptySet<HomeSectionKind>()
            if (!hasVisibleHome) {
                runCatching {
                    homeRepository.getCachedHome()
                }.getOrNull()?.let { cached ->
                    populatedFromCache = applyHomeResponse(cached)
                }
            }

            val populated = try {
                applyHomeResponse(homeRepository.getHome()).also {
                    lastHomeRefreshTime = System.currentTimeMillis()
                }
            } catch (error: Exception) {
                if (
                    error is UnsupportedOperationException ||
                    (error is HttpException && error.code() in setOf(404, 405, 501))
                ) {
                    Log.d("HomeVM", "Home v2 is not enabled; using legacy sections")
                } else {
                    Log.w(
                        "HomeVM",
                        "Home v2 unavailable; using legacy sections: ${error.message}"
                    )
                }
                populatedFromCache
            } finally {
                homeV2Loading = false
            }

            loadMissingLegacySections(populated, forceRefresh)
        }
    }

    private fun hasAnyHomeData(): Boolean {
        return _songs.value != null ||
            _history.value != null ||
            _artists.value != null ||
            _albums.value != null ||
            _recentArtists.value != null ||
            _recentAlbums.value != null ||
            _topSongs.value != null ||
            _topArtists.value != null ||
            _topAlbums.value != null
    }

    private fun clearHomeForOwnerChange() {
        _songs.value = null
        _history.value = null
        _artists.value = null
        _albums.value = null
        _recentArtists.value = null
        _recentAlbums.value = null
        _topSongs.value = null
        _topArtists.value = null
        _topAlbums.value = null
        lastHomeRefreshTime = 0L
        lastSongsFetchTime = 0L
        lastHistoryFetchTime = 0L
        lastArtistsFetchTime = 0L
        lastAlbumsFetchTime = 0L
        lastRecentArtistsFetchTime = 0L
        lastRecentAlbumsFetchTime = 0L
        lastTopSongsFetchTime = 0L
        lastTopArtistsFetchTime = 0L
        lastTopAlbumsFetchTime = 0L
    }

    private fun applyHomeResponse(response: HomeResponseDTO): Set<HomeSectionKind> {
        val populated = mutableSetOf<HomeSectionKind>()
        response.sections.forEach { section ->
            if (section.error != null) return@forEach
            val kind = HomeSectionClassifier.classify(section) ?: return@forEach
            if (kind in populated) return@forEach
            val applied = when (kind) {
                HomeSectionKind.RECOMMENDED_SONGS -> homeItemDecoder.decodeSongs(section)?.let {
                    _songs.value = it
                    _songsTitle.value = section.title.ifBlank { "Recomendado para ti" }
                    _songsError.value = null
                    _songsLoading.value = false
                    lastSongsFetchTime = System.currentTimeMillis()
                    true
                }
                HomeSectionKind.HISTORY -> homeItemDecoder.decodeSongs(section)?.let {
                    _history.value = it
                    _historyError.value = null
                    _historyLoading.value = false
                    lastHistoryFetchTime = System.currentTimeMillis()
                    true
                }
                HomeSectionKind.RECOMMENDED_ARTISTS -> homeItemDecoder.decodeArtists(section)?.let {
                    _artists.value = it
                    _artistsTitle.value = section.title.ifBlank { "Recomendado para ti" }
                    _artistsError.value = null
                    _artistsLoading.value = false
                    lastArtistsFetchTime = System.currentTimeMillis()
                    true
                }
                HomeSectionKind.RECOMMENDED_ALBUMS -> homeItemDecoder.decodeAlbums(section)?.let {
                    _albums.value = it
                    _albumsTitle.value = section.title.ifBlank { "Recomendado para ti" }
                    _albumsError.value = null
                    _albumsLoading.value = false
                    lastAlbumsFetchTime = System.currentTimeMillis()
                    true
                }
                HomeSectionKind.RECENT_ARTISTS -> homeItemDecoder.decodeArtists(section)?.let {
                    _recentArtists.value = it
                    _recentArtistsError.value = null
                    _recentArtistsLoading.value = false
                    lastRecentArtistsFetchTime = System.currentTimeMillis()
                    true
                }
                HomeSectionKind.RECENT_ALBUMS -> homeItemDecoder.decodeAlbums(section)?.let {
                    _recentAlbums.value = it
                    _recentAlbumsError.value = null
                    _recentAlbumsLoading.value = false
                    lastRecentAlbumsFetchTime = System.currentTimeMillis()
                    true
                }
                HomeSectionKind.TOP_SONGS -> homeItemDecoder.decodeSongs(section)?.let {
                    _topSongs.value = it
                    _topSongsError.value = null
                    _topSongsLoading.value = false
                    lastTopSongsFetchTime = System.currentTimeMillis()
                    true
                }
                HomeSectionKind.TOP_ARTISTS -> homeItemDecoder.decodeArtists(section)?.let {
                    _topArtists.value = it
                    _topArtistsError.value = null
                    _topArtistsLoading.value = false
                    lastTopArtistsFetchTime = System.currentTimeMillis()
                    true
                }
                HomeSectionKind.TOP_ALBUMS -> homeItemDecoder.decodeAlbums(section)?.let {
                    _topAlbums.value = it
                    _topAlbumsError.value = null
                    _topAlbumsLoading.value = false
                    lastTopAlbumsFetchTime = System.currentTimeMillis()
                    true
                }
            } ?: false
            if (applied) {
                populated += kind
            } else {
                Log.w(
                    "HomeVM",
                    "Rejected incomplete Home v2 section '${section.id}' ($kind); using legacy fallback"
                )
            }
        }
        return populated
    }

    private fun markEmptySectionsLoading() {
        if (_songs.value == null) _songsLoading.value = true
        if (_history.value == null) _historyLoading.value = true
        if (_artists.value == null) _artistsLoading.value = true
        if (_albums.value == null) _albumsLoading.value = true
        if (_recentArtists.value == null) _recentArtistsLoading.value = true
        if (_recentAlbums.value == null) _recentAlbumsLoading.value = true
        if (_topSongs.value == null) _topSongsLoading.value = true
        if (_topArtists.value == null) _topArtistsLoading.value = true
        if (_topAlbums.value == null) _topAlbumsLoading.value = true
    }

    private fun loadMissingLegacySections(
        populated: Set<HomeSectionKind>,
        forceRefresh: Boolean
    ) {
        if (HomeSectionKind.RECOMMENDED_SONGS !in populated) {
            _songsLoading.value = false
            loadSongs(forceRefresh)
        }
        if (HomeSectionKind.HISTORY !in populated) {
            _historyLoading.value = false
            loadHistory(forceRefresh)
        }
        if (HomeSectionKind.RECOMMENDED_ARTISTS !in populated) {
            _artistsLoading.value = false
            loadArtists(forceRefresh)
        }
        if (HomeSectionKind.RECOMMENDED_ALBUMS !in populated) {
            _albumsLoading.value = false
            loadAlbums(forceRefresh)
        }
        if (HomeSectionKind.RECENT_ARTISTS !in populated) {
            _recentArtistsLoading.value = false
            loadRecentArtists(forceRefresh)
        }
        if (HomeSectionKind.RECENT_ALBUMS !in populated) {
            _recentAlbumsLoading.value = false
            loadRecentAlbums(forceRefresh)
        }
        if (HomeSectionKind.TOP_SONGS !in populated) {
            _topSongsLoading.value = false
            loadTopSongs(forceRefresh)
        }
        if (HomeSectionKind.TOP_ARTISTS !in populated) {
            _topArtistsLoading.value = false
            loadTopArtists(forceRefresh)
        }
        if (HomeSectionKind.TOP_ALBUMS !in populated) {
            _topAlbumsLoading.value = false
            loadTopAlbums(forceRefresh)
        }
    }


    fun loadSongs(forceRefresh: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        val isExpired = (currentTime - lastSongsFetchTime) > DATA_EXPIRATION_TIME
        val hasData = _songs.value != null

        if (!forceRefresh && _songsLoading.value == true) return
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

        if (!forceRefresh && _historyLoading.value == true) return
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

        if (!forceRefresh && _artistsLoading.value == true) return
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

        if (!forceRefresh && _albumsLoading.value == true) return
        if (hasData && !forceRefresh && !isExpired) return

        val showLoading = !hasData
        if (showLoading) _albumsLoading.value = true
        _albumsError.value = null

        viewModelScope.launch {
            try {
                val result = AlbumManager.getRecommendedAlbums(getApplication(), count = 3)
                if (result != null && result.items.isNotEmpty()) {
                    _albumsTitle.value = result.title ?: "Recomendado para ti"
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

        Log.d("HomeVM", "loadRecentArtists: hasData=$hasData forceRefresh=$forceRefresh isExpired=$isExpired")
        if (!forceRefresh && _recentArtistsLoading.value == true) return
        if (hasData && !forceRefresh && !isExpired) {
            Log.d("HomeVM", "loadRecentArtists: skipped (cache, ${_recentArtists.value?.size} items)")
            return
        }

        if (!hasData) _recentArtistsLoading.value = true
        _recentArtistsError.value = null

        viewModelScope.launch {
            try {
                Log.d("HomeVM", "loadRecentArtists: calling API...")
                val result = ArtistManager.getRecentlyAddedArtists(getApplication(), limit = 20)
                Log.d("HomeVM", "loadRecentArtists: got ${result.size} artists")
                _recentArtists.value = result
                if (result.isNotEmpty()) lastRecentArtistsFetchTime = System.currentTimeMillis()
                else if (!hasData) _recentArtistsError.value = "No hay artistas recientes"
            } catch (e: Exception) {
                Log.e("HomeVM", "loadRecentArtists: EXCEPTION", e)
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

        Log.d("HomeVM", "loadRecentAlbums: hasData=$hasData forceRefresh=$forceRefresh isExpired=$isExpired")
        if (!forceRefresh && _recentAlbumsLoading.value == true) return
        if (hasData && !forceRefresh && !isExpired) {
            Log.d("HomeVM", "loadRecentAlbums: skipped (cache, ${_recentAlbums.value?.size} items)")
            return
        }

        if (!hasData) _recentAlbumsLoading.value = true
        _recentAlbumsError.value = null

        viewModelScope.launch {
            try {
                Log.d("HomeVM", "loadRecentAlbums: calling API...")
                val result = AlbumManager.getNewReleaseAlbums(getApplication(), limit = 20)
                Log.d("HomeVM", "loadRecentAlbums: got ${result.size} albums")
                _recentAlbums.value = result
                if (result.isNotEmpty()) lastRecentAlbumsFetchTime = System.currentTimeMillis()
                else if (!hasData) _recentAlbumsError.value = "No hay álbumes recientes"
            } catch (e: Exception) {
                Log.e("HomeVM", "loadRecentAlbums: EXCEPTION", e)
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

        if (!forceRefresh && _topSongsLoading.value == true) return
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

        if (!forceRefresh && _topArtistsLoading.value == true) return
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

        if (!forceRefresh && _topAlbumsLoading.value == true) return
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
