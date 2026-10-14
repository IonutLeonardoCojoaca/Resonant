package com.example.resonant.ui.viewmodels

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.ArtistSmartPlaylist
import com.example.resonant.data.models.StatsPeriod
import com.example.resonant.data.models.Genre
import com.example.resonant.data.models.Playlist
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.ExploreLoadResult
import com.example.resonant.data.network.ExploreRepository
import com.example.resonant.data.network.ExploreSectionResult
import com.example.resonant.explore.ExploreGreeting
import com.example.resonant.explore.ExploreLayoutEvent
import com.example.resonant.explore.ExploreLayoutReducer
import com.example.resonant.explore.ExploreLayoutState
import com.example.resonant.explore.ExploreMode
import com.example.resonant.explore.ExploreModeResolver
import com.example.resonant.explore.ExploreModeResolver.Action
import com.example.resonant.live.LiveCardMapper
import com.example.resonant.live.LiveCardUi
import com.example.resonant.live.LiveManager
import com.example.resonant.managers.AriaPickFeedbackResult
import com.example.resonant.managers.AriaPicksManager
import com.example.resonant.managers.AlbumManager
import com.example.resonant.managers.ArtistManager
import com.example.resonant.managers.CapabilitiesManager
import com.example.resonant.managers.GenreManager
import com.example.resonant.managers.PlaylistManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.time.ZoneId

class ExploreViewModel(application: Application) : AndroidViewModel(application) {

    private val genreManager = GenreManager(application)
    private val playlistManager = PlaylistManager(application)

    private val _genres = MutableLiveData<List<Genre>>()
    val genres: LiveData<List<Genre>> get() = _genres

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> get() = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> get() = _error

    /** Essentials / Radio (smart playlists por artista) de un artista del ranking. */
    data class ArtistMix(val artist: Artist, val playlist: ArtistSmartPlaylist)

    private val _artistEssentials = MutableLiveData<List<ArtistMix>>()
    val artistEssentials: LiveData<List<ArtistMix>> get() = _artistEssentials
    private val _artistRadios = MutableLiveData<List<ArtistMix>>()
    val artistRadios: LiveData<List<ArtistMix>> get() = _artistRadios
    private var artistMixesJob: Job? = null

    private val _mostListenedArtists = MutableLiveData<List<Artist>>()
    val mostListenedArtists: LiveData<List<Artist>> get() = _mostListenedArtists
    private val _mostListenedArtistsLoading = MutableLiveData<Boolean>()
    val mostListenedArtistsLoading: LiveData<Boolean> get() = _mostListenedArtistsLoading

    private val _newReleaseAlbums = MutableLiveData<List<Album>>()
    val newReleaseAlbums: LiveData<List<Album>> get() = _newReleaseAlbums
    private val _newReleaseAlbumsLoading = MutableLiveData<Boolean>()
    val newReleaseAlbumsLoading: LiveData<Boolean> get() = _newReleaseAlbumsLoading

    private val _publicPlaylists = MutableLiveData<List<Playlist>>()
    val publicPlaylists: LiveData<List<Playlist>> get() = _publicPlaylists
    private val _publicPlaylistsLoading = MutableLiveData<Boolean>()
    val publicPlaylistsLoading: LiveData<Boolean> get() = _publicPlaylistsLoading

    private val _recommendedAlbums = MutableLiveData<List<Album>>()
    val recommendedAlbums: LiveData<List<Album>> get() = _recommendedAlbums
    private val _recommendedAlbumsLoading = MutableLiveData<Boolean>()
    val recommendedAlbumsLoading: LiveData<Boolean> get() = _recommendedAlbumsLoading

    // "Artistas para ti" (recomendados) ya se muestra en Home — en Explore
    // esta fila es de descubrimiento: artistas recién añadidos al catálogo.
    private val _recentArtists = MutableLiveData<List<Artist>>()
    val recentArtists: LiveData<List<Artist>> get() = _recentArtists
    private val _recentArtistsLoading = MutableLiveData<Boolean>()
    val recentArtistsLoading: LiveData<Boolean> get() = _recentArtistsLoading

    // region Explore v2 (dynamic sections); the legacy loads below stay as the fallback.

    private val exploreRepository = ExploreRepository.get(application)
    private val ariaPicksManager = AriaPicksManager.get(application)
    private val layoutReducer = ExploreLayoutReducer()

    private val _mode = MutableLiveData<ExploreMode>(ExploreMode.Loading)
    val mode: LiveData<ExploreMode> get() = _mode

    /** One-shot message of the Aria picks feedback; the screen clears it with [consumeAriaMessage]. */
    private val _ariaMessage = MutableLiveData<String?>()
    val ariaMessage: LiveData<String?> get() = _ariaMessage

    /** Resonant Live cards for "En el momento"; empty with Live off or on any failure. */
    private val _liveChannels = MutableLiveData<List<LiveCardUi>>(emptyList())
    val liveChannels: LiveData<List<LiveCardUi>> get() = _liveChannels
    private var liveJob: Job? = null

    private var layout: ExploreLayoutState? = null
    private var layoutTz: String = ZoneId.systemDefault().id
    private var exploreJob: Job? = null

