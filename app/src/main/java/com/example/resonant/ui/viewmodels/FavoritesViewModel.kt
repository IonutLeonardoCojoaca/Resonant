package com.example.resonant.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.resonant.managers.FavoriteManager
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.Song
import kotlinx.coroutines.launch

class FavoritesViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = FavoriteManager(app.applicationContext)

    // --- LiveData ---
    private val _favoriteSongs = MutableLiveData<List<Song>>(emptyList())
    val favoriteSongs: LiveData<List<Song>> get() = _favoriteSongs

    private val _favoriteArtists = MutableLiveData<List<Artist>>(emptyList())
    val favoriteArtists: LiveData<List<Artist>> get() = _favoriteArtists

    private val _favoriteAlbums = MutableLiveData<List<Album>>(emptyList())
    val favoriteAlbums: LiveData<List<Album>> get() = _favoriteAlbums

    // --- IDs para chequeo rápido ---
    private val _favoriteArtistIds = MutableLiveData<Set<String>>(emptySet())
    val favoriteArtistIds: LiveData<Set<String>> get() = _favoriteArtistIds

    private val _favoriteSongIds = MutableLiveData<Set<String>>(emptySet())
    val favoriteSongIds: LiveData<Set<String>> get() = _favoriteSongIds

    private val _favoriteAlbumIds = MutableLiveData<Set<String>>(emptySet())
    val favoriteAlbumIds: LiveData<Set<String>> get() = _favoriteAlbumIds


    // =========================================================================
    //                            CARGA DE DATOS
    // =========================================================================

    fun loadAllFavorites() {
        viewModelScope.launch {
            // Lanzamos en paralelo para mayor velocidad
            launch { loadFavoriteSongs() }
            launch { loadFavoriteArtists() }
            launch { loadFavoriteAlbums() }
        }
    }

    fun loadFavoriteSongs() {
        viewModelScope.launch {
            val favSongs = repo.getFavoritesSongs()
            _favoriteSongs.value = favSongs
            _favoriteSongIds.value = favSongs.map { it.id }.toSet()
        }
    }

    fun loadFavoriteArtists() {
        viewModelScope.launch {
            val favArtists = repo.getFavoriteArtists()
            _favoriteArtists.value = favArtists
            _favoriteArtistIds.value = favArtists.map { it.id }.toSet()
        }
    }

    fun loadFavoriteAlbums() {
        viewModelScope.launch {
            val favAlbums = repo.getFavoriteAlbums()
            _favoriteAlbums.value = favAlbums
            _favoriteAlbumIds.value = favAlbums.map { it.id }.toSet()
        }
    }


    // =========================================================================
    //                            CANCIONES (SONGS)
    // =========================================================================

    fun toggleFavoriteSong(song: Song, onResult: (Boolean, Boolean) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val currentFavoritesIds = _favoriteSongIds.value.orEmpty()
            val isCurrentlyFavorite = currentFavoritesIds.contains(song.id)

            if (isCurrentlyFavorite) {
                // 🚀 OPTIMISTA: Borrar de la UI inmediatamente
                val updatedList = _favoriteSongs.value.orEmpty().filter { it.id != song.id }
                _favoriteSongs.value = updatedList
                _favoriteSongIds.value = updatedList.map { it.id }.toSet()

                // Llamada a BD/Red
                val result = repo.deleteFavoriteSong(song.id)
                if (!result) loadFavoriteSongs() // Revertir si falla
                onResult(result, false)
            } else {
                // Agregar
                val result = repo.addFavoriteSong(song.id)
                if (result) loadFavoriteSongs()
                onResult(result, true)
            }
        }
    }


    // =========================================================================
    //                            ARTISTAS (ARTISTS)
    // =========================================================================

    fun addFavoriteArtist(artist: Artist, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val result = repo.addFavoriteArtist(artist.id)
            if (result) loadFavoriteArtists()
            onResult(result)
        }
    }

    // 🔥 MODIFICADO PARA SER ROBUSTO (OPTIMISTA)
    fun deleteFavoriteArtist(artistId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            // 1. UI UPDATE INMEDIATO
            val currentList = _favoriteArtists.value.orEmpty()
            val updatedList = currentList.filter { it.id != artistId }

            _favoriteArtists.value = updatedList
            _favoriteArtistIds.value = updatedList.map { it.id }.toSet()

            // 2. NETWORK CALL
            val result = repo.deleteFavoriteArtist(artistId)

            // 3. FALLBACK (Si falla, recargamos la lista real)
            if (!result) {
                loadFavoriteArtists()
            }
            onResult(result)
        }
    }


    // =========================================================================
    //                            ÁLBUMES (ALBUMS)
    // =========================================================================

    fun addFavoriteAlbum(album: Album, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val result = repo.addFavoriteAlbum(album.id)
            if (result) loadFavoriteAlbums()
            onResult(result)
        }
    }

    // 🔥 MODIFICADO PARA SER ROBUSTO (OPTIMISTA)
    fun deleteFavoriteAlbum(albumId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            // 1. UI UPDATE INMEDIATO
            val currentList = _favoriteAlbums.value.orEmpty()
            val updatedList = currentList.filter { it.id != albumId }

            _favoriteAlbums.value = updatedList
            _favoriteAlbumIds.value = updatedList.map { it.id }.toSet()

            // 2. NETWORK CALL
            val result = repo.deleteFavoriteAlbum(albumId)

            // 3. FALLBACK
            if (!result) {
                loadFavoriteAlbums()
            }
            onResult(result)
        }
    }
}