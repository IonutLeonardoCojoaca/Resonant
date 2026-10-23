package com.example.resonant.aria

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.SessionResult
import com.example.resonant.playback.PlaybackControllerConnection
import com.example.resonant.playback.PlaybackStateRepository
import com.example.resonant.playback.QueueCommands
import com.example.resonant.ui.viewmodels.AriaAction
import com.example.resonant.unisound.UnisoundControl
import com.example.resonant.unisound.UnisoundControlOrigin
import com.example.resonant.unisound.UnisoundManager
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Executes pending Aria effects at Activity level so they work from every screen. */
class AriaClientActionExecutor(
    context: Context,
    private val scope: CoroutineScope,
    private val onFavoriteStateChanged: () -> Unit = {},
    /** Screens and sheets Aria can open (add-aria-context-v2); provided by MainActivity. */
    private val featureNavigator: AriaFeatureNavigator = AriaFeatureNavigator.NONE,
    private val onFeedback: (message: String, success: Boolean) -> Unit
) {
    private val appContext = context.applicationContext
    private val idempotency = PersistentAriaActionIdempotency(appContext)
    private val playbackHandler = AriaPlaybackActionHandler(
        playbackGateway = AndroidAriaPlaybackGateway(appContext),
        idempotency = idempotency,
        telemetry = FirebaseAriaActionTelemetry(appContext),
        onFeedback = onFeedback
    )
    private val favoriteHandler = AriaFavoriteActionHandler(
        favoriteGateway = AndroidAriaFavoriteGateway(appContext, onFavoriteStateChanged),
        idempotency = idempotency,
        onFeedback = onFeedback
    )
    
    private val unisoundHandler = AriaUnisoundActionHandler(
        gateway = UnisoundAriaGateway(appContext),
        idempotency = idempotency,
        telemetry = FirebaseAriaActionTelemetry(appContext)
    )

    private val featureHandler = AriaFeatureActionHandler(
        gateway = AndroidAriaFeatureGateway(appContext, featureNavigator) { message ->
            onFeedback(message, false)
        },
        idempotency = idempotency,
        telemetry = FirebaseAriaActionTelemetry(appContext)
    )

    /**
     * Una respuesta de Aria puede traer varias acciones seguidas ("pon X y añade Y a la cola").
     * Se ejecutan de una en una y en el orden recibido: cada acción espera a que termine la anterior.
     * El Mutex es FIFO y [scope] arranca en Main.immediate, así que el orden de llegada se conserva.
     */
    private val executionLock = Mutex()

    fun execute(action: AriaAction) {
        when (action.type) {
            in AriaFeatureActionHandler.CLIENT_ACTION_TYPES -> {
                scope.launch { executionLock.withLock { featureHandler.handle(action) } }
            }
            "controlar_reproduccion" -> {
                if (action.isPendingClientAction()) {
                    scope.launch {
                        executionLock.withLock {
                            if (!unisoundHandler.handle(action)) playbackHandler.execute(action)
                        }
                    }
                }
            }
            "guardar_actual", "quitar_actual" -> {
                // Ejecutamos siempre que nos llegue guardar_actual o quitar_actual,
                // ya que puede que el backend no envíe correctamente los flags de pending_client
                scope.launch { executionLock.withLock { favoriteHandler.execute(action) } }
            }
        }
    }
}

private class UnisoundAriaGateway(context: Context) : AriaUnisoundGateway {
    private val manager = UnisoundManager.get(context)

    override fun currentSessionId(): String? = manager.currentSessionIdOrNull()

    override fun control(control: UnisoundControl) =
        manager.control(control, positionMs = null, origin = UnisoundControlOrigin.ARIA)
}

/** Bridges Aria to Media3 using only the authoritative song_id supplied by the backend. */
private class AndroidAriaPlaybackGateway(context: Context) : AriaPlaybackGateway {
    private val appContext = context.applicationContext

