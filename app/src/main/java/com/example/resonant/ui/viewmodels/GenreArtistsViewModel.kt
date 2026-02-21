package com.example.resonant.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.models.Artist
import com.example.resonant.data.network.ApiClient
import kotlinx.coroutines.launch

class GenreArtistsViewModel(application: Application) : AndroidViewModel(application) {

    private val _artists = MutableLiveData<List<Artist>>()
    val artists: LiveData<List<Artist>> = _artists

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    fun loadArtistsByGenre(genreId: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null
                
                val artistService = ApiClient.getArtistService(getApplication())
                val artistsList = artistService.getArtistsByGenreId(genreId)
                
                _artists.value = artistsList
                
            } catch (e: Exception) {
                _errorMessage.value = "Error al cargar artistas: ${e.message}"
                _artists.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }
}