    /**
     * Called on every opening of the screen: paints the session copy at once and revalidates it
     * (304 keeps it). Rebuilding from the copy also retries deferred sections that failed last time.
     */
    fun loadExplore() {
        if (exploreJob?.isActive == true) return
        exploreJob = viewModelScope.launch {
            if (!CapabilitiesManager.refresh(getApplication()).exploreV2) {
                showLegacy()
                return@launch
            }
            val tz = ZoneId.systemDefault().id
            layoutTz = tz
            val copy = exploreRepository.cached(tz)
            copy?.let { show(ExploreModeResolver.stateFrom(it, layoutReducer)) }

            val result = exploreRepository.load(tz)
            when (ExploreModeResolver.afterLoad(result, hasCopy = copy != null)) {
                Action.SHOW_FRESH -> {
                    val session = (result as ExploreLoadResult.Ok).session
                    show(ExploreModeResolver.stateFrom(session, layoutReducer))
                }
                Action.KEEP_CURRENT -> Unit
                Action.LEGACY -> showLegacy()
                Action.DISABLE_AND_LEGACY -> {
                    CapabilitiesManager.refresh(getApplication(), force = true)
                    showLegacy()
                }
            }
            Log.i("ExploreVM", "explore mode: ${_mode.value?.javaClass?.simpleName}")
        }
    }

    /** On every visit; the manager keeps the listing 15 s, so quick returns do not ask again. */
    fun loadLiveChannels() {
        if (liveJob?.isActive == true) return
        liveJob = viewModelScope.launch {
            val response = LiveManager.get(getApplication()).loadChannels()
            _liveChannels.value = response?.channels
                ?.let { LiveCardMapper.map(it, System.currentTimeMillis(), ZoneId.systemDefault()) }
                .orEmpty()
        }
    }

    /** Loads a pending section once; a failed one is not retried until Explore is opened again. */
    fun requestDeferred(id: String) {
        val current = layout ?: return
        if (id !in current.pending || id in current.inFlight || id in current.failedOnce) return
        reduce(ExploreLayoutEvent.DeferredRequested(id))
        viewModelScope.launch {
            val event = when (val result = exploreRepository.loadSection(id, layoutTz)) {
                is ExploreSectionResult.Loaded -> ExploreLayoutEvent.DeferredLoaded(id, result.section)
                ExploreSectionResult.Missing -> ExploreLayoutEvent.DeferredMissing(id)
                ExploreSectionResult.Failed -> ExploreLayoutEvent.DeferredFailed(id)
            }
            reduce(event)
        }
    }

    /** "No me interesa" hides the pick at once and brings it back if the request fails. */
    fun sendAriaFeedback(songId: String, liked: Boolean) {
        if (!liked) dismissAriaPick(songId)
        viewModelScope.launch {
            when (ariaPicksManager.sendFeedback(songId, liked)) {
                AriaPickFeedbackResult.Sent -> {
                    if (liked) _ariaMessage.value = ARIA_LIKED_MESSAGE
                }
                AriaPickFeedbackResult.Gone -> {
                    if (liked) dismissAriaPick(songId)
                }
                AriaPickFeedbackResult.Failed -> {
                    if (!liked) {
                        reduce(ExploreLayoutEvent.AriaRestored(songId))
                        rememberDismissed()
                    }
                    _ariaMessage.value = ARIA_FAILED_MESSAGE
                }
            }
        }
    }

    fun consumeAriaMessage() {
        _ariaMessage.value = null
    }

    private fun dismissAriaPick(songId: String) {
        reduce(ExploreLayoutEvent.AriaDismissed(songId))
        rememberDismissed()
    }

    private fun rememberDismissed() {
        val current = layout ?: return
        exploreRepository.rememberDismissed(layoutTz, current.etag, current.dismissedAriaSongIds)
    }

    private fun reduce(event: ExploreLayoutEvent) {
        show(layoutReducer.reduce(layout, event) ?: return)
    }

    private fun show(state: ExploreLayoutState) {
        layout = state
        val userName = getApplication<Application>()
            .getSharedPreferences("user_data", Context.MODE_PRIVATE)
            .getString("NAME", null)
        _mode.value = ExploreModeResolver.modeFor(state, ExploreGreeting.of(state.daypart, userName))
    }

    private fun showLegacy() {
        layout = null
        if (_mode.value != ExploreMode.Legacy) _mode.value = ExploreMode.Legacy
    }

    // endregion

    fun loadPopularGenres() {
        if (_genres.value != null) return
        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                // Solo se muestran 2 tandas de 6 (ver ExploreFragment), pedimos
                // un pequeño margen (12) en vez de las 32 que se pedían antes.
                val result = genreManager.getPopularGenres(count = 12)
                _genres.value = result
                Log.i("ExploreVM", "Generos cargados: ${result.size}")
            } catch (e: Exception) {
                _error.value = "Error al cargar generos: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadRecommendedAlbums() {
        if (_recommendedAlbums.value != null) return
        _recommendedAlbumsLoading.value = true

        viewModelScope.launch {
            try {
                val result = AlbumManager.getRecommendedAlbums(getApplication(), count = 12)
                _recommendedAlbums.value = result?.items ?: emptyList()
            } catch (e: Exception) {
                Log.w("ExploreVM", "Error cargando albumes recomendados: ${e.message}")
                _recommendedAlbums.value = emptyList()
            } finally {
                _recommendedAlbumsLoading.value = false
            }
        }
    }

