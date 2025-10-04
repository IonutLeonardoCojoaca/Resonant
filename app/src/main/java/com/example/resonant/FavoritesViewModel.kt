package com.example.resonant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

sealed class FavoriteItem {
    data class SongItem(val song: Song) : FavoriteItem()
    data class ArtistItem(val artist: Artist) : FavoriteItem()
    data class AlbumItem(val album: Album) : FavoriteItem()
}

class FavoritesViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = FavoriteManager(app.applicationContext)

    private val _favorites = MutableLiveData<List<FavoriteItem>>(emptyList())
    val favorites: LiveData<List<FavoriteItem>> get() = _favorites

    private val _favoriteArtistIds = MutableLiveData<Set<String>>(emptySet())
    val favoriteArtistIds: LiveData<Set<String>> get() = _favoriteArtistIds

    private val _favoriteSongIds = MutableLiveData<Set<String>>(emptySet())
    val favoriteSongIds: LiveData<Set<String>> get() = _favoriteSongIds

    private val _favoriteAlbumIds = MutableLiveData<Set<String>>(emptySet())
    val favoriteAlbumIds: LiveData<Set<String>> get() = _favoriteAlbumIds

    fun toggleFavoriteSong(song: Song, onResult: (Boolean, Boolean) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val currentFavorites = _favoriteSongIds.value.orEmpty()
            val isCurrentlyFavorite = currentFavorites.contains(song.id)

            if (isCurrentlyFavorite) {
                // 🔻 Si ya es favorito, lo eliminamos
                val result = repo.deleteFavoriteSong(song.id)
                if (result) loadFavoriteSongs()
                onResult(result, false) // (éxito, ahora no es favorito)
            } else {
                // 🔺 Si no es favorito, lo añadimos
                val result = repo.addFavoriteSong(song.id)
                if (result) loadFavoriteSongs()
                onResult(result, true) // (éxito, ahora sí es favorito)
            }
        }
    }

    // ---- CARGA GENERAL ----
    fun loadAllFavorites() {
        viewModelScope.launch {
            val favSongs = repo.getFavoritesSongs()
            val favArtists = repo.getFavoriteArtists()
            val favAlbums = repo.getFavoriteAlbums()
            val items = mutableListOf<FavoriteItem>()
            items += favSongs.map { FavoriteItem.SongItem(it) }
            items += favArtists.map { FavoriteItem.ArtistItem(it) }
            items += favAlbums.map { FavoriteItem.AlbumItem(it) }
            _favorites.value = items
            updateFavoriteIds()
        }
    }

    // ---- SONGS ----
    fun loadFavoriteSongs() {
        viewModelScope.launch {
            val favSongs = repo.getFavoritesSongs()
            val current = _favorites.value.orEmpty().filterNot { it is FavoriteItem.SongItem }
            _favorites.value = current + favSongs.map { FavoriteItem.SongItem(it) }
            updateFavoriteIds()
        }
    }

    fun addFavoriteSong(song: Song, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val result = repo.addFavoriteSong(song.id)
            if (result) loadFavoriteSongs()
            onResult(result)
        }
    }

    fun deleteFavoriteSong(songId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val result = repo.deleteFavoriteSong(songId)
            if (result) loadFavoriteSongs()
            onResult(result)
        }
    }

    // ---- ARTISTS ----
    fun loadFavoriteArtists() {
        viewModelScope.launch {
            val favArtists = repo.getFavoriteArtists()
            val current = _favorites.value.orEmpty().filterNot { it is FavoriteItem.ArtistItem }
            _favorites.value = current + favArtists.map { FavoriteItem.ArtistItem(it) }
            updateFavoriteIds()
        }
    }

    fun addFavoriteArtist(artist: Artist, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val result = repo.addFavoriteArtist(artist.id)
            if (result) loadFavoriteArtists()
            onResult(result)
        }
    }

    fun deleteFavoriteArtist(artistId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val result = repo.deleteFavoriteArtist(artistId)
            if (result) loadFavoriteArtists()
            onResult(result)
        }
    }

    // ---- ALBUMS ----
    fun loadFavoriteAlbums() {
        viewModelScope.launch {
            val favAlbums = repo.getFavoriteAlbums()
            val current = _favorites.value.orEmpty().filterNot { it is FavoriteItem.AlbumItem }
            _favorites.value = current + favAlbums.map { FavoriteItem.AlbumItem(it) }
            updateFavoriteIds()
        }
    }

    fun addFavoriteAlbum(album: Album, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val result = repo.addFavoriteAlbum(album.id)
            if (result) loadFavoriteAlbums()
            onResult(result)
        }
    }

    fun deleteFavoriteAlbum(albumId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val result = repo.deleteFavoriteAlbum(albumId)
            if (result) loadFavoriteAlbums()
            onResult(result)
        }
    }

    private fun updateFavoriteIds() {
        val favoritesList = _favorites.value.orEmpty()
        _favoriteArtistIds.value = favoritesList.filterIsInstance<FavoriteItem.ArtistItem>().map { it.artist.id }.toSet()
        _favoriteSongIds.value = favoritesList.filterIsInstance<FavoriteItem.SongItem>().map { it.song.id }.toSet()
        _favoriteAlbumIds.value = favoritesList.filterIsInstance<FavoriteItem.AlbumItem>().map { it.album.id }.toSet()
    }
}