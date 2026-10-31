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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class FavoritesViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = FavoriteManager(app.applicationContext)
    private val dataExpirationTime = 5 * 60 * 1000L
    private var lastFavoriteSongsFetchTime = 0L
    private var favoriteSongsLoading = false
    /** Carga de canciones en vuelo; un `forceRefresh` la cancela para que su respuesta (anterior al cambio) no pise el estado. */
    private var favoriteSongsJob: Job? = null
    /** Canciones con un alta/baja en curso: un segundo toque no lanza otra petición. */
    private val songTogglesInFlight = mutableSetOf<String>()
    private var lastFavoriteArtistsFetchTime = 0L
    private var favoriteArtistsLoading = false
    private var lastFavoriteAlbumsFetchTime = 0L
    private var favoriteAlbumsLoading = false

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

    fun loadFavoriteSongs(forceRefresh: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        val isExpired = (currentTime - lastFavoriteSongsFetchTime) > dataExpirationTime

        // Antes un forceRefresh durante una carga se descartaba y la carga vieja pisaba el cambio recién hecho.
        if (favoriteSongsLoading && !forceRefresh) return
        if (!forceRefresh && lastFavoriteSongsFetchTime > 0L && !isExpired) return

        favoriteSongsJob?.cancel()
        favoriteSongsLoading = true
        favoriteSongsJob = viewModelScope.launch {
            try {
                // null = fallo de red: se conserva lo que había (antes se mostraba "sin favoritos" 5 minutos).
                val favSongs = repo.fetchFavoriteSongs() ?: return@launch
                _favoriteSongs.value = favSongs
                _favoriteSongIds.value = favSongs.map { it.id }.toSet()
                lastFavoriteSongsFetchTime = System.currentTimeMillis()
            } finally {
                favoriteSongsLoading = false
            }
        }
    }

    fun loadFavoriteArtists(forceRefresh: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        val isExpired = (currentTime - lastFavoriteArtistsFetchTime) > dataExpirationTime

        if (favoriteArtistsLoading) return
        if (!forceRefresh && lastFavoriteArtistsFetchTime > 0L && !isExpired) return

        favoriteArtistsLoading = true
        viewModelScope.launch {
            try {
                val favArtists = repo.fetchFavoriteArtists() ?: return@launch
                _favoriteArtists.value = favArtists
                _favoriteArtistIds.value = favArtists.map { it.id }.toSet()
                lastFavoriteArtistsFetchTime = System.currentTimeMillis()
            } finally {
                favoriteArtistsLoading = false
            }
        }
    }

    fun loadFavoriteAlbums(forceRefresh: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        val isExpired = (currentTime - lastFavoriteAlbumsFetchTime) > dataExpirationTime

        if (favoriteAlbumsLoading) return
        if (!forceRefresh && lastFavoriteAlbumsFetchTime > 0L && !isExpired) return

        favoriteAlbumsLoading = true
        viewModelScope.launch {
            try {
                val favAlbums = repo.fetchFavoriteAlbums() ?: return@launch
                _favoriteAlbums.value = favAlbums
                _favoriteAlbumIds.value = favAlbums.map { it.id }.toSet()
                lastFavoriteAlbumsFetchTime = System.currentTimeMillis()
            } finally {
                favoriteAlbumsLoading = false
            }
        }
    }


    // =========================================================================
    //                            CANCIONES (SONGS)
    // =========================================================================

    fun toggleFavoriteSong(song: Song, onResult: (Boolean, Boolean) -> Unit = { _, _ -> }) {
        // Doble toque: la segunda petición leería el mismo estado y desharía la primera.
        if (!songTogglesInFlight.add(song.id)) return
        viewModelScope.launch {
            try {
                val currentFavoritesIds = _favoriteSongIds.value.orEmpty()
                val isCurrentlyFavorite = currentFavoritesIds.contains(song.id)

                if (isCurrentlyFavorite) {
                    // 🚀 OPTIMISTA: Borrar de la UI inmediatamente
                    val updatedList = _favoriteSongs.value.orEmpty().filter { it.id != song.id }
                    _favoriteSongs.value = updatedList
                    _favoriteSongIds.value = updatedList.map { it.id }.toSet()

                    // Llamada a BD/Red
                    val result = repo.deleteFavoriteSong(song.id)
                    if (!result) loadFavoriteSongs(forceRefresh = true) // Revertir si falla
                    onResult(result, false)
                } else {
                    // 🚀 OPTIMISTA también al añadir: el corazón se enciende al momento y la lista se completa
                    // en segundo plano (antes: petición, espera y recarga completa de favoritos).
                    _favoriteSongIds.value = currentFavoritesIds + song.id
                    _favoriteSongs.value = listOf(song) + _favoriteSongs.value.orEmpty().filter { it.id != song.id }

                    val result = repo.addFavoriteSong(song.id)
                    if (result) {
                        loadFavoriteSongs(forceRefresh = true) // orden y favoritedAt reales del servidor
                    } else {
                        _favoriteSongIds.value = _favoriteSongIds.value.orEmpty() - song.id
                        _favoriteSongs.value = _favoriteSongs.value.orEmpty().filter { it.id != song.id }
                    }
                    onResult(result, true)
                }
            } finally {
                songTogglesInFlight.remove(song.id)
            }
        }
    }


    // =========================================================================
    //                            ARTISTAS (ARTISTS)
    // =========================================================================

    fun addFavoriteArtist(artist: Artist, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val result = repo.addFavoriteArtist(artist.id)
            if (result) loadFavoriteArtists(forceRefresh = true)
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
                loadFavoriteArtists(forceRefresh = true)
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
            if (result) loadFavoriteAlbums(forceRefresh = true)
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
                loadFavoriteAlbums(forceRefresh = true)
            }
            onResult(result)
        }
    }
}