    fun loadRecentArtists() {
        if (_recentArtists.value != null) return
        _recentArtistsLoading.value = true

        viewModelScope.launch {
            try {
                _recentArtists.value = ArtistManager.getRecentlyAddedArtists(getApplication(), limit = 12)
            } catch (e: Exception) {
                Log.w("ExploreVM", "Error cargando artistas recientes: ${e.message}")
                _recentArtists.value = emptyList()
            } finally {
                _recentArtistsLoading.value = false
            }
        }
    }

    fun loadAllGenres() {
        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                _genres.value = genreManager.getPopularGenres(count = 100)
            } catch (e: Exception) {
                _error.value = "Error al cargar generos: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadPublicPlaylists() {
        if (_publicPlaylists.value != null) return
        _publicPlaylistsLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val playlists = playlistManager.getAllPublicPlaylists().take(7)
                _publicPlaylists.value = playlists.map { playlist ->
                    async {
                        when {
                            playlist.isSystemPlaylist -> playlist.ownerName = "Resonant"
                            !playlist.userId.isNullOrEmpty() -> {
                                playlist.ownerName = try {
                                    playlistManager.getUserById(playlist.userId).name ?: "Usuario"
                                } catch (_: Exception) {
                                    "Usuario"
                                }
                            }
                            else -> playlist.ownerName = "Usuario"
                        }
                        playlist
                    }
                }.awaitAll()
            } catch (e: Exception) {
                Log.w("ExploreVM", "Error cargando playlists publicas: ${e.message}")
                _publicPlaylists.value = emptyList()
            } finally {
                _publicPlaylistsLoading.value = false
            }
        }
    }

    /**
     * Essentials y Radios de los artistas más escuchados de la semana (o de siempre si
     * la semana aún no tiene datos). No hay endpoint que los devuelva juntos: se pide el
     * ranking y después las smart playlists de cada artista en paralelo (ArtistManager
     * las cachea). Ambas listas conservan el orden del ranking.
     */
    fun loadArtistMixes() {
        if (_artistEssentials.value != null || artistMixesJob?.isActive == true) return
        val app = getApplication<Application>()
        artistMixesJob = viewModelScope.launch {
            try {
                val service = ApiClient.getArtistService(app)
                val artists = service.getTopArtists(period = StatsPeriod.WEEKLY.value.toString(), limit = ARTIST_MIXES_LIMIT)
                    .ifEmpty { service.getTopArtists(period = StatsPeriod.ALL_TIME.value.toString(), limit = ARTIST_MIXES_LIMIT) }
                val mixes = artists.map { artist ->
                    async { artist to ArtistManager.getArtistSmartPlaylists(app, artist.id) }
                }.awaitAll()
                fun pick(type: String) = mixes.mapNotNull { (artist, playlists) ->
                    playlists.firstOrNull { it.playlistType.equals(type, ignoreCase = true) }?.let { ArtistMix(artist, it) }
                }
                _artistEssentials.value = pick("Essentials")
                _artistRadios.value = pick("Radio")
            } catch (e: Exception) {
                Log.w("ExploreVM", "Error cargando essentials/radios de artistas: ${e.message}")
                _artistEssentials.value = emptyList()
                _artistRadios.value = emptyList()
            }
        }
    }

    fun loadMostListenedArtists() {
        if (_mostListenedArtists.value != null) return
        _mostListenedArtistsLoading.value = true

        viewModelScope.launch {
            try {
                val topArtists = ArtistManager.getMostListenedArtists(getApplication(), limit = 2)
                _mostListenedArtists.value = topArtists.ifEmpty {
                    ArtistManager.getRecentlyAddedArtists(getApplication(), limit = 2)
                }
            } catch (e: Exception) {
                Log.w("ExploreVM", "Error cargando artistas mas escuchados: ${e.message}")
                _mostListenedArtists.value = emptyList()
            } finally {
                _mostListenedArtistsLoading.value = false
            }
        }
    }

    fun loadNewReleaseAlbums() {
        if (_newReleaseAlbums.value != null) return
        _newReleaseAlbumsLoading.value = true

        viewModelScope.launch {
            try {
                _newReleaseAlbums.value = AlbumManager.getNewReleaseAlbums(getApplication(), limit = 20)
            } catch (e: Exception) {
                Log.w("ExploreVM", "Error cargando nuevos albumes: ${e.message}")
                _newReleaseAlbums.value = emptyList()
            } finally {
                _newReleaseAlbumsLoading.value = false
            }
        }
    }

    private companion object {
        const val ARIA_LIKED_MESSAGE = "Gracias, Aria lo tendrá en cuenta"
        const val ARIA_FAILED_MESSAGE = "No se ha podido enviar, inténtalo de nuevo"
        const val ARTIST_MIXES_LIMIT = 10
    }
}
