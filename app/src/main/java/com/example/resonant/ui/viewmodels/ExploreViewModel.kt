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
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.services.UserService
import com.example.resonant.managers.AlbumManager
import com.example.resonant.managers.ArtistManager
import com.example.resonant.managers.GenreManager
import kotlinx.coroutines.launch

class ExploreViewModel(application: Application) : AndroidViewModel(application) {

    private val genreManager = GenreManager(application)
    private val userService: UserService = ApiClient.getUserService(application)

    // LiveData para la lista de géneros
    private val _genres = MutableLiveData<List<Genre>>()
    val genres: LiveData<List<Genre>> get() = _genres

    // LiveData para estado de carga
    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> get() = _isLoading

    // LiveData para errores
    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> get() = _error

    // Género favorito del usuario (nombre para mostrar en el círculo central)
    private val _favoriteGenreName = MutableLiveData<String?>()
    val favoriteGenreName: LiveData<String?> get() = _favoriteGenreName

    // Colores de gradiente del género favorito
    private val _favoriteGenreColors = MutableLiveData<String?>()
    val favoriteGenreColors: LiveData<String?> get() = _favoriteGenreColors

    private val _recentArtists = MutableLiveData<List<Artist>>()
    val recentArtists: LiveData<List<Artist>> get() = _recentArtists
    private val _recentArtistsLoading = MutableLiveData<Boolean>()
    val recentArtistsLoading: LiveData<Boolean> get() = _recentArtistsLoading

    private val _newReleaseAlbums = MutableLiveData<List<Album>>()
    val newReleaseAlbums: LiveData<List<Album>> get() = _newReleaseAlbums
    private val _newReleaseAlbumsLoading = MutableLiveData<Boolean>()
    val newReleaseAlbumsLoading: LiveData<Boolean> get() = _newReleaseAlbumsLoading

    fun loadPopularGenres() {
        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val result = genreManager.getPopularGenres(count = 20)
                _genres.value = result
                Log.i("ExploreVM", "Géneros cargados: ${result.size}")
            } catch (e: Exception) {
                _error.value = "Error al cargar géneros: ${e.message}"
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
                val result = genreManager.getPopularGenres(count = 100)
                _genres.value = result
            } catch (e: Exception) {
                _error.value = "Error al cargar géneros: ${e.message}"
            } finally {
                _isLoading.value = false
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
                Log.w("ExploreVM", "Error cargando nuevos álbumes: ${e.message}")
                _newReleaseAlbums.value = emptyList()
            } finally {
                _newReleaseAlbumsLoading.value = false
            }
        }
    }

    fun loadFavoriteGenre() {
        viewModelScope.launch {
            try {
                // Get the current user to extract their ID, then fetch their favourite genres
                val user = userService.getCurrentUser()
                val favorites = genreManager.getFavoriteGenres(user.id)
                val top = favorites.firstOrNull()
                _favoriteGenreName.value = top?.name
                _favoriteGenreColors.value = top?.gradientColors
                Log.d("ExploreVM", "Género favorito: ${top?.name}")
            } catch (e: Exception) {
                // Non-critical: silently leave the centre empty if this fails
                Log.w("ExploreVM", "No se pudo cargar género favorito: ${e.message}")
                _favoriteGenreName.value = null
            }
        }
    }
}