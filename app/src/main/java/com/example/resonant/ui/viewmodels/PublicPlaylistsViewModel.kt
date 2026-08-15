package com.example.resonant.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.models.Playlist
import com.example.resonant.managers.PlaylistManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

class PublicPlaylistsViewModel(application: Application) : AndroidViewModel(application) {

    private val playlistManager = PlaylistManager(application)

    // Único listado plano, ya entremezclado (oficiales de Resonant + de
    // usuarios) — ya no se agrupa por dueño en secciones separadas.
    private val _publicPlaylists = MutableLiveData<List<Playlist>>()
    val publicPlaylists: LiveData<List<Playlist>> = _publicPlaylists

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private var cachedPlaylists: List<Playlist>? = null

    fun loadPublicPlaylists(forceRefresh: Boolean = false) {
        if (!forceRefresh && cachedPlaylists != null) {
            _publicPlaylists.value = cachedPlaylists!!
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
                        if (playlist.isSystemPlaylist) {
                            playlist.ownerName = "Resonant"
                        } else if (!playlist.userId.isNullOrEmpty()) {
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

                val interleaved = interleaveByOwnerType(enriched)
                cachedPlaylists = interleaved
                _publicPlaylists.value = interleaved
            } catch (e: Exception) {
                _error.value = "Error al cargar playlists públicas: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Mismo patrón round-robin que SearchFragment.intercalateAndSortResults():
     * en vez de agrupar en secciones por dueño, se reparten en dos listas
     * (oficiales de Resonant / del resto de usuarios) y se van tomando
     * alternadamente para que el listado final salga entremezclado en vez
     * de "todo Resonant primero, luego todo lo demás".
     */
    private fun interleaveByOwnerType(playlists: List<Playlist>): List<Playlist> {
        val official = playlists.filter { it.isSystemPlaylist }
        val others = playlists.filterNot { it.isSystemPlaylist }

        val lists = mutableListOf(official, others)
        val combined = mutableListOf<Playlist>()
        while (lists.any { it.isNotEmpty() }) {
            lists.forEachIndexed { index, list ->
                if (list.isNotEmpty()) {
                    combined.add(list.first())
                    lists[index] = list.drop(1)
                }
            }
        }
        return combined
    }
}
