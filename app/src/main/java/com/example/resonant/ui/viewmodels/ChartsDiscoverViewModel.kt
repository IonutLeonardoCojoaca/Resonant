package com.example.resonant.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.AlbumStatsDTO
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.ArtistStatsDTO
import com.example.resonant.data.models.Song
import com.example.resonant.managers.AlbumManager
import com.example.resonant.managers.ArtistManager
import com.example.resonant.managers.SongManager
import kotlinx.coroutines.launch

class ChartsDiscoverViewModel(application: Application) : AndroidViewModel(application) {

    private val songManager = SongManager(application)

    // --- NEW RELEASE SONGS ---
    private val _newReleaseSongs = MutableLiveData<List<Song>>()
    val newReleaseSongs: LiveData<List<Song>> get() = _newReleaseSongs
    private val _newReleaseSongsLoading = MutableLiveData<Boolean>()
    val newReleaseSongsLoading: LiveData<Boolean> get() = _newReleaseSongsLoading
    private val _newReleaseSongsError = MutableLiveData<String?>()
    val newReleaseSongsError: LiveData<String?> get() = _newReleaseSongsError

    // --- SONGS BY YEAR ---
    private val _songsByYear = MutableLiveData<List<Song>>()
    val songsByYear: LiveData<List<Song>> get() = _songsByYear
    private val _songsByYearLoading = MutableLiveData<Boolean>()
    val songsByYearLoading: LiveData<Boolean> get() = _songsByYearLoading
    private val _songsByYearError = MutableLiveData<String?>()
    val songsByYearError: LiveData<String?> get() = _songsByYearError

    // --- MOST FAVORITED SONGS ---
    private val _mostFavoritedSongs = MutableLiveData<List<Song>>()
    val mostFavoritedSongs: LiveData<List<Song>> get() = _mostFavoritedSongs
    private val _mostFavoritedSongsLoading = MutableLiveData<Boolean>()
    val mostFavoritedSongsLoading: LiveData<Boolean> get() = _mostFavoritedSongsLoading
    private val _mostFavoritedSongsError = MutableLiveData<String?>()
    val mostFavoritedSongsError: LiveData<String?> get() = _mostFavoritedSongsError

    // --- MOST LISTENED SONGS (user) ---
    private val _mostListenedSongs = MutableLiveData<List<Song>>()
    val mostListenedSongs: LiveData<List<Song>> get() = _mostListenedSongs
    private val _mostListenedSongsLoading = MutableLiveData<Boolean>()
    val mostListenedSongsLoading: LiveData<Boolean> get() = _mostListenedSongsLoading
    private val _mostListenedSongsError = MutableLiveData<String?>()
    val mostListenedSongsError: LiveData<String?> get() = _mostListenedSongsError

    // --- RELATED SONGS ---
    private val _relatedSongs = MutableLiveData<List<Song>>()
    val relatedSongs: LiveData<List<Song>> get() = _relatedSongs
    private val _relatedSongsLoading = MutableLiveData<Boolean>()
    val relatedSongsLoading: LiveData<Boolean> get() = _relatedSongsLoading

    // --- NEW RELEASE ALBUMS ---
    private val _newReleaseAlbums = MutableLiveData<List<Album>>()
    val newReleaseAlbums: LiveData<List<Album>> get() = _newReleaseAlbums
    private val _newReleaseAlbumsLoading = MutableLiveData<Boolean>()
    val newReleaseAlbumsLoading: LiveData<Boolean> get() = _newReleaseAlbumsLoading
    private val _newReleaseAlbumsError = MutableLiveData<String?>()
    val newReleaseAlbumsError: LiveData<String?> get() = _newReleaseAlbumsError

    // --- ALBUMS BY YEAR ---
    private val _albumsByYear = MutableLiveData<List<Album>>()
    val albumsByYear: LiveData<List<Album>> get() = _albumsByYear
    private val _albumsByYearLoading = MutableLiveData<Boolean>()
    val albumsByYearLoading: LiveData<Boolean> get() = _albumsByYearLoading
    private val _albumsByYearError = MutableLiveData<String?>()
    val albumsByYearError: LiveData<String?> get() = _albumsByYearError

