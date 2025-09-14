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

    fun loadFavorites() {
        viewModelScope.launch {
            _favorites.value = repo.getFavorites()
        }
    }

    fun deleteFavorite(songId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val result = repo.deleteFavorite(songId)
            if (result) {
                loadFavorites() // 🔑 recargar toda la lista
            }
            onResult(result)
        }
    }

    fun addFavorite(song: Song, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val result = repo.addFavorite(song.id)
            if (result) {
                loadFavorites() // 🔑 recargar toda la lista
            }
            onResult(result)
        }
    }


}