    override suspend fun control(command: AriaTransportControl) {
        val action = when (command) {
            AriaTransportControl.PLAY -> com.example.resonant.services.MusicPlaybackService.ACTION_RESUME
            AriaTransportControl.PAUSE -> com.example.resonant.services.MusicPlaybackService.ACTION_PAUSE
            AriaTransportControl.NEXT -> com.example.resonant.services.MusicPlaybackService.ACTION_NEXT
            AriaTransportControl.PREVIOUS -> com.example.resonant.services.MusicPlaybackService.ACTION_PREVIOUS
            AriaTransportControl.SHUFFLE -> com.example.resonant.services.MusicPlaybackService.ACTION_TOGGLE_SHUFFLE
            AriaTransportControl.REPEAT -> com.example.resonant.services.MusicPlaybackService.ACTION_TOGGLE_REPEAT
        }
        val intent = android.content.Intent(appContext, com.example.resonant.services.MusicPlaybackService::class.java).apply {
            this.action = action
        }
        appContext.startService(intent)
    }

    override suspend fun playSong(songId: String, title: String?, artist: String?) {
        val songManager = com.example.resonant.managers.SongManager(appContext)
        val song = songManager.getSongById(songId)
            ?: error("Canción no encontrada por el backend")
            
        val playIntent = android.content.Intent(appContext, com.example.resonant.services.MusicPlaybackService::class.java).apply {
            action = com.example.resonant.services.MusicPlaybackService.ACTION_PLAY
            putExtra(com.example.resonant.services.MusicPlaybackService.EXTRA_CURRENT_SONG, song)
            putExtra(com.example.resonant.services.MusicPlaybackService.EXTRA_CURRENT_INDEX, 0)
            putParcelableArrayListExtra(com.example.resonant.services.MusicPlaybackService.SONG_LIST, arrayListOf(song))
            putExtra(com.example.resonant.services.MusicPlaybackService.EXTRA_QUEUE_SOURCE, com.example.resonant.playback.QueueSource.SEARCH)
            putExtra(com.example.resonant.services.MusicPlaybackService.EXTRA_QUEUE_SOURCE_ID, "aria")
        }
        startPlayAndAwaitQueue(playIntent)
    }

    override suspend fun queueSong(songId: String) {
        val result = PlaybackControllerConnection.sendQueueCommand(
            context = appContext,
            action = QueueCommands.ADD,
            arguments = Bundle().apply {
                putString(QueueCommands.ARG_SONG_ID, songId)
            }
        )
        check(result.resultCode == SessionResult.RESULT_SUCCESS) {
            result.extras.getString(QueueCommands.RESULT_MESSAGE)
                ?: "No se pudo añadir a la cola"
        }
    }

    override suspend fun playArtistEssentials(artistId: String, artistName: String?) {
        val songs = com.example.resonant.managers.ArtistManager.getEssentials(appContext, artistId)
        if (songs.isEmpty()) error("No se encontraron canciones imprescindibles")
        playSongList(songs, "aria_essentials")
    }

    override suspend fun playArtistRadio(artistId: String, artistName: String?) {
        val songs = com.example.resonant.managers.ArtistManager.getRadios(appContext, artistId)
        if (songs.isEmpty()) error("No se encontró la radio del artista")
        playSongList(songs, "aria_radio")
    }

    /**
     * ACTION_PLAY sustituye la cola entera. Si la siguiente acción de Aria (p. ej. queue_song, que va por
     * el canal de Media3) llegase antes, la cola nueva la borraría; por eso esperamos a que el servicio
     * haya instalado su cola. Con tope de tiempo: si el play no se aplica (diálogo de Connect), seguimos.
     */
    private suspend fun startPlayAndAwaitQueue(playIntent: android.content.Intent) = withContext(Dispatchers.Main) {
        val previousQueue = PlaybackStateRepository.activeQueue
        appContext.startService(playIntent)
        withTimeoutOrNull(PLAY_APPLY_TIMEOUT_MS) {
            while (PlaybackStateRepository.activeQueue === previousQueue) delay(PLAY_APPLY_POLL_MS)
        }
    }

