package com.example.resonant.ui.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.Genre
import com.example.resonant.data.models.Playlist
import com.example.resonant.managers.AlbumManager
import com.example.resonant.managers.ArtistManager
import com.example.resonant.managers.GenreManager
import com.example.resonant.managers.PlaylistManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

class ExploreViewModel(application: Application) : AndroidViewModel(application) {

    private val genreManager = GenreManager(application)
    private val playlistManager = PlaylistManager(application)

    private val _genres = MutableLiveData<List<Genre>>()
    val genres: LiveData<List<Genre>> get() = _genres

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> get() = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> get() = _error

    private val _recentArtists = MutableLiveData<List<Artist>>()
    val recentArtists: LiveData<List<Artist>> get() = _recentArtists
    private val _recentArtistsLoading = MutableLiveData<Boolean>()
    val recentArtistsLoading: LiveData<Boolean> get() = _recentArtistsLoading

    private val _mostListenedArtists = MutableLiveData<List<Artist>>()
    val mostListenedArtists: LiveData<List<Artist>> get() = _mostListenedArtists
    private val _mostListenedArtistsLoading = MutableLiveData<Boolean>()
    val mostListenedArtistsLoading: LiveData<Boolean> get() = _mostListenedArtistsLoading

    private val _newReleaseAlbums = MutableLiveData<List<Album>>()
    val newReleaseAlbums: LiveData<List<Album>> get() = _newReleaseAlbums
    private val _newReleaseAlbumsLoading = MutableLiveData<Boolean>()
    val newReleaseAlbumsLoading: LiveData<Boolean> get() = _newReleaseAlbumsLoading

    private val _publicPlaylists = MutableLiveData<List<Playlist>>()
    val publicPlaylists: LiveData<List<Playlist>> get() = _publicPlaylists
    private val _publicPlaylistsLoading = MutableLiveData<Boolean>()
    val publicPlaylistsLoading: LiveData<Boolean> get() = _publicPlaylistsLoading

    fun loadPopularGenres() {
        if (_genres.value != null) return
        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val result = genreManager.getPopularGenres(count = 32)
                _genres.value = result
                Log.i("ExploreVM", "Generos cargados: ${result.size}")
            } catch (e: Exception) {
                _error.value = "Error al cargar generos: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadAllGenres() {
        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                _genres.value = genreManager.getPopularGenres(count = 100)
            } catch (e: Exception) {
                _error.value = "Error al cargar generos: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadPublicPlaylists() {
        if (_publicPlaylists.value != null) return
        _publicPlaylistsLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val playlists = playlistManager.getAllPublicPlaylists().take(7)
                _publicPlaylists.value = playlists.map { playlist ->
                    async {
                        when {
                            playlist.isSystemPlaylist -> playlist.ownerName = "Resonant"
                            !playlist.userId.isNullOrEmpty() -> {
                                playlist.ownerName = try {
                                    playlistManager.getUserById(playlist.userId).name ?: "Usuario"
                                } catch (_: Exception) {
                                    "Usuario"
                                }
                            }
                            else -> playlist.ownerName = "Usuario"
                        }
                        playlist
                    }
                }.awaitAll()
            } catch (e: Exception) {
                Log.w("ExploreVM", "Error cargando playlists publicas: ${e.message}")
                _publicPlaylists.value = emptyList()
            } finally {
                _publicPlaylistsLoading.value = false
            }
        }
    }

    fun loadMostListenedArtists() {
        if (_mostListenedArtists.value != null) return
        _mostListenedArtistsLoading.value = true

        viewModelScope.launch {
            try {
                val topArtists = ArtistManager.getMostListenedArtists(getApplication(), limit = 2)
                _mostListenedArtists.value = topArtists.ifEmpty {
                    ArtistManager.getRecentlyAddedArtists(getApplication(), limit = 2)
                }
            } catch (e: Exception) {
                Log.w("ExploreVM", "Error cargando artistas mas escuchados: ${e.message}")
                _mostListenedArtists.value = emptyList()
            } finally {
                _mostListenedArtistsLoading.value = false
            }
        }
    }

    fun loadRecentArtists() {
        if (_recentArtists.value != null) return
        _recentArtistsLoading.value = true

        viewModelScope.launch {
            try {
                _recentArtists.value = ArtistManager.getRecentlyAddedArtists(getApplication(), limit = 20)
            } catch (e: Exception) {
                Log.w("ExploreVM", "Error cargando artistas recientes: ${e.message}")
                _recentArtists.value = emptyList()
            } finally {
                _recentArtistsLoading.value = false
            }
        }
    }

    fun loadNewReleaseAlbums() {
        if (_newReleaseAlbums.value != null) return
        _newReleaseAlbumsLoading.value = true

        viewModelScope.launch {
            try {
                _newReleaseAlbums.value = AlbumManager.getNewReleaseAlbums(getApplication(), limit = 20)
            } catch (e: Exception) {
                Log.w("ExploreVM", "Error cargando nuevos albumes: ${e.message}")
                _newReleaseAlbums.value = emptyList()
            } finally {
                _newReleaseAlbumsLoading.value = false
            }
        }
    }
}
