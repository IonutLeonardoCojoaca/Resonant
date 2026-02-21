package com.example.resonant.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.models.Album
import com.example.resonant.data.network.ApiClient
import kotlinx.coroutines.launch

data class AlbumRank(
    val album: Album,
    val rank: Int,
    val releaseYear: Int?,
    val artistName: String?
)

class TopAlbumsViewModel(application: Application) : AndroidViewModel(application) {

    private val albumService = ApiClient.getAlbumService(application)

    private val _topAlbums = MutableLiveData<List<AlbumRank>>()
    val topAlbums: LiveData<List<AlbumRank>> = _topAlbums

    private val _featuredAlbum = MutableLiveData<AlbumRank?>()
    val featuredAlbum: LiveData<AlbumRank?> = _featuredAlbum

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    fun loadTopAlbums(period: Int) {
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val topAlbumsList = albumService.getTopAlbums(period = period.toString(), limit = 50)
                
                val rankedList = topAlbumsList.mapIndexed { index, album ->
                    AlbumRank(
                        album = album,
                        rank = index + 1,
                        releaseYear = album.releaseYear,
                        artistName = album.artistName ?: album.artists.firstOrNull()?.name
                    )
                }
                
                if (rankedList.isNotEmpty()) {
                    _featuredAlbum.value = rankedList[0]
                    _topAlbums.value = rankedList.drop(1)
                } else {
                    _featuredAlbum.value = null
                    _topAlbums.value = emptyList()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                _featuredAlbum.value = null
                _topAlbums.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }
}