    // --- MOST LISTENED ALBUMS (user) ---
    private val _mostListenedAlbums = MutableLiveData<List<Album>>()
    val mostListenedAlbums: LiveData<List<Album>> get() = _mostListenedAlbums
    private val _mostListenedAlbumsLoading = MutableLiveData<Boolean>()
    val mostListenedAlbumsLoading: LiveData<Boolean> get() = _mostListenedAlbumsLoading
    private val _mostListenedAlbumsError = MutableLiveData<String?>()
    val mostListenedAlbumsError: LiveData<String?> get() = _mostListenedAlbumsError

    // --- ALBUM STATS ---
    private val _albumStats = MutableLiveData<AlbumStatsDTO?>()
    val albumStats: LiveData<AlbumStatsDTO?> get() = _albumStats

    // --- ALBUM ARTISTS ---
    private val _albumArtists = MutableLiveData<List<Artist>>()
    val albumArtists: LiveData<List<Artist>> get() = _albumArtists

    // --- RECENTLY ADDED ARTISTS ---
    private val _recentlyAddedArtists = MutableLiveData<List<Artist>>()
    val recentlyAddedArtists: LiveData<List<Artist>> get() = _recentlyAddedArtists
    private val _recentlyAddedArtistsLoading = MutableLiveData<Boolean>()
    val recentlyAddedArtistsLoading: LiveData<Boolean> get() = _recentlyAddedArtistsLoading

    // --- MOST LISTENED ARTISTS (user) ---
    private val _mostListenedArtists = MutableLiveData<List<Artist>>()
    val mostListenedArtists: LiveData<List<Artist>> get() = _mostListenedArtists
    private val _mostListenedArtistsLoading = MutableLiveData<Boolean>()
    val mostListenedArtistsLoading: LiveData<Boolean> get() = _mostListenedArtistsLoading

    // --- ARTIST STATS ---
    private val _artistStats = MutableLiveData<ArtistStatsDTO?>()
    val artistStats: LiveData<ArtistStatsDTO?> get() = _artistStats

    fun loadNewReleaseSongs(limit: Int = 50) {
        if (_newReleaseSongs.value != null) return
        _newReleaseSongsLoading.value = true
        _newReleaseSongsError.value = null
        viewModelScope.launch {
            try {
                _newReleaseSongs.value = songManager.getNewReleaseSongs(limit)
            } catch (e: Exception) {
                _newReleaseSongsError.value = "Error al cargar nuevos lanzamientos"
            } finally {
                _newReleaseSongsLoading.value = false
            }
        }
    }

    fun loadSongsByYear(yearFrom: Int? = null, yearTo: Int? = null, limit: Int = 50) {
        _songsByYearLoading.value = true
        _songsByYearError.value = null
        viewModelScope.launch {
            try {
                _songsByYear.value = songManager.getSongsByYear(yearFrom, yearTo, limit)
            } catch (e: Exception) {
                _songsByYearError.value = "Error al cargar canciones por año"
            } finally {
                _songsByYearLoading.value = false
            }
        }
    }

    fun loadMostFavoritedSongs(limit: Int = 50) {
        if (_mostFavoritedSongs.value != null) return
        _mostFavoritedSongsLoading.value = true
        _mostFavoritedSongsError.value = null
        viewModelScope.launch {
            try {
                _mostFavoritedSongs.value = songManager.getMostFavoritedSongs(limit)
            } catch (e: Exception) {
                _mostFavoritedSongsError.value = "Error al cargar canciones más gustadas"
            } finally {
                _mostFavoritedSongsLoading.value = false
            }
        }
    }

