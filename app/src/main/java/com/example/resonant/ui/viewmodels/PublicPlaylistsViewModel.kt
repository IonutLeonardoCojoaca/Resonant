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

    enum class Filter { ALL, OFFICIAL, COMMUNITY }

    data class Stats(val playlists: Int, val creators: Int)

    private val playlistManager = PlaylistManager(application)

    // Único listado plano, ya entremezclado (oficiales de Resonant + de
    // usuarios) — ya no se agrupa por dueño en secciones separadas. Lo que se
    // publica aquí es ese listado con el filtro actual aplicado.
    private val _publicPlaylists = MutableLiveData<List<Playlist>>()
    val publicPlaylists: LiveData<List<Playlist>> = _publicPlaylists

    private val _filter = MutableLiveData(Filter.ALL)
    val filter: LiveData<Filter> = _filter

    private val _stats = MutableLiveData<Stats>()
    val stats: LiveData<Stats> = _stats

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private var cachedPlaylists: List<Playlist>? = null

    fun loadPublicPlaylists(forceRefresh: Boolean = false) {
        if (!forceRefresh && cachedPlaylists != null) {
            publish()
            return
        }

        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val playlists = playlistManager.getAllPublicPlaylists()
                val ownerNames = fetchOwnerNames(playlists)
                playlists.forEach { playlist ->
                    playlist.ownerName = if (playlist.isSystemPlaylist) {
                        "Resonant"
                    } else {
                        ownerNames[playlist.userId] ?: "Usuario"
                    }
                }

                cachedPlaylists = interleaveByOwnerType(playlists)
                publish()
            } catch (e: Exception) {
                // Si ya había datos se siguen mostrando; el error solo ocupa la
                // pantalla cuando no hay nada que enseñar.
                _error.value = "Error al cargar playlists públicas: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setFilter(filter: Filter) {
        if (_filter.value == filter) return
        _filter.value = filter
        publish()
    }

    /**
     * Un dueño suele tener varias playlists públicas: se pide cada usuario una
     * sola vez (en paralelo) en vez de una petición por playlist.
     */
    private suspend fun fetchOwnerNames(playlists: List<Playlist>): Map<String, String> {
        val ownerIds = playlists
            .filterNot { it.isSystemPlaylist }
            .mapNotNull { it.userId?.takeIf(String::isNotEmpty) }
            .distinct()

        return ownerIds.map { userId ->
            viewModelScope.async {
                val name = try {
                    playlistManager.getUserById(userId).name
                } catch (_: Exception) {
                    null
                }
                userId to (name?.takeIf { it.isNotBlank() } ?: "Usuario")
            }
        }.awaitAll().toMap()
    }

    private fun publish() {
        val all = cachedPlaylists ?: return
        val community = all.filterNot { it.isSystemPlaylist }
        val creators = community.mapNotNull { it.userId }.distinct().size +
            if (community.size < all.size) 1 else 0
        _stats.value = Stats(playlists = all.size, creators = creators)

        _publicPlaylists.value = when (_filter.value) {
            Filter.OFFICIAL -> all.filter { it.isSystemPlaylist }
            Filter.COMMUNITY -> community
            else -> all
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
