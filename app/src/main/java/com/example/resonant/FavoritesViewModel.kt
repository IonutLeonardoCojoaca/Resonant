package com.example.resonant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class FavoritesViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = FavoriteManager(app.applicationContext)
    private val _favorites = MutableLiveData<List<Song>>(emptyList())
    val favorites: LiveData<List<Song>> get() = _favorites

    // Inicializa favoritos en memoria
    private var currentFavorites: MutableList<Song> = mutableListOf()

    fun loadFavorites() {
        viewModelScope.launch {
            val favs = repo.getFavorites(currentFavorites)
            currentFavorites = favs.toMutableList()
            _favorites.value = favs
        }
    }

    fun addFavorite(song: Song, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            currentFavorites.add(song)
            _favorites.value = currentFavorites.toList()
            val result = repo.addFavorite(song.id)
            if (result) {
                loadFavorites() // sincroniza con backend
            }
            onResult(result)
        }
    }

    fun deleteFavorite(songId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            currentFavorites.removeAll { it.id == songId }
            _favorites.value = currentFavorites.toList()
            val result = repo.deleteFavorite(songId)
            if (result) {
                loadFavorites() // sincroniza con backend
            }
            onResult(result)
        }
    }
}