    fun loadMostListenedSongs(limit: Int = 20) {
        if (_mostListenedSongs.value != null) return
        _mostListenedSongsLoading.value = true
        _mostListenedSongsError.value = null
        viewModelScope.launch {
            try {
                _mostListenedSongs.value = songManager.getMostListenedSongs(limit)
            } catch (e: Exception) {
                _mostListenedSongsError.value = "Error al cargar tus más escuchadas"
            } finally {
                _mostListenedSongsLoading.value = false
            }
        }
    }

    fun loadRelatedSongs(songId: String, limit: Int = 20) {
        _relatedSongsLoading.value = true
        viewModelScope.launch {
            try {
                _relatedSongs.value = songManager.getRelatedSongs(songId, limit)
            } catch (e: Exception) {
                _relatedSongs.value = emptyList()
            } finally {
                _relatedSongsLoading.value = false
            }
        }
    }

    fun loadNewReleaseAlbums(limit: Int = 20) {
        if (_newReleaseAlbums.value != null) return
        _newReleaseAlbumsLoading.value = true
        _newReleaseAlbumsError.value = null
        viewModelScope.launch {
            try {
                _newReleaseAlbums.value = AlbumManager.getNewReleaseAlbums(getApplication(), limit)
            } catch (e: Exception) {
                _newReleaseAlbumsError.value = "Error al cargar nuevos álbumes"
            } finally {
                _newReleaseAlbumsLoading.value = false
            }
        }
    }

    fun loadAlbumsByYear(yearFrom: Int? = null, yearTo: Int? = null, limit: Int = 50) {
        _albumsByYearLoading.value = true
        _albumsByYearError.value = null
        viewModelScope.launch {
            try {
                _albumsByYear.value = AlbumManager.getAlbumsByYear(getApplication(), yearFrom, yearTo, limit)
            } catch (e: Exception) {
                _albumsByYearError.value = "Error al cargar álbumes por año"
            } finally {
                _albumsByYearLoading.value = false
            }
        }
    }

    fun loadMostListenedAlbums(limit: Int = 20) {
        if (_mostListenedAlbums.value != null) return
        _mostListenedAlbumsLoading.value = true
        _mostListenedAlbumsError.value = null
        viewModelScope.launch {
            try {
                _mostListenedAlbums.value = AlbumManager.getMostListenedAlbums(getApplication(), limit)
            } catch (e: Exception) {
                _mostListenedAlbumsError.value = "Error al cargar tus álbumes más escuchados"
            } finally {
                _mostListenedAlbumsLoading.value = false
            }
        }
    }

    fun loadAlbumStats(albumId: String) {
        viewModelScope.launch {
            _albumStats.value = AlbumManager.getAlbumStats(getApplication(), albumId)
        }
    }

    fun loadAlbumArtists(albumId: String) {
        viewModelScope.launch {
            _albumArtists.value = AlbumManager.getAlbumArtists(getApplication(), albumId)
        }
    }

    fun loadRecentlyAddedArtists(limit: Int = 20) {
        if (_recentlyAddedArtists.value != null) return
        _recentlyAddedArtistsLoading.value = true
        viewModelScope.launch {
            try {
                _recentlyAddedArtists.value = ArtistManager.getRecentlyAddedArtists(getApplication(), limit)
            } catch (e: Exception) {
                _recentlyAddedArtists.value = emptyList()
            } finally {
                _recentlyAddedArtistsLoading.value = false
            }
        }
    }

    fun loadMostListenedArtists(limit: Int = 20) {
        if (_mostListenedArtists.value != null) return
        _mostListenedArtistsLoading.value = true
        viewModelScope.launch {
            try {
                _mostListenedArtists.value = ArtistManager.getMostListenedArtists(getApplication(), limit)
            } catch (e: Exception) {
                _mostListenedArtists.value = emptyList()
            } finally {
                _mostListenedArtistsLoading.value = false
            }
        }
    }

    fun loadArtistStats(artistId: String) {
        viewModelScope.launch {
            _artistStats.value = ArtistManager.getArtistStats(getApplication(), artistId)
        }
    }
}
