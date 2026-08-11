package com.example.resonant.services

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioAttributes as AndroidAudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.LiveData
import androidx.media3.common.AudioAttributes as ExoAudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.example.resonant.BuildConfig
import com.example.resonant.data.network.AddStreamDTO
import com.example.resonant.data.network.ClientCompatibilityInterceptor
import com.example.resonant.managers.CrossfadeMode
import com.example.resonant.managers.AudioQuality
import com.example.resonant.playback.AudioEqualizerController
import com.example.resonant.playback.PlaybackCache
import com.example.resonant.playback.PlaybackQueue
import com.example.resonant.playback.PlaybackQueueEnricher
import com.example.resonant.playback.RadioRepository
import com.example.resonant.playback.PlaybackMediaItemFactory
import com.example.resonant.playback.PlaybackMediaSourceFactory
import com.example.resonant.playback.PlaybackStateRepository
import com.example.resonant.playback.PlaybackStateStore
import com.example.resonant.playback.PlaybackUrlResolver
import com.example.resonant.playback.PlaybackQoeTracker
import com.example.resonant.playback.FirebasePlaybackQoeSink
import com.example.resonant.playback.BackendPlaybackQoeSink
import com.example.resonant.playback.CompositePlaybackQoeSink
import com.example.resonant.playback.PlaybackQoeContext
import com.example.resonant.playback.PlayerController
import com.example.resonant.playback.QueueCommands
import com.example.resonant.managers.PlaylistManager
import com.example.resonant.managers.PlaymixManager
import com.example.resonant.playback.QueueSource
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.data.network.PlaybackConnectCommandDTO
import com.example.resonant.data.network.PlaybackConnectQueueItemDTO
import com.example.resonant.data.network.PlaybackConnectStateDTO
import com.example.resonant.data.network.SongPlaybackDTO
import com.example.resonant.managers.SettingsManager
import com.example.resonant.managers.TransitionManager
import com.example.resonant.managers.UserManager
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.ApiClient
import com.example.resonant.managers.SongManager
import com.example.resonant.playback.withPlaybackInfo
import com.example.resonant.playback.ResonantLibrarySessionCallback
import com.example.resonant.playback.PlaybackConnectRepository
import com.example.resonant.playback.ConnectConflictEvent
import com.example.resonant.data.network.V2EndpointAvailability
import com.example.resonant.ui.activities.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaLibraryService(), PlayerController {

    companion object {
        const val EXTRA_CURRENT_SONG = "com.resonant.EXTRA_CURRENT_SONG"
        const val EXTRA_CURRENT_INDEX = "com.resonant.EXTRA_CURRENT_INDEX"
        const val EXTRA_CURRENT_IMAGE_PATH = "com.resonant.EXTRA_CURRENT_IMAGE_PATH"
        const val ACTION_SEEK_TO = "com.resonant.ACTION_SEEK_TO"
        const val EXTRA_SEEK_POSITION = "com.resonant.EXTRA_SEEK_POSITION"
        const val EXTRA_START_POSITION_MS = "com.resonant.EXTRA_START_POSITION_MS"
        const val ACTION_REQUEST_STATE = "com.resonant.ACTION_REQUEST_STATE"
        const val EXTRA_QUEUE_SOURCE = "com.resonant.EXTRA_QUEUE_SOURCE"
        const val EXTRA_QUEUE_SOURCE_ID = "com.resonant.EXTRA_QUEUE_SOURCE_ID"
        const val ACTION_START_SEEK = "com.resonant.ACTION_START_SEEK"
        const val ACTION_STOP_SEEK = "com.resonant.ACTION_STOP_SEEK"
        const val ACTION_SHUTDOWN = "com.resonant.ACTION_SHUTDOWN"
        const val ACTION_PLAY = "com.resonant.ACTION_PLAY"
        const val ACTION_PAUSE = "com.resonant.ACTION_PAUSE"
        const val ACTION_RESUME = "com.resonant.ACTION_RESUME"
        /**
         * Starts the service (keeps it alive for Connect heartbeats) without
         * requiring active audio playback.  Sent by [MainActivity] on start so
         * the Connect presence heartbeat always runs while the app is open,
         * allowing other devices to find this one and for this device to receive
         * TRANSFER_IN commands even when it is not currently playing music.
         */
        const val ACTION_ENSURE_CONNECT = "com.resonant.ACTION_ENSURE_CONNECT"
        /**
         * Sent by the Activity after the user confirms "Escuchar aquí" in the
         * Resonant Connect conflict dialog.  The extra [EXTRA_CONNECT_PENDING_TAG]
         * must match the [ConnectConflictEvent.pendingActionTag] that was emitted
         * when the conflict was detected.
         */
        const val ACTION_PLAY_CONNECT_CONFIRMED = "com.resonant.ACTION_PLAY_CONNECT_CONFIRMED"
        const val EXTRA_CONNECT_PENDING_TAG     = "com.resonant.EXTRA_CONNECT_PENDING_TAG"
        const val ACTION_PREVIOUS = "com.resonant.ACTION_PREVIOUS"
        const val ACTION_NEXT = "com.resonant.ACTION_NEXT"
        const val SONG_LIST = "com.resonant.SONG_LIST"
        const val ACTION_UPDATE_QUEUE = "com.example.resonant.action.UPDATE_QUEUE"
        const val ACTION_PLAYLIST_MODIFIED = "com.resonant.ACTION_PLAYLIST_MODIFIED"
        const val EXTRA_PLAYLIST_ID = "com.resonant.EXTRA_PLAYLIST_ID"
        const val ACTION_SONG_MARKED_FOR_DELETION = "com.resonant.ACTION_SONG_MARKED_FOR_DELETION"
        const val EXTRA_SONG_ID = "com.resonant.EXTRA_SONG_ID"
        const val ACTION_TOGGLE_SHUFFLE = "com.resonant.ACTION_TOGGLE_SHUFFLE"
        const val ACTION_TOGGLE_REPEAT = "com.resonant.ACTION_TOGGLE_REPEAT"
        const val ACTION_CONNECT_SYNC = "com.resonant.ACTION_CONNECT_SYNC"
        private const val AUDIO_DUCK_VOLUME_MULTIPLIER = 0.2f
        private const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L
        private const val PLAYBACK_STATE_SAVE_INTERVAL_MS = 5_000L
        // Shortened from 10s: this is also the maximum window during which the
        // device that just lost playback could still be audible alongside the
        // new active device, so keeping it tight matters for Resonant Connect.
        private const val CONNECT_HEARTBEAT_INTERVAL_MS = 4_000L

        /** Prefetch radio when this many songs (or fewer) remain in the queue. */
        private const val RADIO_PREFETCH_THRESHOLD = 3
        /** Rolling window size of recentSongIds we send to the server. */
        private const val RADIO_RECENT_WINDOW_SIZE = 30
        private const val CONNECT_MAX_QUEUE_ITEMS = 500
        private val LEGACY_CUSTOM_ACTIONS = setOf(
            ACTION_PLAY,
            ACTION_PAUSE,
            ACTION_RESUME,
            ACTION_PLAY_CONNECT_CONFIRMED,
            ACTION_ENSURE_CONNECT,
            ACTION_PREVIOUS,
            ACTION_NEXT,
            ACTION_SEEK_TO,
            ACTION_REQUEST_STATE,
            ACTION_START_SEEK,
            ACTION_STOP_SEEK,
            ACTION_SHUTDOWN,
            ACTION_UPDATE_QUEUE,
            ACTION_PLAYLIST_MODIFIED,
            ACTION_SONG_MARKED_FOR_DELETION,
            ACTION_TOGGLE_SHUFFLE,
            ACTION_TOGGLE_REPEAT,
            ACTION_CONNECT_SYNC
        )
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val currentSongLiveData: LiveData<Song?> get() = PlaybackStateRepository.currentSongLiveData

    private lateinit var settingsManager: SettingsManager
    private lateinit var transitionManager: TransitionManager
    private lateinit var playbackUrlResolver: PlaybackUrlResolver
    private lateinit var playbackMediaItemFactory: PlaybackMediaItemFactory
    private lateinit var playbackMediaSourceFactory: PlaybackMediaSourceFactory
    private lateinit var playbackQueueEnricher: PlaybackQueueEnricher
    private lateinit var playbackQoeTracker: PlaybackQoeTracker
    private lateinit var playbackStateStore: PlaybackStateStore
    private lateinit var playbackConnectRepository: PlaybackConnectRepository
    private lateinit var radioRepository: RadioRepository

    /**
     * Rolling window of the last songs the user has heard.  Sent with every
     * radio request so the server never returns something we just played.
     * Bounded to avoid runaway growth on long sessions.
     */
    private val radioRecentSongIds = ArrayDeque<String>(RADIO_RECENT_WINDOW_SIZE)

    /**
     * Guards against re-entering the pre-fetch coroutine while a fetch is
     * already in flight; the mutex inside RadioRepository serialises the
     * HTTP call itself but this atomic flag stops us from launching
     * multiple coroutines that would all end up waiting on the mutex.
     */
    private val radioPrefetchInFlight = AtomicBoolean(false)

    /** true iff the user has autoplay enabled in Settings. */
    @Volatile private var autoplayRadioEnabled: Boolean = true
    private lateinit var librarySessionCallback: ResonantLibrarySessionCallback
    private val audioEqualizerController = AudioEqualizerController()
    private var mediaLibrarySession: MediaLibrarySession? = null
    private lateinit var audioManager: AudioManager
    private lateinit var audioFocusRequest: AudioFocusRequest

    private var exoPlayer: ExoPlayer? = null
    private lateinit var playerListener: Player.Listener
    private var hasAudioFocus: Boolean = false
    private var resumeOnAudioFocusGain: Boolean = false
    private var duckedByAudioFocus: Boolean = false
    private var noisyReceiverRegistered: Boolean = false
    private var automixEnabled: Boolean = false
    private var crossfadeDurationMs: Long = 5000L
    private var crossfadeMode: CrossfadeMode = CrossfadeMode.SOFT_MIX
    private var isSeeking: Boolean = false
    private var lastManualChangeTimestamp: Long = 0L
    private val pendingDeletionSongIds = mutableSetOf<String>()
    @Volatile
    private var predictedIntelligentDuration: Long = -1L

    // Loudness normalization
    private var loudnessNormalizationEnabled: Boolean = true
    private var streamingQuality: AudioQuality = AudioQuality.AUTO
    private val TARGET_LOUDNESS_LUFS = -14.0f  // Referencia estándar de streaming

    private var currentSongStartTimeMs: Long = 0L
    private var lastPlaybackStateSaveAtMs: Long = 0L
    private var clearPlaybackStateOnDestroy: Boolean = false
    private var originalQueueSongs: List<Song>? = null

    // PlayMix crossfade — map key: "fromSongId:toSongId"
    private var playmixTransitions: Map<String, PlaymixTransitionDTO> = emptyMap()
    private var playmixSongIdMap: Map<String, String> = emptyMap() // songId -> playmixSongId
    private var pendingStartPositionMs: Long = 0L
    private val playbackLoadGeneration = AtomicLong(0L)
    private val connectStateRevision = AtomicLong(0L)
    private val connectSyncInFlight = AtomicBoolean(false)
    // The activeDeviceId from the most recent heartbeat snapshot.
    @Volatile private var connectPreviousActiveDeviceId: String? = null
    // How many consecutive heartbeat ticks have found another device active
    // while this device is playing. Reaches threshold → pause (grace period).
    @Volatile private var connectNotActiveCount: Int = 0

    /**
     * When the user taps a song on this device while another Connect device
     * is the active player, we intercept the [ACTION_PLAY] intent, stash it
     * here, and emit a [ConnectConflictEvent] so the Activity can ask the
     * user what to do.  On confirmation ([ACTION_PLAY_CONNECT_CONFIRMED]) we
     * retrieve and execute it; on cancellation we simply drop it.
     */
    private val pendingConnectPlayIntents =
        java.util.concurrent.ConcurrentHashMap<String, Intent>()
    private var progressiveFallbackSongId: String? = null

    private val exoAudioAttributes = ExoAudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d("AudioFocus", "Foco recuperado")
                hasAudioFocus = true
                if (duckedByAudioFocus) {
                    duckedByAudioFocus = false
                    applyLoudnessNormalization(PlaybackStateRepository.currentSong)
                }
                if (resumeOnAudioFocusGain) {
                    resumeOnAudioFocusGain = false
                    exoPlayer?.play()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d("AudioFocus", "Foco perdido permanentemente")
                resumeOnAudioFocusGain = false
                duckedByAudioFocus = false
                pauseForAudioFocus(shouldResume = false)
                abandonAudioFocus()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d("AudioFocus", "Foco perdido temporalmente")
                duckedByAudioFocus = false
                pauseForAudioFocus(shouldResume = exoPlayer?.isPlaying == true)
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d("AudioFocus", "Foco perdido con ducking")
                if (exoPlayer?.isPlaying == true) {
                    duckedByAudioFocus = true
                    applyLoudnessNormalization(PlaybackStateRepository.currentSong)
                }
            }
        }
    }

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.d("AudioFocus", "Salida de audio desconectada. Pausando reproducción.")
                resumeOnAudioFocusGain = false
                pause()
            }
        }
    }

    private val seekBarHandler = Handler(Looper.getMainLooper())
    private val updateSeekBarRunnable = object : Runnable {
        override fun run() {
            val player = exoPlayer
            if (player == null || !PlaybackStateRepository.isPlaying) {
                stopSeekBarUpdates()
                return
            }

            val position = player.currentPosition
            val duration = player.duration.toInt()

            // Diagnostic: log every ~5s so we can confirm the seekbar is running
            if (BuildConfig.DEBUG && position % 5000 < 250) {
                Log.d("SeekBar", "▶ pos=${position}ms dur=${duration}ms song=${PlaybackStateRepository.currentSong?.title} mode=$crossfadeMode")
            }

            if (duration <= 0) {
                seekBarHandler.postDelayed(this, 200)
                return
            }

            val triggerPosition = calculateTriggerPosition()

            if (triggerPosition > 0 && position >= triggerPosition) {
                Log.i("MusicService", "🏁 TRIGGER by SeekBar: Pos(${position}ms) >= Trigger(${triggerPosition}ms).")
                handleTransitionTrigger()
                return
            }

            PlaybackStateRepository.updatePlaybackPosition(position, duration)
            persistPlaybackState()
            seekBarHandler.postDelayed(this, 200)
        }
    }

    private fun startSeekBarUpdates() {
        seekBarHandler.removeCallbacks(updateSeekBarRunnable)
        seekBarHandler.post(updateSeekBarRunnable)
    }
    private fun stopSeekBarUpdates() {
        seekBarHandler.removeCallbacks(updateSeekBarRunnable)
    }

    private val connectHandler = Handler(Looper.getMainLooper())
    private val connectHeartbeatRunnable = object : Runnable {
        override fun run() {
            syncPlaybackConnect()
            connectHandler.postDelayed(this, CONNECT_HEARTBEAT_INTERVAL_MS)
        }
    }

    private fun startConnectHeartbeats() {
        connectHandler.removeCallbacks(connectHeartbeatRunnable)
        connectHandler.post(connectHeartbeatRunnable)
    }

    private fun stopConnectHeartbeats() {
        connectHandler.removeCallbacks(connectHeartbeatRunnable)
    }

    private fun requestAudioFocus(): Boolean {
        val result = audioManager.requestAudioFocus(audioFocusRequest)
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasAudioFocus) {
            Log.w("AudioFocus", "No se pudo obtener foco de audio. Reproducción bloqueada.")
        }
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!::audioManager.isInitialized || !::audioFocusRequest.isInitialized) return
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
        hasAudioFocus = false
        resumeOnAudioFocusGain = false
        duckedByAudioFocus = false
    }

    private fun pauseForAudioFocus(shouldResume: Boolean) {
        resumeOnAudioFocusGain = shouldResume
        if (transitionManager.isTransitioning) {
            transitionManager.forceCompleteTransition()
        }
        exoPlayer?.pause()
    }

    private fun registerBecomingNoisyReceiver() {
        if (noisyReceiverRegistered) return
        registerReceiver(
            becomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            Context.RECEIVER_NOT_EXPORTED
        )
        noisyReceiverRegistered = true
    }

    private fun unregisterBecomingNoisyReceiver() {
        if (!noisyReceiverRegistered) return
        try {
            unregisterReceiver(becomingNoisyReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w("AudioFocus", "Receiver de audio ya estaba desregistrado.", e)
        } finally {
            noisyReceiverRegistered = false
        }
    }

    override fun resume() {
        if (!requestAudioFocus()) return
        registerBecomingNoisyReceiver()
        val player = exoPlayer ?: return
        if (currentSongStartTimeMs <= 0L) {
            currentSongStartTimeMs = System.currentTimeMillis()
        }
        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
            player.prepare()
        }
        player.play()
    }
    override fun pause() {
        resumeOnAudioFocusGain = false
        if (transitionManager.isTransitioning) {
            Log.w("MusicService", "PAUSA durante transición. Forzando completado para pausar la nueva canción.")

            // 1. FORZAR COMPLETADO
            // Esto llama a onTransitionComplete, que HACE EL CAMBIO
            // de 'exoPlayer' a 'newPlayer' (Song B)
            transitionManager.forceCompleteTransition()

            // 2. PAUSAR EL REPRODUCTOR
            // Ahora 'this.exoPlayer' ya es el 'newPlayer' (Song B),
            // así que esto pausa la canción correcta.
            exoPlayer?.pause()

        } else {
            // No hay transición, solo pausar normalmente
            exoPlayer?.pause()
        }
        // An errored player may already report isPlaying=false and therefore not
        // emit another callback. Keep the observable state authoritative so the
        // mini-player never keeps sending PAUSE while playback is stopped.
        PlaybackStateRepository.setIsPlaying(false)
        stopSeekBarUpdates()
        unregisterBecomingNoisyReceiver()
        abandonAudioFocus()
        updateUiAndSystem()
        syncPlaybackConnect()
        persistPlaybackState(force = true)
    }
    override fun playNext() {
        reportCurrentSongFinished(wasSkipped = true)

        if (transitionManager.isTransitioning) {
            cancelCurrentTransitionAndRestoreEffects()
        }

        val queue = PlaybackStateRepository.activeQueue ?: return
        val nextIndex = if (queue.currentIndex + 1 < queue.songs.size) {
            queue.currentIndex + 1
        } else if (PlaybackStateRepository.repeatModeLiveData.value ==
            PlaybackStateRepository.REPEAT_MODE_ALL
        ) {
            0
        } else {
            -1
        }

        lastManualChangeTimestamp = System.currentTimeMillis()

        if (nextIndex in queue.songs.indices) {
            Log.d("MusicService", "Acción Proactiva: Saltando al índice $nextIndex")
            moveToQueueIndex(nextIndex)
        } else {
            Log.d("MusicService", "Fin de la cola alcanzado en playNext")
            // Last-chance radio fetch: the prefetch in onMediaItemTransition
            // may not have fired (very short queue, user tapped Next fast,
            // network was slow…).  Try once more here; if the server
            // responds we'll advance into the new tracks automatically.
            tryStartRadioAtQueueEnd(queue)
        }
    }

    /**
     * Kick off a radio fetch as the user hits the end of the queue.  When
     * the request comes back we advance the player into the first newly
     * appended track.  Fire-and-forget: if the network is slow the queue
     * simply ends as before.
     */
    private fun tryStartRadioAtQueueEnd(queue: PlaybackQueue) {
        if (!autoplayRadioEnabled) return
        if (queue.sourceType == QueueSource.PLAYMIX ||
            queue.sourceType == QueueSource.DOWNLOADED_SONGS
        ) return
        if (!radioPrefetchInFlight.compareAndSet(false, true)) return

        val sourceType = queue.sourceType.name
        val sourceId = queue.sourceId
        val lastSongId = queue.songs.getOrNull(queue.currentIndex)?.id
        val recentIds = radioRecentSongIds.toList()
        val queueSizeBefore = queue.songs.size

        serviceScope.launch {
            try {
                val newSongs = radioRepository.fetchNextBatch(
                    sourceType = sourceType,
                    sourceId = sourceId,
                    lastSongId = lastSongId,
                    recentSongIds = recentIds
                )
                if (newSongs.isEmpty()) return@launch

                withContext(Dispatchers.Main) {
                    appendRadioSongsToQueue(newSongs)
                    val liveQueue = PlaybackStateRepository.activeQueue ?: return@withContext
                    // The append may have deduplicated; only advance if
                    // there really are new songs after the current index.
                    if (liveQueue.songs.size > queueSizeBefore) {
                        moveToQueueIndex(queueSizeBefore)
                    }
                }
            } finally {
                radioPrefetchInFlight.set(false)
            }
        }
    }
    override fun playPrevious() {
        reportCurrentSongFinished(wasSkipped = true)

        if (transitionManager.isTransitioning) {
            cancelCurrentTransitionAndRestoreEffects()
        }

        val player = exoPlayer
        if (player != null && player.currentPosition > PREVIOUS_RESTART_THRESHOLD_MS) {
            lastManualChangeTimestamp = System.currentTimeMillis()
            player.seekTo(0L)
            return
        }

        lastManualChangeTimestamp = System.currentTimeMillis()

        val queue = PlaybackStateRepository.activeQueue ?: return
        val prevIndex = if (queue.currentIndex > 0) {
            queue.currentIndex - 1
        } else if (PlaybackStateRepository.repeatModeLiveData.value ==
            PlaybackStateRepository.REPEAT_MODE_ALL
        ) {
            queue.songs.lastIndex
        } else {
            -1
        }

        if (prevIndex in queue.songs.indices) {
            Log.d("MusicService", "Acción Proactiva: Volviendo al índice $prevIndex")
            moveToQueueIndex(prevIndex)
        }
    }
    private fun moveToQueueIndex(targetQueueIndex: Int) {
        val queue = PlaybackStateRepository.activeQueue ?: return
        val targetSong = queue.songs.getOrNull(targetQueueIndex) ?: return
        val player = exoPlayer
        val playerIndex = if (player != null) {
            targetQueueIndex.takeIf { index ->
                index in 0 until player.mediaItemCount &&
                    player.getMediaItemAt(index).mediaId == targetSong.id
            } ?: (0 until player.mediaItemCount).firstOrNull { index ->
                player.getMediaItemAt(index).mediaId == targetSong.id
            }
        } else {
            null
        }

        queue.currentIndex = targetQueueIndex
        progressiveFallbackSongId = null
        retryCount = 0
        expectedSeekPositionMs = -1L
        PlaybackStateRepository.setCurrentSong(targetSong)
        PlaybackStateRepository.publishQueue()
        PlaybackStateRepository.updatePlaybackPosition(
            position = 0L,
            duration = targetSong.audioAnalysis?.durationMs ?: 0
        )
        currentSongStartTimeMs = System.currentTimeMillis()
        PlaybackStateRepository.streamReported = false
        lastManualChangeTimestamp = System.currentTimeMillis()
        playbackQoeTracker.markPlayRequested(queue.sourceType.name, targetSong.id)
        loadArtworkForSong(targetSong)
        updateUiAndSystem()
        updateMediaSessionMetadata()

        if (player != null && playerIndex != null) {
            player.seekTo(playerIndex, 0L)
            if (requestAudioFocus()) {
                registerBecomingNoisyReceiver()
                player.play()
            }
            prepareNextTransitionPlayer(targetQueueIndex)
        } else {
            playSongFromQueue()
        }
        persistPlaybackState(force = true)
        syncPlaybackConnect()
    }

    override fun stop() {
        reportCurrentSongFinished(wasSkipped = true)
        if (transitionManager.isTransitioning) {
            cancelCurrentTransitionAndRestoreEffects()
        }
        unregisterBecomingNoisyReceiver()
        abandonAudioFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cancelCurrentTransitionAndRestoreEffects() {
        transitionManager.cancelCurrentTransition()
        val player = exoPlayer ?: return
        audioEqualizerController.resumeAfterTransition(player.audioSessionId)
        applyLoudnessNormalization(PlaybackStateRepository.currentSong)
    }

    override fun seekTo(position: Long) {
        if (transitionManager.isTransitioning) {
            Log.w("MusicService", "Seek to durante transición. Forzando completado para buscar en la nueva canción.")
            transitionManager.forceCompleteTransition()
            
            val player = exoPlayer ?: return
            val currentMediaId = player.currentMediaItem?.mediaId
            val expectedMediaId = PlaybackStateRepository.currentSong?.id
            
            if (currentMediaId != null && expectedMediaId != null && currentMediaId != expectedMediaId) {
                Log.w("MusicService", "Desajuste UI vs ExoPlayer detectado tras forzar completado. Saltando a la siguiente canción.")
                player.seekToNextMediaItem()
                return
            }
        }

        val player = exoPlayer ?: return
        lastManualChangeTimestamp = System.currentTimeMillis()
        expectedSeekPositionMs = position
        seekIssuedAtMs = System.currentTimeMillis()
        player.seekTo(position)
    }

    override fun playFromSearch(query: String?, extras: Bundle?) {
        reportCurrentSongFinished(wasSkipped = true)
        if (transitionManager.isTransitioning) {
            cancelCurrentTransitionAndRestoreEffects()
        }

        serviceScope.launch(Dispatchers.Main) {
            val songs = withContext(Dispatchers.IO) {
                val manager = SongManager(applicationContext)
                val resolvedQuery = query?.takeIf { it.isNotBlank() }

                runCatching {
                    if (resolvedQuery == null) {
                        manager.getPlaybackHistory(20).ifEmpty { manager.getTrendingSongs(20) }
                    } else {
                        manager.searchSongs(resolvedQuery, 20).results
                    }
                }.onFailure {
                    Log.e("MusicService", "Error en busqueda por voz: $query", it)
                }.getOrDefault(emptyList())
            }

            if (songs.isEmpty()) {
                Log.w("MusicService", "Busqueda por voz sin resultados: $query")
                return@launch
            }

            playmixTransitions = emptyMap()
            playmixSongIdMap = emptyMap()
            pendingStartPositionMs = 0L
            PlaybackStateRepository.activeQueue = PlaybackQueue(
                songs = songs,
                currentIndex = 0,
                sourceId = query?.takeIf { it.isNotBlank() }?.let { "voice:$it" } ?: "voice_default",
                sourceType = QueueSource.SEARCH
            )
            PlaybackStateRepository.setIsShuffleEnabled(false)
            originalQueueSongs = null
            playSongFromQueue()
        }
    }


    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("MusicService", "Action received: ${intent?.action}")
        val incomingIntent = intent ?: return START_STICKY
        val action = incomingIntent.action

        // Manejo del botón multimedia de Android
        if (action == Intent.ACTION_MEDIA_BUTTON) {
            return super.onStartCommand(intent, flags, startId)
        }

        // Manejo de nuestras acciones personalizadas
        if (action != null && action in LEGACY_CUSTOM_ACTIONS) {
            handleAction(action, incomingIntent)
            return START_STICKY
        }

        return super.onStartCommand(intent, flags, startId)
    }
    override fun onCreate() {
        super.onCreate()

        settingsManager = SettingsManager(applicationContext)
        playbackStateStore = PlaybackStateStore(applicationContext)
        playbackConnectRepository =
            PlaybackConnectRepository.get(applicationContext)
        playbackConnectRepository.synchronizeUserScope()
        radioRepository = RadioRepository.get(applicationContext)

        // Reflect the user's setting so we don't hit the network when they
        // disabled autoplay.  This coroutine lives as long as the service.
        serviceScope.launch {
            settingsManager.autoplayRadioEnabledFlow.collect { enabled ->
                autoplayRadioEnabled = enabled
            }
        }
        observeSettings() // <-- Movido aquí

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AndroidAudioAttributes.Builder()
                    .setUsage(AndroidAudioAttributes.USAGE_MEDIA)
                    .setContentType(AndroidAudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(audioFocusChangeListener, Handler(Looper.getMainLooper()))
            .build()

        val songService = ApiClient.getSongService(applicationContext)

        // Safety Net: En CADA respuesta de Connect, mirar el snapshot.activeDeviceId.
        // Si este dispositivo está reproduciendo y activeDeviceId != localDeviceId => pausar.
        serviceScope.launch {
            playbackConnectRepository.uiState.collect { state ->
                val localDeviceId = playbackConnectRepository.localDeviceId
                val isAnotherDeviceActive = state.supported &&
                    state.activeDeviceId != null &&
                    state.activeDeviceId != localDeviceId

                if (isAnotherDeviceActive && PlaybackStateRepository.isPlaying) {
                    withContext(Dispatchers.Main) { 
                        Log.w("MusicService", "Connect: Otro dispositivo tomó el control ($isAnotherDeviceActive). Pausando inmediatamente.")
                        pause() 
                    }
                }
            }
        }

        playbackUrlResolver = PlaybackUrlResolver(
            loadPlaybackInfo = { songId ->
                songService.getSongPlaybackInfo(
                    songId = songId,
                    deliveryMode = PlaybackUrlResolver.DELIVERY_MODE_PROGRESSIVE,
                    preferredQuality = streamingQuality.apiValue
                )
            },
            onPlaybackInfoResolved = ::handlePlaybackInfoResolved
        )
        playbackQueueEnricher = PlaybackQueueEnricher(
            context = applicationContext,
            songService = songService,
            urlResolver = playbackUrlResolver
        )
        playbackMediaItemFactory = PlaybackMediaItemFactory(playbackUrlResolver)
        playbackMediaSourceFactory = PlaybackMediaSourceFactory(
            applicationContext,
            playbackUrlResolver
        )
        playbackQoeTracker = PlaybackQoeTracker(
            CompositePlaybackQoeSink(
                FirebasePlaybackQoeSink(applicationContext),
                BackendPlaybackQoeSink(
                    context = applicationContext,
                    contextProvider = ::playbackQoeContext
                )
            )
        )
        transitionManager = TransitionManager(
            context = this,
            mediaSourceFactoryProvider = playbackMediaSourceFactory::create,
            mediaItemFactory = playbackMediaItemFactory::create,
            onTransitionComplete = ::handleTransitionComplete,
            onTransitionFailed = ::handleTransitionFailed,
            onTransitionProgress = { position, duration ->
                PlaybackStateRepository.updatePlaybackPosition(position, duration.toInt())
            }
        )

        setupPlayerListener()
        exoPlayer = createExoPlayer()
        restorePlaybackState()
        librarySessionCallback = ResonantLibrarySessionCallback(
            context = applicationContext,
            mediaItemFactory = playbackMediaItemFactory,
            onQueueSelected = { queue ->
                queue.songs = playbackQueueEnricher.enrich(
                    songs = queue.songs,
                    currentIndex = queue.currentIndex
                )
                withContext(Dispatchers.Main) {
                    cancelCurrentTransitionAndRestoreEffects()
                    PlaybackStateRepository.activeQueue = queue
                    queue.songs.getOrNull(queue.currentIndex)?.let { song ->
                        PlaybackStateRepository.setCurrentSong(song)
                        loadArtworkForSong(song)
                    }
                    playbackQoeTracker.markPlayRequested(
                        queue.sourceType.name,
                        queue.songs.getOrNull(queue.currentIndex)?.id
                    )
                    currentSongStartTimeMs = System.currentTimeMillis()
                }
            },
            onQueueCommand = ::handleQueueCommand,
            playbackPositionMs = { exoPlayer?.currentPosition ?: 0L }
        )
        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            requireNotNull(exoPlayer),
            librarySessionCallback
        )
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("fromNotification", true)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setPeriodicPositionUpdateEnabled(true)
            .build()

        startConnectHeartbeats()
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val player = exoPlayer
        // Only stop if there is no active audio AND Connect is not supported/active.
        // If Connect is available (user is logged in and the feature is reachable),
        // keep the service alive so the heartbeat continues — other devices can
        // still find this one and send TRANSFER_IN commands to it.
        val connectActive = ::playbackConnectRepository.isInitialized &&
            playbackConnectRepository.uiState.value.let { state ->
                state.supported || V2EndpointAvailability.shouldTry(
                    applicationContext,
                    V2EndpointAvailability.FEATURE_CONNECT
                )
            }
        if (
            (player == null || (!player.playWhenReady && player.playbackState == Player.STATE_IDLE)) &&
            !connectActive
        ) {
            stopSelf()
        }
    }
    override fun onDestroy() {
        if (::playbackStateStore.isInitialized) {
            if (clearPlaybackStateOnDestroy) {
                playbackStateStore.clear()
            } else {
                persistPlaybackState(force = true)
            }
        }
        stopSeekBarUpdates()
        stopConnectHeartbeats()

        if (::transitionManager.isInitialized) {
            transitionManager.release()
        }
        if (::playbackQoeTracker.isInitialized) {
            playbackQoeTracker.release()
        }
        if (::librarySessionCallback.isInitialized) {
            librarySessionCallback.release()
        }
        mediaLibrarySession?.release()
        mediaLibrarySession = null
        PlaybackStateRepository.reset()
        audioEqualizerController.release()
        exoPlayer?.release()
        exoPlayer = null
        unregisterBecomingNoisyReceiver()
        abandonAudioFocus()
        serviceScope.cancel()
        super.onDestroy()
    }

    fun isPlaying(): Boolean = PlaybackStateRepository.isPlaying
    fun setLooping(looping: Boolean) {
        if (looping) {
            // Para ExoPlayer, activar el bucle en la canción actual se hace con REPEAT_MODE_ONE.
            exoPlayer?.repeatMode = Player.REPEAT_MODE_ONE
            Log.d("ExoPlayer", "Looping activado (REPEAT_MODE_ONE).")
        } else {
            // Para desactivar cualquier tipo de repetición, se usa REPEAT_MODE_OFF.
            exoPlayer?.repeatMode = Player.REPEAT_MODE_OFF
            Log.d("ExoPlayer", "Looping desactivado (REPEAT_MODE_OFF).")
        }
    }
    fun updateSongs(newSongs: List<Song>) {
        val player = exoPlayer ?: return
        val queue = PlaybackStateRepository.activeQueue ?: return
        cancelCurrentTransitionAndRestoreEffects()

        val currentPositionMs = player.currentPosition
        val oldIndex = queue.currentIndex
        val currentSongId = PlaybackStateRepository.currentSong?.id

        val newIndex = currentSongId?.let { id -> newSongs.indexOfFirst { it.id == id } } ?: -1

        // Actualizamos la cola en el Repository
        queue.songs = newSongs.toMutableList()
        queue.resetEntriesForSongs()

        if (newSongs.isEmpty()) {
            stop()
            return
        }

        if (newIndex != -1) {
            queue.currentIndex = newIndex
        } else {
            queue.currentIndex = oldIndex.coerceAtMost(newSongs.size - 1)
        }

        // Sincroniza ExoPlayer con la cola actualizada del Repository
        synchronizeExoPlayerQueue()
        PlaybackStateRepository.publishQueue()
        persistPlaybackState(force = true)
        prepareNextTransitionPlayer(queue.currentIndex)
    }
    fun getCurrentSong(): Song? = PlaybackStateRepository.currentSong
    fun getDuration(): Int = exoPlayer?.duration?.toInt() ?: 0
    fun getCurrentPosition(): Int {
        val actual = exoPlayer?.currentPosition ?: 0L
        val expected = expectedSeekPositionMs
        if (expected >= 0L && System.currentTimeMillis() - seekIssuedAtMs < 3000L) {
            return maxOf(actual, expected).toInt()
        }
        expectedSeekPositionMs = -1L
        return actual.toInt()
    }
    fun getCurrentIndex(): Int = exoPlayer?.currentMediaItemIndex ?: -1
    fun getCurrentSongUrl(): String? = PlaybackStateRepository.currentSong?.url

    private var expectedSeekPositionMs: Long = -1L
    private var seekIssuedAtMs: Long = 0L

    private fun setupPlayerListener() {
        playerListener = object : Player.Listener {

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (!transitionManager.isTransitioning) {
                    audioEqualizerController.bindToSession(audioSessionId)
                }
            }

            override fun onIsPlayingChanged(isPlayingUpdate: Boolean) {
                if (!isPlayingUpdate) {
                    val timeSinceManualChange = System.currentTimeMillis() - lastManualChangeTimestamp
                    if (timeSinceManualChange < 500) {
                        Log.d("PlayerListener", "onIsPlayingChanged(false) ignorado por ocurrir dentro del periodo de gracia.")
                        return
                    }
                }

                if (isSeeking && !isPlayingUpdate) {
                    Log.d("PlayerListener", "onIsPlayingChanged(false) ignorado por seeking.")
                    return
                }

                if (transitionManager.isTransitioning) {
                    Log.d("PlayerListener", "onIsPlayingChanged ignorado durante transición.")
                    return
                }

                Log.d("PlayerListener", "onIsPlayingChanged: $isPlayingUpdate")

                PlaybackStateRepository.setIsPlaying(isPlayingUpdate)
                updateUiAndSystem()
                syncPlaybackConnect()
                if (isPlayingUpdate) {
                    registerBecomingNoisyReceiver()
                    startSeekBarUpdates()
                } else {
                    unregisterBecomingNoisyReceiver()
                    stopSeekBarUpdates()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (transitionManager.isTransitioning) {
                    Log.d("PlayerListener", "onMediaItemTransition ignorado durante transición.")
                    return
                }

                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {

                    val mediaId = mediaItem?.mediaId
                    val activeQueue = PlaybackStateRepository.activeQueue
                    val directIndex = exoPlayer?.currentMediaItemIndex ?: -1
                    val newQueueIndex = when {
                        directIndex in activeQueue?.songs.orEmpty().indices &&
                            activeQueue?.songs?.get(directIndex)?.id == mediaId -> directIndex
                        else -> activeQueue?.songs?.indexOfFirst { it.id == mediaId } ?: -1
                    }
                    val newSong = activeQueue?.songs?.getOrNull(newQueueIndex)

                    val queueIndexChanged = activeQueue != null &&
                        newQueueIndex != activeQueue.currentIndex
                    val currentSongChanged = newSong != null &&
                        PlaybackStateRepository.currentSong?.id != newSong.id

                    if (activeQueue != null && newSong != null &&
                        (queueIndexChanged || currentSongChanged)
                    ) {
                        Log.d("PlayerListener", "Transición de ExoPlayer a una NUEVA canción: ${newSong.title}")

                        expectedSeekPositionMs = -1L
                        progressiveFallbackSongId = null
                        retryCount = 0

                        PlaybackStateRepository.setCurrentSong(newSong)
                        activeQueue.currentIndex = newQueueIndex
                        PlaybackStateRepository.publishQueue()

                        currentSongStartTimeMs = System.currentTimeMillis()
                        PlaybackStateRepository.streamReported = false

                        loadArtworkForSong(newSong)
                        updateUiAndSystem()
                        persistPlaybackState(force = true)
                        syncPlaybackConnect()

                        // Track the last played song for Resonant Radio's
                        // recentSongIds window, then check if we should
                        // prefetch a batch of autoplay tracks (queue nearing
                        // its end).
                        recordRecentSong(newSong.id)
                        maybePrefetchRadio()

                        // Proactively preload the song after next to maintain seamless native gapless
                        prepareNextTransitionPlayer(newQueueIndex)
                    } else {
                        Log.d("PlayerListener", "Transición de ExoPlayer a la misma canción (${newSong?.title}). Se ignora para evitar parpadeo.")
                    }
                }
                if (pendingDeletionSongIds.isNotEmpty()) {
                    val activeQueue = PlaybackStateRepository.activeQueue
                    if (activeQueue != null && activeQueue.sourceType == QueueSource.PLAYLIST) {
                        Log.d("MusicService", "Transición detectada con eliminaciones pendientes. Resincronizando cola...")

                        pendingDeletionSongIds.clear()

                        serviceScope.launch {
                            val playlistManager = PlaylistManager(applicationContext)
                            val newSongs = playlistManager.getSongsByPlaylistId(activeQueue.sourceId)
                            withContext(Dispatchers.Main) {
                                updateSongs(newSongs)
                            }
                        }
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d("PlayerListener", "onPlaybackStateChanged: $playbackState")

                if (playbackState == Player.STATE_READY) {
                    retryCount = 0
                    exoPlayer?.let { player ->
                        applyStreamingQualityToPlayer(player)
                        if (!transitionManager.isTransitioning) {
                            audioEqualizerController.bindToSession(player.audioSessionId)
                        }
                    }
                    updateMediaSessionMetadata()
                }

                if (playbackState == Player.STATE_ENDED) {
                    if (transitionManager.isTransitioning) {
                        Log.d("PlayerListener", "STATE_ENDED ignorado porque hay una transición en curso.")
                        return
                    }
                    Log.i("PlayerListener", "\uD83C\uDFC1 Canción finalizada naturalmente. Forzando paso a la siguiente.")

                    reportCurrentSongFinished(wasSkipped = false)

                    playNext()
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e("PlayerListener", "\u274C ExoPlayer error: ${error.errorCodeName} - ${error.message}")

                if (transitionManager.isTransitioning) {
                    Log.w("PlayerListener", "Error during transition, letting TransitionManager handle it.")
                    return
                }

                // Source error typically means expired/invalid URL or a lost
                // connection mid-stream. Both are recoverable: we refetch the
                // presigned URL and prepare() from the current position.
                val isSourceError = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE

                val isDeliveryFormatError =
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED

                val willAttemptRecovery = isDeliveryFormatError ||
                    (isSourceError && retryCount < MAX_RETRY_COUNT)

                if (!willAttemptRecovery) {
                    // No recovery left. Fully update UI/session state before
                    // giving up. We keep isPlaying up while retrying so the
                    // seek bar and notification don't flash to "paused" on a
                    // transient error that heals within a few hundred ms.
                    PlaybackStateRepository.setIsPlaying(false)
                    stopSeekBarUpdates()
                    unregisterBecomingNoisyReceiver()
                    updateUiAndSystem()
                    syncPlaybackConnect()
                }

                if (isDeliveryFormatError) {
                    if (!retryCurrentAsProgressive()) {
                        Log.e("PlayerListener", "Progressive fallback already failed. Skipping song.")
                        PlaybackStateRepository.setIsPlaying(false)
                        stopSeekBarUpdates()
                        unregisterBecomingNoisyReceiver()
                        updateUiAndSystem()
                        syncPlaybackConnect()
                        playNext()
                    }
                } else if (isSourceError) {
                    Log.w("PlayerListener", "\uD83D\uDD04 Source error detected. Attempting to refetch URL and retry...")
                    retryCurrentPlayback()
                } else {
                    Log.e("PlayerListener", "Non-recoverable error. Skipping to next song.")
                    playNext()
                }
            }
        }
    }

    /**
     * Recovers from a rollout mismatch where the URL bytes do not match the
     * advertised delivery format. One clean fallback is allowed per song visit.
     */
    private fun retryCurrentAsProgressive(): Boolean {
        val currentSong = PlaybackStateRepository.currentSong ?: return false
        if (progressiveFallbackSongId == currentSong.id) return false

        val player = exoPlayer ?: return false
        val playerIndex = player.currentMediaItemIndex
        if (playerIndex !in 0 until player.mediaItemCount) return false

        progressiveFallbackSongId = currentSong.id
        retryCount = 0
        val songId = currentSong.id
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val shouldResume = player.playWhenReady
        val progressiveSong = currentSong.copy(
            url = null,
            playbackMimeType = null,
            playbackExpiresAtUtc = null,
            playbackDeliveryMode = PlaybackUrlResolver.DELIVERY_MODE_PROGRESSIVE
        )

        val queue = PlaybackStateRepository.activeQueue
        val queueIndex = queue?.indexOfSongId(songId) ?: -1
        if (queue != null && queueIndex >= 0) {
            queue.songs = queue.songs.toMutableList().apply {
                this[queueIndex] = progressiveSong
            }
            PlaybackStateRepository.publishQueue()
        }
        PlaybackStateRepository.setCurrentSong(progressiveSong)
        playbackUrlResolver.invalidate(songId)

        serviceScope.launch {
            PlaybackCache.removeResource(
                applicationContext,
                PlaybackUrlResolver.CACHE_KEY_PREFIX + songId
            )
            withContext(Dispatchers.Main) {
                if (PlaybackStateRepository.currentSong?.id != songId) {
                    return@withContext
                }
                val activePlayer = exoPlayer ?: return@withContext
                if (activePlayer.currentMediaItemIndex != playerIndex ||
                    playerIndex !in 0 until activePlayer.mediaItemCount ||
                    activePlayer.getMediaItemAt(playerIndex).mediaId != songId
                ) {
                    return@withContext
                }

                activePlayer.replaceMediaItem(
                    playerIndex,
                    playbackMediaItemFactory.create(progressiveSong)
                )
                activePlayer.seekTo(playerIndex, positionMs)
                activePlayer.prepare()
                if (shouldResume && requestAudioFocus()) {
                    registerBecomingNoisyReceiver()
                    activePlayer.play()
                }
                Log.i(
                    "MusicService",
                    "Reintentando ${progressiveSong.title} con entrega progresiva limpia"
                )
            }
        }
        return true
    }

    private fun handlePlaybackInfoResolved(playbackInfo: SongPlaybackDTO) {
        serviceScope.launch(Dispatchers.Main) {
            val queue = PlaybackStateRepository.activeQueue ?: return@launch
            val index = queue.songs.indexOfFirst { it.id == playbackInfo.id }
            if (index < 0) return@launch

            val previousSong = queue.songs[index]
            val enrichedSong = previousSong.withPlaybackInfo(playbackInfo)
            val updatedSongs = queue.songs.toMutableList().apply {
                this[index] = enrichedSong
            }
            queue.songs = updatedSongs

            if (queue.currentIndex == index) {
                PlaybackStateRepository.setCurrentSong(enrichedSong)
                applyLoudnessNormalization(enrichedSong)
                updateMediaSessionMetadata()
            }
        }
    }

    private fun playbackQoeContext(mediaId: String?): PlaybackQoeContext? {
        val queue = PlaybackStateRepository.activeQueue ?: return null
        val song = queue.songs.firstOrNull { it.id == mediaId }
            ?: PlaybackStateRepository.currentSong?.takeIf { it.id == mediaId }
            ?: return null
        val isOffline = queue.sourceType == QueueSource.DOWNLOADED_SONGS ||
            song.url?.let { url ->
                !url.startsWith("http://", true) &&
                    !url.startsWith("https://", true) &&
                    !url.startsWith("${PlaybackUrlResolver.STABLE_SCHEME}://", true)
            } == true
        val deliveryMode = when {
            isOffline -> "offline"
            else -> "progressive"
        }
        val streamId = song.playbackStreamId
            ?.takeIf { it.length >= 8 }
            ?: song.id.takeIf { isOffline && it.length >= 8 }
            ?: return null
        return PlaybackQoeContext(
            streamId = streamId,
            deliveryMode = deliveryMode
        )
    }



    private fun createExoPlayer(): ExoPlayer {
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(playbackMediaSourceFactory.create())
            .build().apply {
                setAudioAttributes(exoAudioAttributes, false)
                setWakeMode(C.WAKE_MODE_NETWORK)
                addListener(playerListener)
                addAnalyticsListener(playbackQoeTracker)
                val initialMode = PlaybackStateRepository.repeatModeLiveData.value ?: PlaybackStateRepository.REPEAT_MODE_OFF
                repeatMode = when (initialMode) {
                    PlaybackStateRepository.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ONE
                    PlaybackStateRepository.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_OFF
                }
            }
    }

    @Volatile
    private var retryCount = 0
    // Four attempts is enough to survive a signature refresh + a couple of
    // transient network hiccups without hopping to the next song. The counter
    // resets whenever a load reaches STATE_READY, so successful retries never
    // deplete the budget for later ones.
    private val MAX_RETRY_COUNT = 4

    private fun retryCurrentPlayback() {
        if (retryCount >= MAX_RETRY_COUNT) {
            retryCount = 0
            PlaybackStateRepository.setIsPlaying(false)
            stopSeekBarUpdates()
            unregisterBecomingNoisyReceiver()
            updateUiAndSystem()
            syncPlaybackConnect()
            playNext()
            return
        }

        val songId = PlaybackStateRepository.currentSong?.id ?: run {
            playNext()
            return
        }
        val player = exoPlayer ?: return

        // Capture position BEFORE invalidating and preparing again: some error
        // paths reset the timeline as part of prepare(), which would otherwise
        // silently jump to STATE_ENDED (perceived as "song stopped mid-way").
        val resumePositionMs = player.currentPosition.coerceAtLeast(0L)
        val resumeIndex = player.currentMediaItemIndex.takeIf {
            it in 0 until player.mediaItemCount
        } ?: 0
        val shouldResume = player.playWhenReady

        retryCount++
        playbackUrlResolver.invalidate(songId)
        Log.w(
            "MusicService",
            "Reintentando la fuente actual sin reconstruir la cola (intento $retryCount, pos=${resumePositionMs}ms)"
        )
        player.prepare()
        player.seekTo(resumeIndex, resumePositionMs)
        if (shouldResume && requestAudioFocus()) {
            registerBecomingNoisyReceiver()
            player.play()
        }
    }

    private fun calculateTriggerPosition(): Long {
        val song = PlaybackStateRepository.currentSong
        if (song == null) {
            return -1L
        }

        val repeatMode = PlaybackStateRepository.repeatModeLiveData.value ?: PlaybackStateRepository.REPEAT_MODE_OFF
        if (repeatMode == PlaybackStateRepository.REPEAT_MODE_ONE) {
            return -1L
        }

        val queue = PlaybackStateRepository.activeQueue
        val isAlbumMode = queue?.sourceType == QueueSource.ALBUM
        val isPlaymixMode = queue?.sourceType == QueueSource.PLAYMIX

        if (isAlbumMode && automixEnabled) {
            return -1L
        }

        val queueIndex = queue?.currentIndex ?: -1
        val currentQueueIndex = if (queue?.songs?.getOrNull(queueIndex)?.id == song.id) {
            queueIndex
        } else {
            queue?.songs?.indexOfFirst { it.id == song.id } ?: -1
        }
        val nextSong: Song? = when {
            currentQueueIndex >= 0 -> queue?.songs?.getOrNull(currentQueueIndex + 1)
            else -> PlaybackStateRepository.getNextSong(exoPlayer?.currentMediaItemIndex ?: -1)
        }
        if (nextSong == null || nextSong.id == song.id) {
            return -1L
        }

        // —— PlayMix mode: trigger at exitPointMs (which already accounts for crossfade + gap) ——
        if (isPlaymixMode) {
            val transition = playmixTransitions["${song.id}:${nextSong.id}"]
            if (transition != null) {
                val exitMs = transition.exitPointMs.toLong()
                val crossfadeMs = transition.crossfadeDurationMs.toLong()
                val gapMs = transition.gapMs.toLong()
                val mixMode = transition.mixMode ?: "crossfade"

                // For hard_edit with a gap, we need extra time for fade-out + gap
                val triggerMs = when {
                    mixMode == "hard_edit" && gapMs > 0 -> exitMs - 300L // 300ms for fade-out animation
                    crossfadeMs == 0L && gapMs > 0 -> exitMs - 300L
                    else -> exitMs // exitPointMs is already set by the editor to be crossfadeMs before the end
                }
                return triggerMs.coerceAtLeast(0L)
            }
            return -1L
        }

        val player = exoPlayer
        val exoDurationMs = if (player != null && player.duration > 0) player.duration else -1L

        // Resolve song duration: prefer audioAnalysis (most accurate), fall back to ExoPlayer.
        val analysis = song.audioAnalysis
        val songDurationMs: Long = when {
            (analysis?.durationMs ?: 0) > 0 -> analysis!!.durationMs.toLong()
            exoDurationMs > 0               -> exoDurationMs
            else -> return -1L
        }

        if (isAlbumMode) return -1L  // album non-automix: handled elsewhere

        val actualMixDuration: Long = if (crossfadeMode == CrossfadeMode.INTELLIGENT_EQ) {
            // Intelligent mode always picks its own duration, regardless of the slider.
            val cached = predictedIntelligentDuration
            if (cached > 0L) {
                cached
            } else if (analysis != null && nextSong.audioAnalysis != null) {
                // Full analysis available — compute and CACHE the result.
                val predicted = transitionManager.predictIntelligentMixDuration(song, nextSong)
                predictedIntelligentDuration = predicted  // cache so next ticks are cheap
                predicted
            } else {
                // Fallback when analysis is missing — use a sensible 8-second default.
                8000L
            }
        } else {
            crossfadeDurationMs
        }

        if (actualMixDuration <= 0) {
            return -1L
        }

        val triggerPos = songDurationMs - actualMixDuration
        if (triggerPos <= 0) {
            return -1L
        }

        return triggerPos
    }



    private fun loadArtworkForSong(song: Song) {
        val imageUrl = song.coverUrl
        if (imageUrl.isNullOrBlank()) {
            // Si no hay URL, limpiamos el bitmap en el repositorio
            PlaybackStateRepository.setCurrentSongBitmap(null)
            return
        }

        serviceScope.launch(Dispatchers.Main) {
            Glide.with(this@MusicPlaybackService)
                .asBitmap()
                .load(imageUrl)
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        Log.d("ArtworkLoader", "✅ Imagen cargada para ${song.title}")
                        PlaybackStateRepository.setCurrentSongBitmap(resource)
                        updateMediaSessionMetadata()
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        Log.w("ArtworkLoader", "⚠️ Falló la carga de imagen para ${song.title}")
                        PlaybackStateRepository.setCurrentSongBitmap(null)
                        updateMediaSessionMetadata()
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {}
                })
        }
    }
    private fun playSongFromQueue(playWhenReady: Boolean = true) {
        val queue = PlaybackStateRepository.activeQueue ?: return
        val index = queue.currentIndex
        val songToPlay = queue.songs.getOrNull(index) ?: return
        if (exoPlayer == null) return
        progressiveFallbackSongId = null
        retryCount = 0
        val generation = playbackLoadGeneration.incrementAndGet()

        serviceScope.launch(Dispatchers.Main) {
            playbackQoeTracker.markPlayRequested(queue.sourceType.name, songToPlay.id)
            PlaybackStateRepository.setCurrentSong(songToPlay)
            loadArtworkForSong(songToPlay)
        }

        serviceScope.launch {
            val enrichedSongs = playbackQueueEnricher.enrich(
                songs = queue.songs,
                currentIndex = index
            )
            withContext(Dispatchers.Main) {
                if (generation != playbackLoadGeneration.get() ||
                    PlaybackStateRepository.activeQueue !== queue
                ) {
                    return@withContext
                }

                queue.songs = enrichedSongs
                queue.ensureEntryIds()
                val enrichedCurrentSong = enrichedSongs.getOrNull(index) ?: return@withContext
                PlaybackStateRepository.setCurrentSong(enrichedCurrentSong)
                PlaybackStateRepository.publishQueue()
                val player = exoPlayer ?: return@withContext
                val mediaItems = enrichedSongs.map(playbackMediaItemFactory::create)
                if (mediaItems.isEmpty()) return@withContext

                val startPosition = pendingStartPositionMs
                pendingStartPositionMs = 0L

                player.setMediaItems(mediaItems, index, startPosition)
                player.prepare()

                if (playWhenReady) {
                    if (!requestAudioFocus()) {
                        return@withContext
                    }
                    registerBecomingNoisyReceiver()
                    player.play()
                } else {
                    player.pause()
                    PlaybackStateRepository.setIsPlaying(false)
                }
                applyLoudnessNormalization(enrichedCurrentSong)

                currentSongStartTimeMs = System.currentTimeMillis()
                PlaybackStateRepository.streamReported = false
                updatePredictedDurationAsync()
                prepareNextTransitionPlayer(index)
            }
        }
    }

    private fun syncPlaybackConnect() {
        if (
            !::playbackConnectRepository.isInitialized ||
            !connectSyncInFlight.compareAndSet(false, true)
        ) {
            return
        }
        serviceScope.launch {
            try {
                // Media3 requires every ExoPlayer access to happen on its application
                // thread. Capture the local playback snapshot on main, then keep the
                // Connect request and command acknowledgements on this IO scope.
                val playbackState = withContext(Dispatchers.Main.immediate) {
                    buildConnectPlaybackState()
                }
                val commands = playbackConnectRepository.heartbeat(
                    playbackState
                )

                // NOTE: No auto-pause heuristic here.  Pausing the local player
                // is handled exclusively by:
                //   1. The YIELD command from the server (delivered via commands list).
                //   2. The explicit ACTION_PAUSE sent by PlaybackDevicesBottomSheet
                //      immediately after a successful transfer.
                // Previous iterations used a snapshot-based "ownership lost"
                // heuristic that caused spurious mid-song pauses due to stale or
                // temporarily inconsistent activeDeviceId values from the backend.

                commands
                    .sortedBy(PlaybackConnectCommandDTO::sequence)
                    .forEach { command ->
                        if (
                            playbackConnectRepository.wasCommandProcessed(
                                command.commandId
                            )
                        ) {
                            playbackConnectRepository.acknowledge(
                                command,
                                successful = true
                            )
                            return@forEach
                        }
                        val applied = runCatching {
                            applyPlaybackConnectCommand(command)
                        }.getOrElse { error ->
                            Log.e(
                                "ResonantConnect",
                                "No se pudo aplicar ${command.type}",
                                error
                            )
                            false
                        }
                        if (applied) {
                            playbackConnectRepository.markCommandProcessed(
                                command.commandId
                            )
                        }
                        playbackConnectRepository.acknowledge(
                            command = command,
                            successful = applied,
                            errorCode = if (applied) null else "command_not_applied"
                        )
                    }
            } finally {
                connectSyncInFlight.set(false)
            }
        }
    }

    private fun buildConnectPlaybackState(): PlaybackConnectStateDTO? {
        val queue = PlaybackStateRepository.activeQueue ?: return null
        if (queue.songs.isEmpty()) return null
        val safeCurrentIndex = queue.currentIndex.coerceIn(queue.songs.indices)
        val windowStart = if (queue.songs.size <= CONNECT_MAX_QUEUE_ITEMS) {
            0
        } else {
            (safeCurrentIndex - 50).coerceIn(
                0,
                queue.songs.size - CONNECT_MAX_QUEUE_ITEMS
            )
        }
        val windowEnd = (
            windowStart + CONNECT_MAX_QUEUE_ITEMS
        ).coerceAtMost(queue.songs.size)
        val queueItems = queue.songs
            .subList(windowStart, windowEnd)
            .map { song ->
                PlaybackConnectQueueItemDTO(
                    songId = song.id,
                    title = song.title,
                    artistName = song.artistName
                        ?: song.artists.joinToString(", ") { it.name }
                            .takeIf(String::isNotBlank),
                    coverUrl = song.coverUrl?.takeIf { url ->
                        '?' !in url && '#' !in url
                    },
                    durationMs = song.audioAnalysis
                        ?.durationMs
                        ?.toLong()
                        ?.takeIf { it > 0L }
                )
            }
        val player = exoPlayer
        return PlaybackConnectStateDTO(
            stateRevision = connectStateRevision.incrementAndGet(),
            queueRevision = PlaybackStateRepository.currentQueueRevision(),
            queueItems = queueItems,
            queueTruncated = queue.songs.size > queueItems.size,
            currentIndex = safeCurrentIndex - windowStart,
            positionMs = player?.currentPosition?.coerceAtLeast(0L) ?: 0L,
            durationMs = player
                ?.duration
                ?.takeIf { it > 0L && it != C.TIME_UNSET }
                ?: 0L,
            isPlaying = PlaybackStateRepository.isPlaying,
            sourceType = queue.sourceType.name,
            sourceId = queue.sourceId.takeIf(String::isNotBlank),
            repeatMode = PlaybackStateRepository.repeatModeLiveData.value
                ?: PlaybackStateRepository.REPEAT_MODE_OFF,
            shuffleEnabled =
                PlaybackStateRepository.isShuffleEnabledLiveData.value == true
        )
    }

    private suspend fun applyPlaybackConnectCommand(
        command: PlaybackConnectCommandDTO
    ): Boolean {
        return when (command.type.uppercase()) {
            "YIELD", "PAUSE" -> {
                withContext(Dispatchers.Main) { pause() }
                true
            }
            "RESUME" -> {
                withContext(Dispatchers.Main) { resume() }
                true
            }
            "TRANSFER_IN" -> {
                val state = command.playback ?: return false
                val items = state.queueItems.orEmpty()
                if (items.isEmpty()) return false
                val songs = items.map { item ->
                    Song(
                        id = item.songId,
                        title = item.title,
                        coverUrl = item.coverUrl,
                        artistName = item.artistName
                    )
                }
                val requestedSourceType = runCatching {
                    QueueSource.valueOf(state.sourceType.uppercase())
                }.getOrDefault(QueueSource.QUEUE)
                val sourceType = requestedSourceType
                    .takeUnless { it == QueueSource.PLAYMIX }
                    ?: QueueSource.QUEUE
                withContext(Dispatchers.Main) {
                    cancelCurrentTransitionAndRestoreEffects()
                    // Connect took over this device with a new queue — any
                    // radio station we had running belonged to the previous
                    // context and must not leak into the new one.
                    resetRadioSession()
                    pendingStartPositionMs = state.positionMs.coerceAtLeast(0L)
                    PlaybackStateRepository.activeQueue = PlaybackQueue(
                        sourceId = state.sourceId ?: "connect_transfer",
                        sourceType = sourceType,
                        songs = songs,
                        currentIndex = state.currentIndex.coerceIn(songs.indices)
                    )
                    PlaybackStateRepository.setRepeatMode(state.repeatMode)
                    PlaybackStateRepository.setIsShuffleEnabled(
                        state.shuffleEnabled
                    )
                    originalQueueSongs =
                        if (state.shuffleEnabled) songs.toList() else null
                    exoPlayer?.repeatMode = when (state.repeatMode) {
                        PlaybackStateRepository.REPEAT_MODE_ONE ->
                            Player.REPEAT_MODE_ONE
                        PlaybackStateRepository.REPEAT_MODE_ALL ->
                            Player.REPEAT_MODE_ALL
                        else -> Player.REPEAT_MODE_OFF
                    }
                    // Don't call exoPlayer.play() directly here — let
                    // playSongFromQueue handle the full playback lifecycle
                    // (audio focus, noisy receiver, state updates) so that
                    // TRANSFER_IN follows the same code path as local play.
                    playSongFromQueue(playWhenReady = state.isPlaying)
                    persistPlaybackState(force = true)
                }
                true
            }
            else -> false
        }
    }

    private fun prepareNextTransitionPlayer(currentQueueIndex: Int) {
        val queue = PlaybackStateRepository.activeQueue ?: return
        val nextSong = queue.songs.getOrNull(currentQueueIndex + 1)
        val needsSecondPlayer = nextSong != null && (
            queue.sourceType == QueueSource.PLAYMIX ||
                crossfadeMode == CrossfadeMode.INTELLIGENT_EQ ||
                (queue.sourceType == QueueSource.ALBUM && automixEnabled) ||
                crossfadeDurationMs > 0L
            )

        if (needsSecondPlayer) {
            transitionManager.preloadNextSong(nextSong)
        } else {
            cancelCurrentTransitionAndRestoreEffects()
        }
    }

    // ── Resonant Radio (autoplay at end of queue) ──────────────────────────

    /**
     * Push [songId] into the "recently played" rolling window that we send
     * to the radio server, so it won't return anything we just heard.
     */
    private fun recordRecentSong(songId: String) {
        if (songId.isBlank()) return
        // O(n) but n is bounded to RADIO_RECENT_WINDOW_SIZE (~30), fine.
        radioRecentSongIds.remove(songId)
        radioRecentSongIds.addLast(songId)
        while (radioRecentSongIds.size > RADIO_RECENT_WINDOW_SIZE) {
            radioRecentSongIds.removeFirst()
        }
    }

    /**
     * Prefetch radio tracks when the queue is running out.  Called from
     * [onMediaItemTransition] as the user advances through songs.
     *
     * Skips silently if:
     *  - autoplay is disabled in settings,
     *  - the queue is a PlayMix (has its own curated ending semantics),
     *  - the queue is DOWNLOADED_SONGS (user is in offline mode),
     *  - we're already fetching,
     *  - or fewer than [RADIO_PREFETCH_THRESHOLD] songs remain but there's
     *    still work to do (the check catches the transition into that state).
     */
    private fun maybePrefetchRadio() {
        if (!autoplayRadioEnabled) return
        if (isSeeking) return

        val queue = PlaybackStateRepository.activeQueue ?: return
        if (queue.songs.isEmpty()) return

        // Don't hijack playmixes (curated arc) or offline sessions.
        if (queue.sourceType == QueueSource.PLAYMIX ||
            queue.sourceType == QueueSource.DOWNLOADED_SONGS
        ) return

        // Repeat-all naturally cycles forever, no radio needed.
        if (PlaybackStateRepository.repeatModeLiveData.value ==
            PlaybackStateRepository.REPEAT_MODE_ALL
        ) return

        val songsRemainingAfterCurrent =
            queue.songs.size - 1 - queue.currentIndex
        if (songsRemainingAfterCurrent > RADIO_PREFETCH_THRESHOLD) return

        if (!radioPrefetchInFlight.compareAndSet(false, true)) return

        val sourceType = queue.sourceType.name
        val sourceId = queue.sourceId
        val lastSongId = queue.songs.getOrNull(queue.currentIndex)?.id
        val recentIds = radioRecentSongIds.toList()

        serviceScope.launch {
            try {
                val newSongs = radioRepository.fetchNextBatch(
                    sourceType = sourceType,
                    sourceId = sourceId,
                    lastSongId = lastSongId,
                    recentSongIds = recentIds
                )
                if (newSongs.isEmpty()) return@launch

                withContext(Dispatchers.Main) { appendRadioSongsToQueue(newSongs) }
            } finally {
                radioPrefetchInFlight.set(false)
            }
        }
    }

    /**
     * Appends radio-fetched songs to the active queue in place.  Runs on
     * main because it mutates the ExoPlayer timeline.  Preserves the
     * queue's original [QueueSource] — the sourceType stays as e.g. ALBUM
     * for Connect purposes, and we surface the radio state separately via
     * [PlaybackStateRepository.radioSessionLiveData].
     */
    @androidx.annotation.MainThread
    private fun appendRadioSongsToQueue(newSongs: List<com.example.resonant.data.models.Song>) {
        val queue = PlaybackStateRepository.activeQueue ?: return
        val existingIds = queue.songs.mapTo(HashSet(queue.songs.size)) { it.id }
        val toAppend = newSongs.filter { it.id.isNotBlank() && it.id !in existingIds }
        if (toAppend.isEmpty()) return

        queue.appendSongs(toAppend)
        PlaybackStateRepository.publishQueue()
        PlaybackStateRepository.setRadioSession(radioRepository.currentSession)

        // Push the new items into the ExoPlayer timeline too, otherwise the
        // native "play next" would still hit end-of-queue.
        val player = exoPlayer ?: return
        val newMediaItems = toAppend.map(playbackMediaItemFactory::create)
        player.addMediaItems(newMediaItems)

        Log.d(
            "MusicService",
            "🎧 Resonant Radio: added ${toAppend.size} songs " +
                "(seed=${radioRepository.currentSession?.seedName})"
        )
    }

    /**
     * Clears the current radio session — call whenever the user starts a
     * brand-new playback context (tapped a song, playlist, album,
     * received TRANSFER_IN, etc.) so the next queue-end starts a fresh
     * station instead of continuing the previous one.
     */
    private fun resetRadioSession() {
        radioRepository.clearSession()
        radioRecentSongIds.clear()
        PlaybackStateRepository.setRadioSession(null)
    }

    private fun synchronizeExoPlayerQueue() {
        val queue = PlaybackStateRepository.activeQueue ?: return
        val player = exoPlayer ?: return

        if (queue.songs.isEmpty()) {
            player.clearMediaItems()
            return
        }

        // Si el player está vacío, carga inicial
        if (player.mediaItemCount == 0) {
             val items = queue.songs.map { createMediaItem(it) }
             player.setMediaItems(items, queue.currentIndex, 0L)
             player.prepare()
             if (!requestAudioFocus()) return
             registerBecomingNoisyReceiver()
             player.play()
             return
        }

        val currentSongId = PlaybackStateRepository.currentSong?.id
        val newSongs = queue.songs
        
        // Buscar dónde debería estar la canción actual en la NUEVA lista
        val logicalCurrentIndex = queue.currentIndex
        val newIndexTarget = logicalCurrentIndex.takeIf { index ->
            index in newSongs.indices && newSongs[index].id == currentSongId
        } ?: newSongs.indexOfFirst { it.id == currentSongId }

        // Si la canción actual ya no está en la lista o algo raro pasa, fallback a recarga total
        if (newIndexTarget == -1) {
             val items = newSongs.map { createMediaItem(it) }
             player.setMediaItems(items, 0, 0L)
             player.prepare()
             if (!requestAudioFocus()) return
             registerBecomingNoisyReceiver()
             player.play()
             return
        }

        // --- ACTUALIZACIÓN QUIRÚRGICA (Sin interrupción) ---
        
        val currentExoIndex = player.currentMediaItemIndex
        
        // 1. Eliminar lo posterior
        if (currentExoIndex < player.mediaItemCount - 1) {
             player.removeMediaItems(currentExoIndex + 1, player.mediaItemCount)
        }
        
        // 2. Eliminar lo anterior
        if (currentExoIndex > 0) {
             player.removeMediaItems(0, currentExoIndex)
        }
        
        // Ahora en ExoPlayer solo queda [CurrentSong] en index 0
        
        // 3. Insertar nuevos anteriores
        val itemsBefore = newSongs.subList(0, newIndexTarget).map { createMediaItem(it) }
        if (itemsBefore.isNotEmpty()) {
            player.addMediaItems(0, itemsBefore)
        }
        
        // 4. Insertar nuevos posteriores
        val itemsAfter = newSongs.subList(newIndexTarget + 1, newSongs.size).map { createMediaItem(it) }
        if (itemsAfter.isNotEmpty()) {
            player.addMediaItems(player.mediaItemCount, itemsAfter)
        }
        
        Log.d("ExoPlayer", "Cola Sincronizada QUIRÚRGICAMENTE. Playing: ${player.isPlaying}")
    }

    private fun createMediaItem(song: Song): MediaItem {
        return playbackMediaItemFactory.create(song)
    }

    private fun persistPlaybackState(force: Boolean = false) {
        if (!::playbackStateStore.isInitialized) return
        val queue = PlaybackStateRepository.activeQueue ?: return
        val player = exoPlayer
        val now = System.currentTimeMillis()
        if (!force && now - lastPlaybackStateSaveAtMs < PLAYBACK_STATE_SAVE_INTERVAL_MS) {
            return
        }

        val currentSong = PlaybackStateRepository.currentSong
        val durationMs = player?.duration
            ?.takeIf { it > 0L && it != C.TIME_UNSET }
            ?: currentSong?.audioAnalysis?.durationMs?.toLong()?.takeIf { it > 0L }
            ?: currentSong?.duration
                ?.toDoubleOrNull()
                ?.takeIf { it > 0.0 }
                ?.times(1_000.0)
                ?.toLong()
            ?: 0L
        playbackStateStore.save(
            queue = queue,
            currentSongId = player?.currentMediaItem?.mediaId ?: currentSong?.id,
            positionMs = player?.currentPosition ?: 0L,
            durationMs = durationMs,
            repeatMode = PlaybackStateRepository.repeatModeLiveData.value
                ?: PlaybackStateRepository.REPEAT_MODE_OFF,
            shuffleEnabled = PlaybackStateRepository.isShuffleEnabledLiveData.value ?: false,
            synchronous = force
        )
        lastPlaybackStateSaveAtMs = now
    }

    private fun restorePlaybackState() {
        val restored = playbackStateStore.restore() ?: return
        val player = exoPlayer ?: return
        val queue = restored.queue
        val currentSong = queue.songs.getOrNull(queue.currentIndex) ?: return

        PlaybackStateRepository.restore(restored)
        currentSongStartTimeMs = 0L
        loadArtworkForSong(currentSong)

        player.repeatMode = when (restored.repeatMode) {
            PlaybackStateRepository.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ONE
            PlaybackStateRepository.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        player.setMediaItems(
            queue.songs.map(playbackMediaItemFactory::create),
            queue.currentIndex,
            restored.positionMs
        )
        applyLoudnessNormalization(currentSong)
        updateMediaSessionMetadata()
    }

    private fun reportCurrentSongFinished(wasSkipped: Boolean) {
        // Si ya se reportó (ej. en una transición), no hacerlo de nuevo.
        if (PlaybackStateRepository.streamReported) {
            return
        }

        // 1. Obtenemos los datos de la canción que acaba de sonar
        val songToReport = PlaybackStateRepository.currentSong ?: return
        val startTime = currentSongStartTimeMs
        if (startTime <= 0L) {
            // A restored, paused session has metadata and position but has not
            // accumulated listening time in this process yet.
            Log.d("StreamAPI", "Sesión restaurada sin tiempo activo; no se reporta stream.")
            return
        }
        val playSource = PlaybackStateRepository.activeQueue?.sourceType?.name ?: "UNKNOWN"

        // 2. Calculamos la duración
        val endTime = System.currentTimeMillis()
        val durationInSeconds = ((endTime - startTime) / 1000).toInt()

        // 3. Seguridad: No reportar si se escuchó muy poco (ej. < 5 seg)
        if (durationInSeconds < 5) {
            Log.d("StreamAPI", "Duración de $durationInSeconds s es muy corta. No se reporta.")
            return
        }

        // 4. Llamamos a la función de red (que ya tienes correcta)
        Log.d("StreamAPI", "Reportando: Song=${songToReport.title}, Duration=${durationInSeconds}s, Skipped=$wasSkipped")
        reportStream(
            songId = songToReport.id,
            durationInSeconds = durationInSeconds,
            wasSkipped = wasSkipped,
            playSource = playSource
        )

        // 5. Marcamos como reportada para evitar duplicados
        PlaybackStateRepository.streamReported = true
    }
    private fun reportStream(songId: String, durationInSeconds: Int, wasSkipped: Boolean, playSource: String) {
        serviceScope.launch {
            try {
                val userManager = UserManager(applicationContext)
                val userId = userManager.getUserId()

                if (userId.isNullOrEmpty()) {
                    Log.e("StreamAPI", "❌ Error al reportar stream: UserId no encontrado. Abortando.")
                    return@launch // Abortar la corrutina si no hay UserId
                }

                // 2. Construir el objeto DTO (¡Añadiendo el userId!)
                val streamData = AddStreamDTO(
                    songId = songId,
                    userId = userId, // 🔥 ¡NUEVA LÍNEA!
                    listenDurationInSeconds = durationInSeconds,
                    wasSkipped = wasSkipped,
                    playSource = playSource
                )

                Log.i("StreamAPI", "Reportando stream: $streamData")

                val songManager = SongManager(applicationContext)
                songManager.addStream(streamData)

                Log.d("StreamAPI", "✅ Stream reportado para $songId")

            } catch (e: Exception) {
                Log.e("StreamAPI", "❌ Error al reportar stream", e)
            }
        }
    }
    private fun updateUiAndSystem() {
        triggerNotificationUpdate()

        // Su única responsabilidad ahora es el estado de reproducción (Play/Pause y posición)

        // La notificación se actualiza desde updateMediaSessionMetadata,
        // pero podemos dejar una llamada aquí para el estado Play/Pause

        Log.d("MusicService", "🔄 Estado de reproducción actualizado")
    }

    /**
     * Executes the actual "load queue and start playing" logic factored out of
     * [ACTION_PLAY] and [ACTION_PLAY_CONNECT_CONFIRMED] so both paths share
     * identical behaviour.
     */
    private fun startLocalPlayback(
        originalIntent: Intent,
        song: Song,
        songList: ArrayList<Song>,
        index: Int,
        sourceType: QueueSource,
        sourceId: String
    ) {
        // A brand-new playback context — the previous radio station is
        // no longer relevant.  Next queue-end will seed a fresh one.
        resetRadioSession()
        lastManualChangeTimestamp = System.currentTimeMillis()

        PlaybackStateRepository.activeQueue = PlaybackQueue(
            songs = songList,
            currentIndex = index,
            sourceId = sourceId,
            sourceType = sourceType
        )

        if (sourceType == QueueSource.PLAYMIX && sourceId.isNotBlank()) {
            playmixTransitions = emptyMap()
            playmixSongIdMap   = emptyMap()
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val detail = PlaymixManager(applicationContext).getPlaymixDetail(sourceId)
                    val songIdToPlaymixId = detail.songs.associate { it.songId to it.playmixSongId }
                    val transitionMap = mutableMapOf<String, PlaymixTransitionDTO>()
                    for (t in detail.transitions) {
                        val fromSongId = detail.songs.firstOrNull {
                            it.playmixSongId == t.fromPlaymixSongId
                        }?.songId
                        val toSongId   = detail.songs.firstOrNull {
                            it.playmixSongId == t.toPlaymixSongId
                        }?.songId
                        if (fromSongId != null && toSongId != null) {
                            transitionMap["$fromSongId:$toSongId"] = t
                        }
                    }
                    withContext(Dispatchers.Main) {
                        playmixSongIdMap   = songIdToPlaymixId
                        playmixTransitions = transitionMap
                        Log.d("MusicService", "🔌 PlayMix transitions loaded: ${transitionMap.size}")
                    }
                } catch (e: Exception) {
                    Log.e("MusicService", "Failed to load PlayMix transitions", e)
                }
            }
        } else {
            playmixTransitions = emptyMap()
            playmixSongIdMap   = emptyMap()
        }

        pendingStartPositionMs = originalIntent.getIntExtra(EXTRA_START_POSITION_MS, 0).toLong()
        playSongFromQueue()

        PlaybackStateRepository.setIsShuffleEnabled(false)
        originalQueueSongs = null
    }
    private fun handleAction(action: String, intent: Intent) {
        when (action) {
            ACTION_PLAY -> {
                reportCurrentSongFinished(wasSkipped = true)

                cancelCurrentTransitionAndRestoreEffects()

                val song = intent.getParcelableExtra(EXTRA_CURRENT_SONG, Song::class.java)
                val songList = intent.getParcelableArrayListExtra(SONG_LIST, Song::class.java)
                val index = intent.getIntExtra(EXTRA_CURRENT_INDEX, -1)

                val sourceType = intent.getSerializableExtra(EXTRA_QUEUE_SOURCE, QueueSource::class.java)
                    ?: QueueSource.UNKNOWN
                val sourceId = intent.getStringExtra(EXTRA_QUEUE_SOURCE_ID) ?: ""

                if (song != null && !songList.isNullOrEmpty() && index != -1) {

                    // ── Resonant Connect conflict detection ──────────────────────────
                    // If another device is currently the active Connect player, don't
                    // silently steal control.  Instead stash the intent and let the
                    // Activity show the "Ahora suena en X – ¿Escucharlo aquí?" dialog.
                    // The user's answer comes back as ACTION_PLAY_CONNECT_CONFIRMED or
                    // is simply ignored (stash entry expires naturally).
                    if (::playbackConnectRepository.isInitialized) {
                        val connectState = playbackConnectRepository.uiState.value
                        val remoteDevice = connectState.activeDevice
                        if (connectState.supported &&
                            remoteDevice != null &&
                            remoteDevice.deviceId != playbackConnectRepository.localDeviceId
                        ) {
                            // Stash this intent under a unique tag.
                            val tag = UUID.randomUUID().toString()
                            pendingConnectPlayIntents[tag] = intent

                            // Tell the active device name and current track so the
                            // dialog can show them.
                            val remoteTrack = remoteDevice.playback
                                ?.queueItems
                                ?.getOrNull(remoteDevice.playback.currentIndex)
                                ?.title

                            playbackConnectRepository.emitConflict(
                                ConnectConflictEvent(
                                    activeDeviceName = remoteDevice.name,
                                    activeDeviceId   = remoteDevice.deviceId,
                                    nowPlayingTitle  = remoteTrack,
                                    pendingActionTag = tag
                                )
                            )
                            // Do NOT start local playback — wait for user confirmation.
                            return
                        }
                    }
                    // ── No conflict: play immediately ────────────────────────────────
                    startLocalPlayback(intent, song, songList, index, sourceType, sourceId)
                }
            }

            ACTION_PLAY_CONNECT_CONFIRMED -> {
                // The user confirmed "Escuchar aquí" in the conflict dialog.
                val tag = intent.getStringExtra(EXTRA_CONNECT_PENDING_TAG) ?: return
                val pending = pendingConnectPlayIntents.remove(tag) ?: return

                val song = pending.getParcelableExtra(EXTRA_CURRENT_SONG, Song::class.java)
                val songList = pending.getParcelableArrayListExtra(SONG_LIST, Song::class.java)
                val index = pending.getIntExtra(EXTRA_CURRENT_INDEX, -1)
                val sourceType = pending.getSerializableExtra(EXTRA_QUEUE_SOURCE, QueueSource::class.java)
                    ?: QueueSource.UNKNOWN
                val sourceId = pending.getStringExtra(EXTRA_QUEUE_SOURCE_ID) ?: ""

                if (song == null || songList.isNullOrEmpty() || index == -1) return

                // First claim Connect ownership so the other device gets a YIELD.
                if (::playbackConnectRepository.isInitialized) {
                    serviceScope.launch {
                        val result = playbackConnectRepository.transferTo(
                            playbackConnectRepository.localDeviceId
                        )
                    }
                }
                startLocalPlayback(pending, song, songList, index, sourceType, sourceId)
            }
            ACTION_TOGGLE_SHUFFLE -> toggleShuffle()
            ACTION_TOGGLE_REPEAT -> toggleRepeat()
            ACTION_CONNECT_SYNC -> syncPlaybackConnect()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            // ACTION_ENSURE_CONNECT: nothing extra needed — the service is already
            // running and heartbeats have been started in onCreate(). This action
            // exists purely to keep the service alive via START_STICKY so that the
            // Connect heartbeat loop continues even when there is no active playback.
            ACTION_NEXT -> playNext()
            ACTION_PREVIOUS -> playPrevious()
            ACTION_SEEK_TO -> seekTo(intent.getIntExtra(EXTRA_SEEK_POSITION, 0).toLong())
            ACTION_UPDATE_QUEUE -> {
                val songList = intent.getParcelableArrayListExtra(SONG_LIST, Song::class.java) ?: return
                updateSongs(songList)
            }
            ACTION_SHUTDOWN -> {
                clearPlaybackStateOnDestroy = true
                if (::playbackStateStore.isInitialized) {
                    playbackStateStore.clear()
                }
                stop()
            }
            ACTION_REQUEST_STATE -> {
                updateUiAndSystem()
            }

            ACTION_START_SEEK -> {
                isSeeking = true
                stopSeekBarUpdates()
            }
            ACTION_STOP_SEEK -> {
                isSeeking = false
                startSeekBarUpdates()
            }
            ACTION_PLAYLIST_MODIFIED -> {
                val modifiedPlaylistId = intent.getStringExtra(EXTRA_PLAYLIST_ID)

                // Comprueba si la playlist modificada es la que se está reproduciendo AHORA
                val activeQueue = PlaybackStateRepository.activeQueue
                if (activeQueue?.sourceType == QueueSource.PLAYLIST && activeQueue.sourceId == modifiedPlaylistId) {
                    Log.d("MusicService", "La playlist activa ($modifiedPlaylistId) ha sido modificada. Sincronizando...")

                    serviceScope.launch {
                        val playlistManager = PlaylistManager(applicationContext)
                        val newSongs = playlistManager.getSongsByPlaylistId(modifiedPlaylistId)
                        withContext(Dispatchers.Main) {
                            updateSongs(newSongs)
                        }
                    }
                }
            }
            // ✅ AÑADE ESTE NUEVO CASO
            ACTION_SONG_MARKED_FOR_DELETION -> {
                val playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID)
                val songId = intent.getStringExtra(EXTRA_SONG_ID)
                val activeQueue = PlaybackStateRepository.activeQueue

                // Si la canción eliminada es de la playlist activa, la añadimos a la lista de pendientes
                if (songId != null && activeQueue?.sourceType == QueueSource.PLAYLIST && activeQueue.sourceId == playlistId) {
                    Log.d("MusicService", "🎵 Marcando canción $songId para eliminación diferida.")
                    pendingDeletionSongIds.add(songId)
                }
            }
        }
    }

    private suspend fun handleQueueCommand(
        action: String,
        arguments: Bundle
    ): SessionResult {
        val expectedRevision = arguments
            .takeIf { it.containsKey(QueueCommands.ARG_EXPECTED_REVISION) }
            ?.getLong(QueueCommands.ARG_EXPECTED_REVISION)
        if (
            expectedRevision != null &&
            expectedRevision != PlaybackStateRepository.currentQueueRevision()
        ) {
            return queueCommandResult(
                SessionResult.RESULT_ERROR_INVALID_STATE,
                "La cola cambió; se ha actualizado antes de aplicar la acción"
            )
        }

        val resolvedSong = if (
            action == QueueCommands.PLAY_NEXT ||
            action == QueueCommands.ADD
        ) {
            val songId = arguments.getString(QueueCommands.ARG_SONG_ID)
                ?.takeIf(String::isNotBlank)
                ?: return queueCommandResult(
                    SessionResult.RESULT_ERROR_BAD_VALUE,
                    "Falta la canción"
                )
            val song = SongManager(applicationContext).getSongById(songId)
                ?: return queueCommandResult(
                    SessionResult.RESULT_ERROR_BAD_VALUE,
                    "La canción ya no está disponible"
                )
            playbackQueueEnricher.enrich(listOf(song), 0).firstOrNull() ?: song
        } else {
            null
        }

        return withContext(Dispatchers.Main) {
            when (action) {
                QueueCommands.PLAY_NEXT -> {
                    enqueueResolvedSong(resolvedSong!!, playNext = true)
                    queueCommandResult(SessionResult.RESULT_SUCCESS, "Se reproducirá a continuación")
                }

                QueueCommands.ADD -> {
                    enqueueResolvedSong(resolvedSong!!, playNext = false)
                    queueCommandResult(SessionResult.RESULT_SUCCESS, "Añadida a la cola")
                }

                QueueCommands.MOVE -> moveUpcomingQueueItem(
                    arguments.getInt(QueueCommands.ARG_FROM_INDEX, -1),
                    arguments.getInt(QueueCommands.ARG_TO_INDEX, -1)
                )

                QueueCommands.REMOVE -> removeUpcomingQueueItem(
                    arguments.getInt(QueueCommands.ARG_QUEUE_INDEX, -1)
                )

                QueueCommands.PLAY_INDEX -> playQueueIndex(
                    arguments.getInt(QueueCommands.ARG_QUEUE_INDEX, -1)
                )

                QueueCommands.CLEAR_UPCOMING -> clearUpcomingQueue()

                QueueCommands.SHUFFLE_UPCOMING -> shuffleUpcomingQueue()
                else -> queueCommandResult(
                    SessionResult.RESULT_ERROR_NOT_SUPPORTED,
                    "Comando no compatible"
                )
            }
        }
    }

    private fun enqueueResolvedSong(song: Song, playNext: Boolean) {
        val existingQueue = PlaybackStateRepository.activeQueue
        if (existingQueue == null || existingQueue.songs.isEmpty()) {
            PlaybackStateRepository.activeQueue = PlaybackQueue(
                sourceId = "manual_queue",
                sourceType = QueueSource.QUEUE,
                songs = listOf(song),
                currentIndex = 0
            )
            PlaybackStateRepository.setCurrentSong(song)
            playSongFromQueue()
            persistPlaybackState(force = true)
            return
        }

        prepareForManualQueueMutation(existingQueue)
        val songs = existingQueue.songs.toMutableList()
        val entryIds = existingQueue.entryIds.toMutableList()
        val insertionIndex = if (playNext) {
            (existingQueue.currentIndex + 1).coerceAtMost(songs.size)
        } else {
            songs.size
        }
        songs.add(insertionIndex, song)
        entryIds.add(insertionIndex, UUID.randomUUID().toString())
        existingQueue.songs = songs
        existingQueue.entryIds = entryIds
        finishQueueMutation(existingQueue)
    }

    private fun moveUpcomingQueueItem(fromIndex: Int, toIndex: Int): SessionResult {
        val queue = PlaybackStateRepository.activeQueue
            ?: return queueCommandResult(
                SessionResult.RESULT_ERROR_INVALID_STATE,
                "No hay una cola activa"
            )
        if (
            fromIndex !in queue.songs.indices ||
            toIndex !in queue.songs.indices ||
            fromIndex <= queue.currentIndex ||
            toIndex <= queue.currentIndex
        ) {
            return queueCommandResult(
                SessionResult.RESULT_ERROR_BAD_VALUE,
                "Solo se pueden ordenar las canciones pendientes"
            )
        }
        if (PlaybackStateRepository.isShuffleEnabledLiveData.value == true) {
            return queueCommandResult(
                SessionResult.RESULT_ERROR_INVALID_STATE,
                "Desactiva el modo aleatorio para ordenar la cola"
            )
        }
        if (queue.sourceType == QueueSource.PLAYMIX) {
            return queueCommandResult(
                SessionResult.RESULT_ERROR_INVALID_STATE,
                "Una PlayMix conserva el orden de sus transiciones"
            )
        }

        val songs = queue.songs.toMutableList()
        val ids = queue.entryIds.toMutableList()
        val movedSong = songs.removeAt(fromIndex)
        val movedId = ids.removeAt(fromIndex)
        songs.add(toIndex, movedSong)
        ids.add(toIndex, movedId)
        queue.songs = songs
        queue.entryIds = ids
        prepareForManualQueueMutation(queue)
        finishQueueMutation(queue)
        return queueCommandResult(SessionResult.RESULT_SUCCESS, "Cola reordenada")
    }

    private fun removeUpcomingQueueItem(index: Int): SessionResult {
        val queue = PlaybackStateRepository.activeQueue
            ?: return queueCommandResult(
                SessionResult.RESULT_ERROR_INVALID_STATE,
                "No hay una cola activa"
            )
        if (index !in queue.songs.indices || index <= queue.currentIndex) {
            return queueCommandResult(
                SessionResult.RESULT_ERROR_BAD_VALUE,
                "No se puede eliminar la canción actual ni el historial"
            )
        }
        prepareForManualQueueMutation(queue)
        queue.songs = queue.songs.toMutableList().apply { removeAt(index) }
        queue.entryIds = queue.entryIds.toMutableList().apply { removeAt(index) }
        finishQueueMutation(queue)
        return queueCommandResult(SessionResult.RESULT_SUCCESS, "Eliminada de la cola")
    }

    private fun playQueueIndex(index: Int): SessionResult {
        val queue = PlaybackStateRepository.activeQueue
            ?: return queueCommandResult(
                SessionResult.RESULT_ERROR_INVALID_STATE,
                "No hay una cola activa"
            )
        if (index !in queue.songs.indices) {
            return queueCommandResult(
                SessionResult.RESULT_ERROR_BAD_VALUE,
                "La canción ya no está en la cola"
            )
        }
        cancelCurrentTransitionAndRestoreEffects()
        moveToQueueIndex(index)
        PlaybackStateRepository.publishQueue()
        persistPlaybackState(force = true)
        return queueCommandResult(SessionResult.RESULT_SUCCESS, "Reproduciendo")
    }

    private fun clearUpcomingQueue(): SessionResult {
        val queue = PlaybackStateRepository.activeQueue
            ?: return queueCommandResult(
                SessionResult.RESULT_ERROR_INVALID_STATE,
                "No hay una cola activa"
            )
        if (queue.currentIndex >= queue.songs.lastIndex) {
            return queueCommandResult(SessionResult.RESULT_SUCCESS, "No había canciones pendientes")
        }
        prepareForManualQueueMutation(queue)
        val keepCount = queue.currentIndex + 1
        queue.songs = queue.songs.take(keepCount)
        queue.entryIds = queue.entryIds.take(keepCount)
        finishQueueMutation(queue)
        return queueCommandResult(SessionResult.RESULT_SUCCESS, "Cola pendiente vaciada")
    }

    private fun shuffleUpcomingQueue(): SessionResult {
        val queue = PlaybackStateRepository.activeQueue
            ?: return queueCommandResult(
                SessionResult.RESULT_ERROR_INVALID_STATE,
                "No hay una cola activa"
            )
        if (queue.sourceType == QueueSource.PLAYMIX) {
            return queueCommandResult(
                SessionResult.RESULT_ERROR_INVALID_STATE,
                "Una PlayMix conserva el orden de sus transiciones"
            )
        }
        val upcomingStart = queue.currentIndex + 1
        if (queue.songs.size - upcomingStart < 2) {
            return queueCommandResult(
                SessionResult.RESULT_SUCCESS,
                "No hay suficientes canciones pendientes para mezclar"
            )
        }

        prepareForManualQueueMutation(queue)
        val prefixSongs = queue.songs.take(upcomingStart)
        val prefixIds = queue.entryIds.take(upcomingStart)
        val shuffledUpcoming = queue.songs
            .drop(upcomingStart)
            .zip(queue.entryIds.drop(upcomingStart))
            .shuffled()
        queue.songs = prefixSongs + shuffledUpcoming.map { it.first }
        queue.entryIds = prefixIds + shuffledUpcoming.map { it.second }
        finishQueueMutation(queue)
        return queueCommandResult(SessionResult.RESULT_SUCCESS, "Próximas canciones mezcladas")
    }

    private fun prepareForManualQueueMutation(queue: PlaybackQueue) {
        cancelCurrentTransitionAndRestoreEffects()
        queue.ensureEntryIds()
        if (PlaybackStateRepository.isShuffleEnabledLiveData.value == true) {
            PlaybackStateRepository.setIsShuffleEnabled(false)
            originalQueueSongs = null
        }
        if (queue.sourceType == QueueSource.PLAYMIX) {
            playmixTransitions = emptyMap()
            playmixSongIdMap = emptyMap()
        }
        queue.sourceType = QueueSource.QUEUE
        queue.sourceId = "manual_queue"
    }

    private fun finishQueueMutation(queue: PlaybackQueue) {
        queue.currentIndex = queue.currentIndex.coerceIn(queue.songs.indices)
        synchronizeExoPlayerQueue()
        PlaybackStateRepository.publishQueue()
        persistPlaybackState(force = true)
        prepareNextTransitionPlayer(queue.currentIndex)
    }

    private fun queueCommandResult(code: Int, message: String): SessionResult {
        return SessionResult(
            code,
            Bundle().apply { putString(QueueCommands.RESULT_MESSAGE, message) }
        )
    }

    private fun observeSettings() {
        serviceScope.launch {
            settingsManager.automixEnabledFlow.collect { isEnabled ->
                automixEnabled = isEnabled
                Log.d("MusicService", "Automix para Álbumes: ${if(isEnabled) "ACTIVADO" else "DESACTIVADO"}")
            }
        }
        serviceScope.launch {
            settingsManager.crossfadeDurationFlow.collect { seconds ->
                crossfadeDurationMs = seconds * 1000L
                Log.d("MusicService", "Duración Crossfade: ${crossfadeDurationMs}ms")
            }
        }
        serviceScope.launch {
            settingsManager.crossfadeModeFlow.collect { mode ->
                crossfadeMode = mode
                updatePredictedDurationAsync()
                Log.d("MusicService", "Modo Crossfade: $mode")
            }
        }
        serviceScope.launch {
            settingsManager.loudnessNormalizationFlow.collect { isEnabled ->
                loudnessNormalizationEnabled = isEnabled
                Log.d("MusicService", "Normalización de Audio: ${if(isEnabled) "ACTIVADA" else "DESACTIVADA"}")
                // Reaplicar volumen inmediatamente — debe ser en el hilo principal
                val currentSong = PlaybackStateRepository.currentSong
                withContext(Dispatchers.Main) {
                    applyLoudnessNormalization(currentSong)
                }
            }
        }
        serviceScope.launch {
            settingsManager.equalizerSettingsFlow.collect { equalizerSettings ->
                withContext(Dispatchers.Main) {
                    audioEqualizerController.updateSettings(equalizerSettings)
                    exoPlayer?.let { player ->
                        if (!transitionManager.isTransitioning) {
                            audioEqualizerController.bindToSession(player.audioSessionId)
                        }
                    }
                    applyLoudnessNormalization(PlaybackStateRepository.currentSong)
                }
            }
        }
        serviceScope.launch {
            settingsManager.streamingQualityFlow.collect { quality ->
                val changed = streamingQuality != quality
                streamingQuality = quality
                withContext(Dispatchers.Main) {
                    exoPlayer?.let(::applyStreamingQualityToPlayer)
                }
                if (changed && ::playbackUrlResolver.isInitialized) {
                    refreshQueueForStreamingQuality()
                }
            }
        }
    }

    private fun applyStreamingQualityToPlayer(player: ExoPlayer) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setMaxAudioBitrate(streamingQuality.maxStreamingBitrate)
            .build()
    }

    private suspend fun refreshQueueForStreamingQuality() {
        playbackUrlResolver.invalidateAll()
        val queue = PlaybackStateRepository.activeQueue ?: return
        val currentIndex = queue.currentIndex
        val previousCurrent = queue.songs.getOrNull(currentIndex) ?: return
        val refreshed = playbackQueueEnricher.enrich(queue.songs, currentIndex)
        val refreshedCurrent = refreshed.getOrNull(currentIndex) ?: return

        withContext(Dispatchers.Main) {
            val activeQueue = PlaybackStateRepository.activeQueue ?: return@withContext
            if (
                activeQueue.sourceId != queue.sourceId ||
                activeQueue.currentIndex != currentIndex ||
                activeQueue.songs.getOrNull(currentIndex)?.id != previousCurrent.id
            ) {
                return@withContext
            }

            activeQueue.songs = refreshed
            PlaybackStateRepository.setCurrentSong(refreshedCurrent)
            if (refreshedCurrent.url != previousCurrent.url) {
                val player = exoPlayer
                val playerIndex = player?.currentMediaItemIndex ?: -1
                if (player != null &&
                    playerIndex in 0 until player.mediaItemCount &&
                    player.currentMediaItem?.mediaId == refreshedCurrent.id
                ) {
                    val positionMs = player.currentPosition.coerceAtLeast(0L)
                    val shouldResume = player.playWhenReady
                    player.replaceMediaItem(playerIndex, playbackMediaItemFactory.create(refreshedCurrent))
                    player.seekTo(playerIndex, positionMs)
                    player.prepare()
                    if (shouldResume && requestAudioFocus()) {
                        registerBecomingNoisyReceiver()
                        player.play()
                    }
                }
            }
            updateMediaSessionMetadata()
        }
    }

    private fun toggleShuffle() {
        val queue = PlaybackStateRepository.activeQueue ?: return
        val isShuffle = PlaybackStateRepository.isShuffleEnabledLiveData.value ?: false
        val newShuffleState = !isShuffle

        if (newShuffleState) {
            // Activar Shuffle
            if (originalQueueSongs == null) {
                originalQueueSongs = queue.songs.toList()
            }
            val currentSongId = PlaybackStateRepository.currentSong?.id
            val shuffled = queue.songs.shuffled().toMutableList()

            // Mover canción actual al inicio para no interrumpir
            if (currentSongId != null) {
                 val currentSong = shuffled.find { it.id == currentSongId }
                 if (currentSong != null) {
                     shuffled.remove(currentSong)
                     shuffled.add(0, currentSong)
                 }
            }
            updateSongs(shuffled)
        } else {
            // Desactivar Shuffle (Restaurar)
            val original = originalQueueSongs
            if (original != null) {
                updateSongs(original)
                originalQueueSongs = null
            }
        }
        PlaybackStateRepository.setIsShuffleEnabled(newShuffleState)
        PlaybackStateRepository.publishQueue(shuffleOverride = newShuffleState)
        persistPlaybackState(force = true)
    }

    private fun toggleRepeat() {
        val currentMode = PlaybackStateRepository.repeatModeLiveData.value ?: PlaybackStateRepository.REPEAT_MODE_OFF
        // Ciclo: OFF -> ONE -> ALL -> OFF...
        // O lo que prefiera el usuario. Estándar: OFF -> ALL -> ONE -> OFF (Spotify)
        val newMode = when (currentMode) {
            PlaybackStateRepository.REPEAT_MODE_OFF -> PlaybackStateRepository.REPEAT_MODE_ALL
            PlaybackStateRepository.REPEAT_MODE_ALL -> PlaybackStateRepository.REPEAT_MODE_ONE
            else -> PlaybackStateRepository.REPEAT_MODE_OFF
        }

        PlaybackStateRepository.setRepeatMode(newMode)

        when (newMode) {
            PlaybackStateRepository.REPEAT_MODE_OFF -> {
                exoPlayer?.repeatMode = Player.REPEAT_MODE_OFF
                Log.d("MusicService", "Repeat: OFF")
            }
            PlaybackStateRepository.REPEAT_MODE_ONE -> {
                exoPlayer?.repeatMode = Player.REPEAT_MODE_ONE
                Log.d("MusicService", "Repeat: ONE")
            }
            PlaybackStateRepository.REPEAT_MODE_ALL -> {
                exoPlayer?.repeatMode = Player.REPEAT_MODE_ALL
                Log.d("MusicService", "Repeat: ALL")
            }
        }
    }
    private fun updateMediaSessionMetadata() {
        val song = PlaybackStateRepository.currentSong
        triggerNotificationUpdate()
        Log.d("MusicService", "🔄 Metadata de MediaSession y Notificación actualizada para ${song?.title}")
    }
    private fun updatePredictedDurationAsync() {
        predictedIntelligentDuration = -1L

        serviceScope.launch {
            val currentSong: Song?
            val nextSong: Song?
            withContext(Dispatchers.Main) {
                currentSong = PlaybackStateRepository.currentSong
                // Resolve the next song by ID in the logical queue, NOT by ExoPlayer's filtered
                // index. When songs without URLs are filtered from ExoPlayer's queue, its index
                // does not match the original queue position, causing getNextSong to return the
                // wrong song (often the currently playing song itself).
                val queue = PlaybackStateRepository.activeQueue
                val currentQueueIndex = queue?.songs?.indexOfFirst { it.id == currentSong?.id } ?: -1
                nextSong = if (currentQueueIndex >= 0) {
                    queue?.songs?.getOrNull(currentQueueIndex + 1)
                } else {
                    null
                }
            }

            if (crossfadeMode == CrossfadeMode.INTELLIGENT_EQ &&
                currentSong != null &&
                nextSong != null &&
                currentSong.id != nextSong.id) {

                val duration = transitionManager.predictIntelligentMixDuration(currentSong, nextSong)
                predictedIntelligentDuration = duration
                Log.d("MusicService", "🧠 Duración inteligente cacheada: ${duration}ms (${currentSong.title} -> ${nextSong.title})")
            } else {
                Log.d("MusicService", "🧠 updatePredictedDuration: condiciones no cumplidas (mode=$crossfadeMode, cur=${currentSong?.title}, next=${nextSong?.title})")
            }
        }
    }

    /**
     * Aplica normalización de loudness al ExoPlayer.
     * Fórmula: gain (dB) = TARGET_LUFS - song.loudness
     * Convertido a escala lineal: volume = 10^(gain/20), clamped entre 0.05 y 1.0
     * Si la normalización está desactivada o no hay análisis, vuelve a volumen 1.0.
     */
    /**
     * Calcula el volumen absoluto deseado (de 0.05 a 1.0) para una canción según Normalización de Audio.
     */
    private fun getNormalizedVolume(song: Song?): Float {
        if (!loudnessNormalizationEnabled || song == null) return 1.0f
        val loudness = song.audioAnalysis?.loudnessLufs
        if (loudness == null || loudness == 0.0f) return 1.0f
        val gainDb = TARGET_LOUDNESS_LUFS - loudness
        return Math.pow(10.0, (gainDb / 20.0)).toFloat().coerceIn(0.05f, 1.0f)
    }

    private fun getEffectivePlaybackVolume(song: Song?): Float {
        val focusMultiplier = if (duckedByAudioFocus) AUDIO_DUCK_VOLUME_MULTIPLIER else 1.0f
        return (
            getNormalizedVolume(song) *
                focusMultiplier *
                audioEqualizerController.headroomMultiplier
            ).coerceIn(0.0f, 1.0f)
    }

    /**
     * Aplica el volumen normalizado a ExoPlayer.
     */
    private fun applyLoudnessNormalization(song: Song?) {
        val player = exoPlayer ?: return
        val linearVolume = getEffectivePlaybackVolume(song)
        player.volume = linearVolume
        Log.d("Loudness", "Aplicando volumen efectivo para '${song?.title}': $linearVolume")
    }

    private fun preloadNextSongMetadata(index: Int) {
        val queue = PlaybackStateRepository.activeQueue ?: return
        val nextSong = queue.songs.getOrNull(index) ?: return

        // If URL is already present and looks valid, skip preload
        if (!nextSong.url.isNullOrEmpty()) {
            Log.d("MusicService", "\u26A1 Song at index $index already has URL, skipping preload")
            val player = exoPlayer
            if (player != null) {
                var exists = false
                for (i in 0 until player.mediaItemCount) {
                    if (player.getMediaItemAt(i).mediaId == nextSong.id) {
                        exists = true
                        break
                    }
                }
                if (!exists) {
                    player.addMediaItem(createMediaItem(nextSong))
                    Log.d("MusicService", "➕ Added '${nextSong.title}' natively during early return.")
                }
            }
            return
        }

        Log.d("MusicService", "\u26A1 Preloading song at index $index: ${nextSong.title}")

        serviceScope.launch(Dispatchers.IO) {
            try {
                val songManager = SongManager(applicationContext)
                val fetched = songManager.getSongById(nextSong.id)
                if (fetched != null && !fetched.url.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        val currentQueue = PlaybackStateRepository.activeQueue
                        if (currentQueue != null && index < currentQueue.songs.size) {
                            val mutableSongs = currentQueue.songs.toMutableList()
                            mutableSongs[index] = fetched
                            currentQueue.songs = mutableSongs.toList()

                            // Also update ExoPlayer queue in-place if possible
                            val player = exoPlayer
                            if (player != null) {
                                var exists = false
                                for (i in 0 until player.mediaItemCount) {
                                    if (player.getMediaItemAt(i).mediaId == fetched.id) {
                                        exists = true
                                        break
                                    }
                                }
                                if (!exists) {
                                    val newMediaItem = createMediaItem(fetched)
                                    player.addMediaItem(newMediaItem)
                                    Log.d("MusicService", "➕ Added '${fetched.title}' to ExoPlayer's playlist.")
                                }

                                val currentExoIdx = player.currentMediaItemIndex
                                // If this is the next item in ExoPlayer, insert it
                                if (index == currentQueue.currentIndex + 1) {
                                    transitionManager.preloadNextSong(fetched)
                                }
                            }

                            Log.d("MusicService", "\u2705 Preloaded URL for '${fetched.title}' at index $index")
                        }
                    }
                } else {
                    Log.w("MusicService", "Preload returned null or empty URL for index $index")
                }
            } catch (e: Exception) {
                Log.e("MusicService", "Failed to preload metadata for index $index: ${e.message}")
            }
        }
    }

    private fun handleTransitionTrigger() {
        // Always stop the seekbar FIRST — must never fire twice.
        stopSeekBarUpdates()

        val player = exoPlayer
        if (player == null) {
            Log.e("TransitionTrigger", "❌ exoPlayer is null — aborting")
            return
        }

        val oldSong = PlaybackStateRepository.currentSong
        if (oldSong == null) {
            Log.e("TransitionTrigger", "❌ currentSong is null — aborting")
            return
        }

        reportCurrentSongFinished(wasSkipped = false)

        val queue = PlaybackStateRepository.activeQueue
        if (queue == null) {
            Log.e("TransitionTrigger", "❌ activeQueue is null — aborting")
            return
        }

        // Resolve current position in the LOGICAL queue (not ExoPlayer's filtered index).
        val currentQueueIndex = queue.songs.indexOfFirst { it.id == oldSong.id }
        Log.d("TransitionTrigger", "📍 oldSong=${oldSong.title}, queue size=${queue.songs.size}, currentQueueIndex=$currentQueueIndex, ExoIndex=${player.currentMediaItemIndex}")

        // Prefer queue-based lookup; fall back to ExoPlayer index if song not found in queue.
        val nextSong: Song? = when {
            currentQueueIndex >= 0 -> queue.songs.getOrNull(currentQueueIndex + 1)
            else -> {
                Log.w("TransitionTrigger", "⚠️ Current song not found in queue by ID. Falling back to ExoPlayer index.")
                PlaybackStateRepository.getNextSong(player.currentMediaItemIndex)
            }
        }

        if (nextSong == null) {
            Log.w("TransitionTrigger", "⚠️ No next song found (queueIndex=$currentQueueIndex, queueSize=${queue.songs.size}) — stopping")
            return
        }

        val nextIndex = queue.songs.indexOf(nextSong)
        Log.d("TransitionTrigger", "🎵 nextSong=${nextSong.title}, nextIndex=$nextIndex")

        if (nextIndex == -1) {
            Log.w("TransitionTrigger", "⚠️ Next song not found in queue.songs by reference. Seeking forward.")
            player.seekToNextMediaItem()
            return
        }

        Log.d("MusicService", "🎬 INICIANDO TRANSICIÓN DESDE TRIGGER")
        Log.d("MusicService", "⚙️ Parámetros - Modo: $crossfadeMode, Duración: ${crossfadeDurationMs}ms")

        // PlayMix: use the full transition object (mixMode, fadeCurveType, bandFadeTypes, gapMs)
        // instead of the global crossfadeMode setting — Spotify-style per-transition config.
        val isPlaymixMode = queue.sourceType == QueueSource.PLAYMIX
        val playmixTransition = if (isPlaymixMode) playmixTransitions["${oldSong.id}:${nextSong.id}"] else null

        // Update UI immediately to the next song
        PlaybackStateRepository.setCurrentSong(nextSong)
        loadArtworkForSong(nextSong)
        PlaybackStateRepository.activeQueue?.currentIndex = nextIndex
        PlaybackStateRepository.publishQueue()

        val newDuration = nextSong.audioAnalysis?.durationMs ?: 0
        PlaybackStateRepository.updatePlaybackPosition(0, newDuration)

        audioEqualizerController.suspendForTransition()
        if (playmixTransition != null) {
            Log.d("MusicService", "🎹 PlayMix — using dedicated engine: exit=${playmixTransition.exitPointMs}ms, entry=${playmixTransition.entryPointMs}ms, crossfade=${playmixTransition.crossfadeDurationMs}ms, mixMode=${playmixTransition.mixMode}, curve=${playmixTransition.fadeCurveType}, gap=${playmixTransition.gapMs}ms")
            transitionManager.startPlaymixTransition(
                oldPlayer = player,
                oldSong = oldSong,
                nextSong = nextSong,
                nextSongIndex = nextIndex,
                transition = playmixTransition,
                oldSongTargetVolume = getEffectivePlaybackVolume(oldSong),
                nextSongTargetVolume = getEffectivePlaybackVolume(nextSong)
            )
        } else {
            transitionManager.startTransition(
                oldPlayer = player,
                oldSong = oldSong,
                nextSong = nextSong,
                nextSongIndex = nextIndex,
                crossfadeMode = crossfadeMode,
                isAlbumMode = queue.sourceType == QueueSource.ALBUM,
                automixEnabled = automixEnabled,
                crossfadeDurationMs = crossfadeDurationMs,
                oldSongTargetVolume = getEffectivePlaybackVolume(oldSong),
                nextSongTargetVolume = getEffectivePlaybackVolume(nextSong),
                nextSongStartPositionMs = 0L
            )
        }
    }


    private fun handleTransitionComplete(newPlayer: ExoPlayer, oldPlayer: ExoPlayer) {
        Log.d("MusicService", "✅ Transición completada. Realizando el cambio de reproductor.")

        // Look up the completed song by its mediaId (set on each MediaItem by buildValidMediaItems).
        // This is safe even when empty-URL songs were filtered out of the transition player's
        // queue, because it ignores position entirely and matches by song ID.
        val completedSongId = newPlayer.currentMediaItem?.mediaId
        val activeQueue = PlaybackStateRepository.activeQueue
        val expectedQueueSong = activeQueue?.songs?.getOrNull(activeQueue.currentIndex)
        val completedSong = if (
            expectedQueueSong != null &&
            expectedQueueSong.id == completedSongId
        ) {
            expectedQueueSong
        } else if (!completedSongId.isNullOrEmpty()) {
            activeQueue?.songs?.firstOrNull { it.id == completedSongId }
        } else {
            // Fallback to index if mediaId is somehow unavailable (e.g. album mode preload)
            val newIndex = newPlayer.currentMediaItemIndex
            PlaybackStateRepository.activeQueue?.songs?.getOrNull(newIndex)
        }

        if (completedSong == null) {
            Log.e("MusicService", "ERROR CRÍTICO: Transición completada pero no se encontró la canción en la cola. Deteniendo servicio.")
            stop()
            return
        }

        Log.i("MusicService", "La nueva canción es: ${completedSong.title}")

        oldPlayer.removeListener(playerListener)
        oldPlayer.release()
        this.exoPlayer = newPlayer
        newPlayer.addListener(playerListener)
        newPlayer.addAnalyticsListener(playbackQoeTracker)
        applyStreamingQualityToPlayer(newPlayer)
        audioEqualizerController.resumeAfterTransition(newPlayer.audioSessionId)

        // After a crossfade the new player only holds the transition window, not the full
        // queue. Sync it immediately so that Media3 / Bluetooth / AVRCP see the complete
        // queue and don't report "tried to update with no new data".
        synchronizeExoPlayerQueue()
        playbackQoeTracker.markPreparedPlayerHandoff(
            PlaybackStateRepository.activeQueue?.sourceType?.name ?: "unknown",
            completedSong.id
        )

        currentSongStartTimeMs = System.currentTimeMillis()
        PlaybackStateRepository.streamReported = false

        Log.d("MusicService", "Reproductor principal actualizado a la nueva instancia.")

        // Update the queue's currentIndex to match the completed song
        val newQueueIndex = activeQueue?.currentIndex
            ?.takeIf { activeQueue.songs.getOrNull(it) == completedSong }
            ?: activeQueue?.songs?.indexOf(completedSong)
            ?: -1
        if (newQueueIndex >= 0) {
            PlaybackStateRepository.activeQueue?.currentIndex = newQueueIndex
        }
        PlaybackStateRepository.publishQueue()

        PlaybackStateRepository.setCurrentSong(completedSong)
        PlaybackStateRepository.setIsPlaying(newPlayer.isPlaying)

        loadArtworkForSong(completedSong)
        applyLoudnessNormalization(completedSong)
        updateUiAndSystem()
        persistPlaybackState(force = true)

        if (newPlayer.isPlaying) {
            startSeekBarUpdates()
        }

        updatePredictedDurationAsync()

        val nextSong = PlaybackStateRepository.getNextSong(newQueueIndex.takeIf { it >= 0 } ?: newPlayer.currentMediaItemIndex)
        transitionManager.preloadNextSong(nextSong)
    }

    private fun handleTransitionFailed(oldPlayer: ExoPlayer) {
        // La transición no se hizo o falló, así que simplemente saltamos.
        audioEqualizerController.resumeAfterTransition(oldPlayer.audioSessionId)
        oldPlayer.seekToNextMediaItem()
    }

    private fun logPlayerState(context: String) {
        Log.d("MusicService", "🔍 DIAGNÓSTICO [$context]: " +
                "Repo.isPlaying=${PlaybackStateRepository.isPlaying}, " +
                "DJ.isTransitioning=${transitionManager.isTransitioning}, " +
                "Exo.isPlaying=${exoPlayer?.isPlaying}, " +
                "Repo.Song=${PlaybackStateRepository.currentSong?.title}")
    }
}

/**
 * Maps the fadeCurveType string from PlaymixTransitionDTO to the CrossfadeMode used by TransitionManager.
 */
private fun String.toPlaymixCrossfadeMode(): CrossfadeMode = when (this.lowercase()) {
    "exponential", "logarithmic" -> CrossfadeMode.DIRECT_CUT
    else -> CrossfadeMode.SOFT_MIX // "linear", "custom", unknown → equal-power
}
