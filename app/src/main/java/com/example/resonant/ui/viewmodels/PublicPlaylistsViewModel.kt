package com.example.resonant.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.models.Playlist
import com.example.resonant.managers.PlaylistManager
import com.example.resonant.ui.adapters.PlaylistSection
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

class PublicPlaylistsViewModel(application: Application) : AndroidViewModel(application) {

    private val playlistManager = PlaylistManager(application)

    private val _publicPlaylists = MutableLiveData<List<Playlist>>()
    val publicPlaylists: LiveData<List<Playlist>> = _publicPlaylists

    private val _sections = MutableLiveData<List<PlaylistSection>>()
    val sections: LiveData<List<PlaylistSection>> = _sections

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private var cachedPlaylists: List<Playlist>? = null

    fun loadPublicPlaylists(forceRefresh: Boolean = false) {
        if (!forceRefresh && cachedPlaylists != null) {
            _publicPlaylists.value = cachedPlaylists!!
            _sections.value = buildSections(cachedPlaylists!!)
            return
        }

        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val playlists = playlistManager.getAllPublicPlaylists()

                // Enriquecer en paralelo con el nombre del owner
                val enriched = playlists.map { playlist ->
                    async {
                        if (!playlist.userId.isNullOrEmpty()) {
                            try {
                                val user = playlistManager.getUserById(playlist.userId)
                                playlist.ownerName = user.name ?: "Usuario"
                            } catch (_: Exception) {
                                playlist.ownerName = "Usuario"
                            }
                        } else {
                            playlist.ownerName = "Usuario"
                        }
                        playlist
                    }
                }.awaitAll()

                cachedPlaylists = enriched
                _publicPlaylists.value = enriched
                _sections.value = buildSections(enriched)
            } catch (e: Exception) {
                _error.value = "Error al cargar playlists públicas: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun buildSections(playlists: List<Playlist>): List<PlaylistSection> {
        return playlists
            .groupBy { it.ownerName ?: "Usuario" }
            .map { (owner, list) -> PlaylistSection(ownerName = owner, playlists = list) }
            .sortedByDescending { it.playlists.size } // Más playlists primero
    }
}
