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
import com.example.resonant.data.network.ExploreSession
import com.example.resonant.explore.ExploreV2Flag
import com.example.resonant.managers.UserManager
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
import com.example.resonant.managers.StreakManager
import com.example.resonant.data.network.StreakStatusDTO
import com.example.resonant.streak.SongOfDayResult
import com.example.resonant.streak.SongOfDayRetryPolicy
import com.example.resonant.streak.SongOfDayUi
import com.example.resonant.streak.StreakResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
    private var artistMixesPending = false

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
        ensureOwner()
        if (_mode.value == ExploreMode.Loading && firstContentStartedAt == 0L) {
            firstContentStartedAt = android.os.SystemClock.elapsedRealtime()
        }
        val app = getApplication<Application>()
        exploreJob = viewModelScope.launch {
            val tz = ZoneId.systemDefault().id
            layoutTz = tz
            // La copia de sesión se pinta antes de consultar las capacidades (que, caducadas, son una petición
            // de red): solo existe si Explore v2 estaba activo en esta sesión, y si ya no lo está se pasa a legacy.
            // Si lo que hay en pantalla ya es esa copia (mismo ViewModel, misma ETag), no se decodifica otra vez.
            val copy = exploreRepository.cached(tz)
            if (copy != null && (layout == null || layout?.etag != copy.etag)) show(stateFrom(copy))
            // El layout se pide a la vez que las capacidades cuando v2 estaba activo la última vez (en memoria o
            // guardado) o hay copia: un viaje de red hasta el primer contenido en vez de dos encadenados.
            val eager = ExploreModeResolver.shouldLoadLayoutEagerly(
                lastKnownV2 = CapabilitiesManager.isExploreV2Enabled() || ExploreV2Flag.isEnabled(app),
                hasCopy = copy != null
            )
            val eagerLoad = if (eager) async { exploreRepository.load(tz) } else null
            if (!CapabilitiesManager.refresh(app).exploreV2) {
                eagerLoad?.cancel()
                showLegacy()
                return@launch
            }

            val result = eagerLoad?.await() ?: exploreRepository.load(tz)
            when (ExploreModeResolver.afterLoad(result, hasCopy = copy != null)) {
                Action.SHOW_FRESH -> {
                    val session = (result as ExploreLoadResult.Ok).session
                    show(stateFrom(session))
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
        // La última lista conocida se pinta al instante (el ViewModel es nuevo en cada visita) y se revalida.
        if (_liveChannels.value.isNullOrEmpty()) {
            LiveManager.get(getApplication()).lastChannels()?.channels?.let {
                _liveChannels.value = LiveCardMapper.map(it, System.currentTimeMillis(), ZoneId.systemDefault())
            }
        }
        liveJob = viewModelScope.launch {
            val response = LiveManager.get(getApplication()).loadChannels()
            _liveChannels.value = response?.channels
                ?.let { LiveCardMapper.map(it, System.currentTimeMillis(), ZoneId.systemDefault()) }
                .orEmpty()
        }
    }

    /** Decodifica el layout (Gson por elemento) fuera del hilo principal; el estado se aplica después en Main. */
    private suspend fun stateFrom(session: ExploreSession): ExploreLayoutState =
        withContext(Dispatchers.Default) { ExploreModeResolver.stateFrom(session, layoutReducer) }

    /** Loads a pending section once; a failed one is not retried until Explore is opened again. */
    fun requestDeferred(id: String) {
        val current = layout ?: return
        if (id !in current.pending || id in current.inFlight || id in current.failedOnce) return
        reduce(ExploreLayoutEvent.DeferredRequested(id))
        viewModelScope.launch {
            val event = when (val result = exploreRepository.loadSection(id, layoutTz)) {
                is ExploreSectionResult.Loaded -> ExploreLayoutEvent.DeferredMapped(
                    id, withContext(Dispatchers.Default) { layoutReducer.mapDeferred(result.section) }
                )
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

    /** La pantalla ya mostró el error: no debe reaparecer al recrear la vista. */
    fun consumeError() {
        _error.value = null
    }

    // region Dueño y caducidad (el ViewModel vive en la Activity: sobrevive al cambio de pestaña)

    private var loadedOwnerId: String? = null
    private val loadedAtMs = HashMap<String, Long>()

    /** Animación de entrada de las quick cards: una vez por sesión, no en cada visita a la pestaña. */
    var quickCardsAnimated = false

    /** Si la sección se cargó hace menos de [DATA_TTL_MS] no se vuelve a pedir al volver a la pestaña. */
    private fun isFresh(key: String): Boolean =
        loadedAtMs[key]?.let { System.currentTimeMillis() - it < DATA_TTL_MS } == true

    private fun markLoaded(key: String) {
        loadedAtMs[key] = System.currentTimeMillis()
    }

    /** Otra cuenta en el mismo proceso: lo cargado pertenece al usuario anterior (mismo criterio que HomeViewModel). */
    private fun ensureOwner() {
        val ownerId = UserManager(getApplication()).getUserId()
        if (loadedOwnerId != null && loadedOwnerId != ownerId) clearForOwnerChange()
        loadedOwnerId = ownerId
    }

    private fun clearForOwnerChange() {
        exploreJob?.cancel()
        artistMixesJob?.cancel()
        liveJob?.cancel()
        layout = null
        loadedAtMs.clear()
        quickCardsAnimated = false
        _mode.value = ExploreMode.Loading
        _genres.value = emptyList()
        _artistEssentials.value = emptyList()
        _artistRadios.value = emptyList()
        _mostListenedArtists.value = emptyList()
        _newReleaseAlbums.value = emptyList()
        _publicPlaylists.value = emptyList()
        _recommendedAlbums.value = emptyList()
        _recentArtists.value = emptyList()
        _liveChannels.value = emptyList()
    }

    // endregion

    /** Medición (debug): ms desde la primera apertura hasta que la pantalla deja de estar en `Loading`. */
    private var firstContentStartedAt = 0L

    private fun logFirstContent(mode: String) {
        if (firstContentStartedAt == 0L || !com.example.resonant.BuildConfig.DEBUG) return
        Log.d("ExploreTiming", "first content ($mode) after ${android.os.SystemClock.elapsedRealtime() - firstContentStartedAt} ms")
        firstContentStartedAt = 0L
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
        if (_mode.value != ExploreMode.Loading) {
            logFirstContent("dynamic")
            if (artistMixesPending) loadArtistMixes()
        }
    }

    private fun showLegacy() {
        layout = null
        if (_mode.value != ExploreMode.Legacy) _mode.value = ExploreMode.Legacy
        logFirstContent("legacy")
        if (artistMixesPending) loadArtistMixes()
    }

    // endregion

    // region Racha y canción del día (solo con features.streaks)

    private val streakManager = StreakManager.get(application)

    /** Null hides the chip: streaks off, disabled on the server or the request failed. */
    private val _streak = MutableLiveData<StreakStatusDTO?>(null)
    val streak: LiveData<StreakStatusDTO?> get() = _streak
    private var streakJob: Job? = null

    private val _songOfDay = MutableLiveData<SongOfDayUi>(SongOfDayUi.Hidden)
    val songOfDay: LiveData<SongOfDayUi> get() = _songOfDay
    private var songOfDayJob: Job? = null
    private var songOfDayRetries = 0
    private var songOfDayExhausted = false

    /** [force] after a streak event; otherwise the manager's 30 s cache applies. */
    fun loadStreak(force: Boolean = false) {
        if (!force && streakJob?.isActive == true) return
        streakJob?.cancel()
        streakJob = viewModelScope.launch {
            if (!CapabilitiesManager.refresh(getApplication()).streaks) {
                _streak.value = null
                return@launch
            }
            _streak.value = when (val result = streakManager.getStatus(force)) {
                is StreakResult.Ok -> result.status
                StreakResult.Disabled -> {
                    CapabilitiesManager.refresh(getApplication(), force = true)
                    null
                }
                StreakResult.Failed -> null
            }
        }
    }

    /** New opening of Explorar: the song of the day gets its retries back. */
    fun resetSongOfDay() {
        songOfDayRetries = 0
        songOfDayExhausted = false
    }

    /** Asks for the song of the day while Explorar is visible, retrying a bounded number of times on 202. */
    fun startSongOfDay() {
        if (songOfDayJob?.isActive == true || songOfDayExhausted) return
        songOfDayJob = viewModelScope.launch {
            if (!CapabilitiesManager.refresh(getApplication()).streaks) {
                _songOfDay.value = SongOfDayUi.Hidden
                return@launch
            }
            while (true) {
                val result = streakManager.getSongOfDay()
                if (result == SongOfDayResult.Disabled) CapabilitiesManager.refresh(getApplication(), force = true)
                val step = SongOfDayUi.from(result, songOfDayRetries)
                _songOfDay.value = step.ui
                val waitSeconds = step.retryAfterSeconds ?: break
                delay(waitSeconds * 1000L)
                songOfDayRetries++
            }
            songOfDayExhausted = _songOfDay.value == SongOfDayUi.Hidden && songOfDayRetries >= SongOfDayRetryPolicy.MAX_RETRIES
        }
    }

    fun stopSongOfDay() {
        songOfDayJob?.cancel()
        songOfDayJob = null
    }

    // endregion

    // region Resumen mensual (solo con features.monthlyRecap)

    private val recapManager = com.example.resonant.managers.RecapManager.get(application)

    /** Null oculta el banner: función apagada, sin resumen nuevo, descartado o error. */
    private val _recapBanner = MutableLiveData<com.example.resonant.data.network.RecapSummaryDTO?>(null)
    val recapBanner: LiveData<com.example.resonant.data.network.RecapSummaryDTO?> get() = _recapBanner
    private var recapJob: Job? = null

    fun loadRecapBanner() {
        if (recapJob?.isActive == true) return
        recapJob = viewModelScope.launch {
            if (!CapabilitiesManager.refresh(getApplication()).monthlyRecap) {
                _recapBanner.value = null
                return@launch
            }
            _recapBanner.value = when (val result = recapManager.list()) {
                is com.example.resonant.managers.RecapResult.Ok -> com.example.resonant.recap.RecapBannerPolicy.bannerFor(
                    result.value, System.currentTimeMillis(), recapManager.dismissedPeriods()
                )
                com.example.resonant.managers.RecapResult.Disabled -> {
                    CapabilitiesManager.refresh(getApplication(), force = true)
                    null
                }
                else -> null
            }
        }
    }

    fun dismissRecapBanner() {
        val periodKey = _recapBanner.value?.periodKey ?: return
        recapManager.dismiss(periodKey)
        _recapBanner.value = null
    }

    // endregion

    fun loadPopularGenres() {
        if (_genres.value != null && isFresh(KEY_GENRES)) return
        markLoaded(KEY_GENRES)
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
        if (_recommendedAlbums.value != null && isFresh(KEY_RECOMMENDED_ALBUMS)) return
        markLoaded(KEY_RECOMMENDED_ALBUMS)
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
        if (_recentArtists.value != null && isFresh(KEY_RECENT_ARTISTS)) return
        markLoaded(KEY_RECENT_ARTISTS)
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
        if (_publicPlaylists.value != null && isFresh(KEY_PUBLIC_PLAYLISTS)) return
        markLoaded(KEY_PUBLIC_PLAYLISTS)
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
        if (artistMixesJob?.isActive == true) return
        if (_artistEssentials.value != null && isFresh(KEY_ARTIST_MIXES)) return
        // Van bajo el primer pliegue: se piden cuando ya hay contenido en pantalla, para no competir con el
        // layout por las 8 conexiones por host, y de 4 en 4.
        if (_mode.value == ExploreMode.Loading) {
            artistMixesPending = true
            return
        }
        artistMixesPending = false
        markLoaded(KEY_ARTIST_MIXES)
        val app = getApplication<Application>()
        artistMixesJob = viewModelScope.launch {
            try {
                val service = ApiClient.getArtistService(app)
                val artists = service.getTopArtists(period = StatsPeriod.WEEKLY.value.toString(), limit = ARTIST_MIXES_LIMIT)
                    .ifEmpty { service.getTopArtists(period = StatsPeriod.ALL_TIME.value.toString(), limit = ARTIST_MIXES_LIMIT) }
                val gate = Semaphore(ARTIST_MIXES_PARALLELISM)
                val mixes = artists.map { artist ->
                    async { gate.withPermit { artist to ArtistManager.getArtistSmartPlaylists(app, artist.id) } }
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
        if (_mostListenedArtists.value != null && isFresh(KEY_MOST_LISTENED)) return
        markLoaded(KEY_MOST_LISTENED)
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
        if (_newReleaseAlbums.value != null && isFresh(KEY_NEW_RELEASES)) return
        markLoaded(KEY_NEW_RELEASES)
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
        const val ARTIST_MIXES_PARALLELISM = 4
        /** Caducidad de las secciones fijas y de los mixes de artistas (misma ventana que HomeViewModel). */
        const val DATA_TTL_MS = 15 * 60 * 1000L
        const val KEY_GENRES = "genres"
        const val KEY_RECOMMENDED_ALBUMS = "recommendedAlbums"
        const val KEY_RECENT_ARTISTS = "recentArtists"
        const val KEY_PUBLIC_PLAYLISTS = "publicPlaylists"
        const val KEY_MOST_LISTENED = "mostListened"
        const val KEY_NEW_RELEASES = "newReleases"
        const val KEY_ARTIST_MIXES = "artistMixes"
    }
}