    private suspend fun playSongList(songs: List<com.example.resonant.data.models.Song>, queueSourceId: String) {
        val playIntent = android.content.Intent(appContext, com.example.resonant.services.MusicPlaybackService::class.java).apply {
            action = com.example.resonant.services.MusicPlaybackService.ACTION_PLAY
            putExtra(com.example.resonant.services.MusicPlaybackService.EXTRA_CURRENT_SONG, songs.first())
            putExtra(com.example.resonant.services.MusicPlaybackService.EXTRA_CURRENT_INDEX, 0)
            putParcelableArrayListExtra(com.example.resonant.services.MusicPlaybackService.SONG_LIST, ArrayList(songs))
            putExtra(com.example.resonant.services.MusicPlaybackService.EXTRA_QUEUE_SOURCE, com.example.resonant.playback.QueueSource.SEARCH)
            putExtra(com.example.resonant.services.MusicPlaybackService.EXTRA_QUEUE_SOURCE_ID, queueSourceId)
        }
        startPlayAndAwaitQueue(playIntent)
    }

    private companion object {
        const val PLAY_APPLY_TIMEOUT_MS = 4_000L
        const val PLAY_APPLY_POLL_MS = 50L
    }
}

private class AndroidAriaFavoriteGateway(
    context: Context,
    private val onFavoriteStateChanged: () -> Unit
) : AriaFavoriteGateway {
    private val favoriteManager = com.example.resonant.managers.FavoriteManager(context.applicationContext)

    override suspend fun addFavoriteSong(songId: String): Boolean {
        val result = favoriteManager.addFavoriteSong(songId)
        if (result) {
            onFavoriteStateChanged()
        }
        return result
    }

    override suspend fun removeFavoriteSong(songId: String): Boolean {
        val result = favoriteManager.deleteFavoriteSong(songId)
        if (result) {
            onFavoriteStateChanged()
        }
        return result
    }
}

/** Persists recent action_id values across SSE reconnects and Activity recreation. */
private class PersistentAriaActionIdempotency(context: Context) : AriaActionIdempotency {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun tryClaim(actionId: String?): Boolean {
        val normalized = actionId?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        val key = CLAIM_PREFIX + normalized
        synchronized(lock) {
            if (preferences.contains(key)) return false

            val previousClaims = preferences.all
                .asSequence()
                .filter { (storedKey, value) ->
                    storedKey.startsWith(CLAIM_PREFIX) && value is Long
                }
                .sortedBy { (_, value) -> value as Long }
                .toList()
            val editor = preferences.edit()
            if (previousClaims.size >= MAX_PERSISTED_IDS) {
                previousClaims
                    .take(previousClaims.size - MAX_PERSISTED_IDS + 1)
                    .forEach { (storedKey, _) -> editor.remove(storedKey) }
            }
            editor.putLong(key, System.currentTimeMillis()).apply()
            return true
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "aria_action_idempotency"
        const val CLAIM_PREFIX = "action_id_"
        const val MAX_PERSISTED_IDS = 200
        val lock = Any()
    }
}

private class FirebaseAriaActionTelemetry(context: Context) : AriaActionTelemetry {
    private val analytics = FirebaseAnalytics.getInstance(context.applicationContext)

    override fun record(event: AriaActionTelemetryEvent) {
        if (event.outcome == "error") {
            Log.w(
                TAG,
                "Aria action failed command=${event.command} reason=${event.reason} " +
                    "songId=${event.songId} actionId=${event.actionId}"
            )
        }
        analytics.logEvent(EVENT_NAME, Bundle().apply {
            putString("command", event.command.take(MAX_VALUE_LENGTH))
            putString("outcome", event.outcome.take(MAX_VALUE_LENGTH))
            event.reason?.let { putString("reason", it.take(MAX_VALUE_LENGTH)) }
            event.songId?.let { putString("song_id", it.take(MAX_VALUE_LENGTH)) }
            event.selectionMode?.let {
                putString("selection_mode", it.take(MAX_VALUE_LENGTH))
            }
            event.actionId?.let { putString("action_id", it.take(MAX_VALUE_LENGTH)) }
            event.logId?.let { putString("log_id", it.take(MAX_VALUE_LENGTH)) }
        })
    }

    private companion object {
        const val TAG = "AriaActionExecutor"
        const val EVENT_NAME = "aria_playback_action"
        const val MAX_VALUE_LENGTH = 100
    }
}
