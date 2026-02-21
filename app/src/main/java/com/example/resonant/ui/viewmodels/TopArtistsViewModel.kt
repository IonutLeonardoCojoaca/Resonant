package com.example.resonant.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.models.Artist
import com.example.resonant.data.network.ApiClient
import kotlinx.coroutines.launch

data class ArtistRank(
    val artist: Artist,
    val rank: Int,
    val songCount: Int,
    val topSongName: String?
)

class TopArtistsViewModel(application: Application) : AndroidViewModel(application) {

    private val artistService = ApiClient.getArtistService(application)

    private val _topArtists = MutableLiveData<List<ArtistRank>>()
    val topArtists: LiveData<List<ArtistRank>> = _topArtists

    private val _featuredArtist = MutableLiveData<ArtistRank?>()
    val featuredArtist: LiveData<ArtistRank?> = _featuredArtist

    private val _featuredImages = MutableLiveData<List<String>>()
    val featuredImages: LiveData<List<String>> = _featuredImages

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    fun loadTopArtists(period: Int) {
        _isLoading.value = true
        viewModelScope.launch {
            try {
                // Usamos el endpoint directo de artistas
                val topArtistsList = artistService.getTopArtists(period = period.toString(), limit = 50)
                
                val rankedList = topArtistsList.mapIndexed { index, artist ->
                    ArtistRank(
                        artist = artist,
                        rank = index + 1,
                        songCount = 0,
                        topSongName = null
                    )
                }
                
                if (rankedList.isNotEmpty()) {
                    val first = rankedList[0]
                    _featuredArtist.value = first
                    
                    // El resto (del 2 en adelante) para la lista
                    val rest = rankedList.drop(1)
                    _topArtists.value = rest
                    
                    // Mostramos ya el contenido y quitamos el loader
                    _isLoading.value = false
                    
                    // Ponemos la imagen principal inmediatamente como primera en la galería
                    _featuredImages.value = listOf(first.artist.url ?: "")
                    
                    // Cargar el resto de imágenes en segundo plano (no bloquea el loader principal)
                    loadFeaturedImages(first.artist.id)
                } else {
                    _featuredArtist.value = null
                    _topArtists.value = emptyList()
                    _featuredImages.value = emptyList()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                _featuredArtist.value = null
                _topArtists.value = emptyList()
            } finally {
                // Ya lo controlamos arriba o aquí por si hay error
                if (_isLoading.value == true) _isLoading.value = false
            }
        }
    }

    private suspend fun loadFeaturedImages(artistId: String) {
        try {
            val response = artistService.getArtistImages(artistId)
            val images = mutableListOf<String>()
            
            // Prioridad: Main image, luego gallery
            response.mainImageUrl?.let { images.add(it) }
            response.galleryImageUrls?.let { images.addAll(it) }
            
            // Si no hay suficientes, repetir la main o buscar en otras top songs? 
            // Por ahora pasar lo que hay.
            
            _featuredImages.value = images
        } catch (e: Exception) {
            _featuredImages.value = emptyList()
        }
    }
}
