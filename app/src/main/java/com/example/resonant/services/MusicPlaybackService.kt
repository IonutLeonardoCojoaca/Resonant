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
import android.os.SystemClock
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
import com.example.resonant.playback.ListenClock
import com.example.resonant.playback.PlaybackCache
import com.example.resonant.playback.PlaymixMixMode
import com.example.resonant.playback.PlaybackQueue
import com.example.resonant.playback.PlaybackQueueEnricher
import com.example.resonant.playback.RadioRepository
import com.example.resonant.playback.RadioBatch
import com.example.resonant.playback.StreamAttribution
import com.example.resonant.playback.PlaySourceMapper
import com.example.resonant.playback.FlowSessionSnapshot
import com.example.resonant.playback.flow.FlowQueuePolicy
import com.example.resonant.playback.PlaybackMediaItemFactory
import com.example.resonant.playback.PlaybackMediaSourceFactory
import com.example.resonant.playback.PlaybackStateRepository
import com.example.resonant.playback.PlaybackStateStore
import com.example.resonant.playback.PlaybackUrlResolver
import com.example.resonant.playback.TransitionPlanner
import com.example.resonant.playback.TransitionTiming
import com.example.resonant.playback.RenderedTransition
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
import com.example.resonant.data.network.TransitionSuggestRequest
import com.example.resonant.data.network.MixDocumentDTO
import com.example.resonant.data.network.PlaybackConnectCommandDTO
import com.example.resonant.data.network.PlaybackConnectQueueItemDTO
import com.example.resonant.data.network.PlaybackConnectStateDTO
import com.example.resonant.data.network.SongPlaybackDTO
import com.example.resonant.managers.SettingsManager
import com.example.resonant.managers.FlowPreferencesManager
import com.example.resonant.managers.CapabilitiesManager
import com.example.resonant.managers.TransitionManager
import com.example.resonant.managers.UserManager
import com.example.resonant.managers.StreamReporter
import kotlinx.coroutines.CancellationException
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.ApiClient
import com.example.resonant.managers.SongManager
import com.example.resonant.playback.withPlaybackInfo
import com.example.resonant.playback.ResonantLibrarySessionCallback
import com.example.resonant.playback.PlaybackConnectRepository
import com.example.resonant.playback.ConnectConflictEvent
import com.example.resonant.playback.anchoredPositionMs
import com.example.resonant.data.network.V2EndpointAvailability
import com.example.resonant.streak.StreakEventBus
import com.example.resonant.ui.activities.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import androidx.media3.common.PlaybackParameters
import com.example.resonant.playback.LivePlaybackAdapter
import com.example.resonant.live.LiveCommand
import com.example.resonant.live.LiveControlPolicy
import com.example.resonant.live.LiveCursor
import com.example.resonant.live.LiveManager
import com.example.resonant.live.LiveMirrorChange
import com.example.resonant.live.LivePlayerSnapshot
import com.example.resonant.live.LiveRoute
import com.example.resonant.live.LiveSessionState
import com.example.resonant.live.LiveSyncLog
import com.example.resonant.live.LiveDjDuckRamp
import com.example.resonant.live.LiveDjMode
import com.example.resonant.playback.LiveDjController
import com.example.resonant.playback.LiveDjPlayer
import com.example.resonant.live.LiveTickAction
import com.example.resonant.playback.UnisoundAwarePlayer
import com.example.resonant.playback.UnisoundPlaybackAdapter
import com.example.resonant.playback.UnisoundPlaybackBridge
import com.example.resonant.unisound.UnisoundControl
import com.example.resonant.unisound.UnisoundControlOrigin
import com.example.resonant.unisound.UnisoundDriftAction
import com.example.resonant.unisound.UnisoundDriftPolicy
import com.example.resonant.unisound.UnisoundDriftReport
import com.example.resonant.unisound.UnisoundManager
import com.example.resonant.unisound.UnisoundMirrorChange
import com.example.resonant.unisound.UnisoundMirrorPlan
import com.example.resonant.unisound.UnisoundRoomState
import com.google.firebase.analytics.FirebaseAnalytics

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
        /** `requestId` de la superficie de recomendación desde la que se lanza [ACTION_PLAY] (opcional). */
        const val EXTRA_RECOMMENDATION_REQUEST_ID = "com.resonant.EXTRA_RECOMMENDATION_REQUEST_ID"
        /** `playSource` canónico (ver [PlaySourceMapper]) para las entradas de [ACTION_PLAY] (opcional). */
        const val EXTRA_PLAY_SOURCE_CANONICAL = "com.resonant.EXTRA_PLAY_SOURCE_CANONICAL"
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
        private const val PLAYMIX_REFRESH_MIN_INTERVAL_MS = 60_000L
        /** La transición se arma unos segundos antes de `a.startMs` del plan para preparar B. */
        private const val TRANSITION_LEAD_MS = 3_000L
        private const val PLAYBACK_STATE_SAVE_INTERVAL_MS = 5_000L
        // Shortened from 10s: this is also the maximum window during which the
        // device that just lost playback could still be audible alongside the
        // new active device, so keeping it tight matters for Resonant Connect.
        private const val CONNECT_FALLBACK_INTERVAL_MS = 8_000L

        /** Rolling window size of recentSongIds we send to the server. */
        private const val RADIO_RECENT_WINDOW_SIZE = 30
        private const val FLOW_TAG = "Flow"
        private const val CONNECT_MAX_QUEUE_ITEMS = 500
        /** Orders are anchored ~300 ms ahead; never wait longer than this for an anchor. */
        private const val UNISOUND_MAX_ANCHOR_WAIT_MS = 2_000L
        private const val UNISOUND_LEAVE_TO_PLAY_MESSAGE = "Sal de la sala para reproducir tu música"
        private const val LIVE_TAG = "Live"
        private const val LIVE_DJ_TAG = "LiveDj"
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

    /** Nivel de descubrimiento elegido (0-2); solo se envía con la capacidad `flowV2`. */
    @Volatile private var flowDiscoveryLevel: Int = 1

    /**
     * Sube cada vez que el contexto de Flow deja de valer (cola nueva, sala, interruptor
     * apagado): una respuesta pedida con una generación anterior se descarta.
     */
    private val flowGeneration = AtomicLong(0L)

    /** La cola terminó esperando un lote de Flow: al llegar se avanza a él (solo Main). */
    private var flowAdvancePending = false
    private val flowHandler = Handler(Looper.getMainLooper())
    private val flowAdvanceTimeout = Runnable {
        Log.d(FLOW_TAG, "Sin lote de Flow a tiempo: la cola termina.")
        flowAdvancePending = false
    }
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
    // Aria DJ en Live: la música baja con este multiplicador mientras suena la voz (1.0 sin intro).
    // Es independiente de duckedByAudioFocus para que un evento no restaure el volumen del otro.
    private var djDuckMultiplier: Float = 1.0f
    private var djDuckJob: kotlinx.coroutines.Job? = null
    @Volatile private var liveDjMode: LiveDjMode = LiveDjMode.VOICE
    private lateinit var liveDj: LiveDjController
    private var noisyReceiverRegistered: Boolean = false
    private var automixEnabled: Boolean = false
    private var crossfadeDurationMs: Long = 5000L
    private var crossfadeMode: CrossfadeMode = CrossfadeMode.SOFT_MIX
    private var isSeeking: Boolean = false
    private var lastManualChangeTimestamp: Long = 0L
    private val pendingDeletionSongIds = mutableSetOf<String>()

    /**
     * Canción cuya transición automática ya falló en esta reproducción. Evita que el
     * seekbar vuelva a disparar la misma transición en bucle; se limpia al cambiar de canción.
     */
    private var transitionSuppressedForSongId: String? = null

    // Loudness normalization
    private var loudnessNormalizationEnabled: Boolean = true
    private var streamingQuality: AudioQuality = AudioQuality.AUTO
    private val TARGET_LOUDNESS_LUFS = -14.0f  // Referencia estándar de streaming

    private val listenClock = ListenClock()
    // Reasignarlo marca el inicio de una canción y reinicia el descuento de pausas.
    private var currentSongStartTimeMs: Long = 0L
        set(value) {
            field = value
            listenClock.restart(value, PlaybackStateRepository.isPlaying)
        }
    private var lastPlaybackStateSaveAtMs: Long = 0L
    private var clearPlaybackStateOnDestroy: Boolean = false
    private var originalQueueSongs: List<Song>? = null

    // Guards loadArtworkForSong() against fast skips: bumped on every call,
    // on Main (the only thread that ever calls it). A load whose generation
    // no longer matches artworkLoadGeneration by the time it would apply its
    // result is stale and is dropped, even if it finishes after a newer one.
    private var artworkLoadGeneration = 0L
    private var currentArtworkTarget: CustomTarget<Bitmap>? = null

    // PlayMix crossfade — map key: "fromSongId:toSongId"
    private var playmixTransitions: Map<String, PlaymixTransitionDTO> = emptyMap()
    private var playmixSongIdMap: Map<String, String> = emptyMap() // songId -> playmixSongId
    private var pendingStartPositionMs: Long = 0L
    private val playbackLoadGeneration = AtomicLong(0L)
    private val connectStateRevision = AtomicLong(0L)
    private val connectSyncInFlight = AtomicBoolean(false)
    private val connectCommandMutex = Mutex()
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

    // Unisound: while in a room the queue mirrors the server and controls go to the hub.
    private lateinit var unisoundManager: UnisoundManager
    private lateinit var unisoundAdapter: UnisoundPlaybackAdapter
    private val unisoundHandler = Handler(Looper.getMainLooper())
    private var unisoundApplyRunnable: Runnable? = null
    private val isUnisoundMode: Boolean
        get() = PlaybackStateRepository.activeQueue?.sourceType == QueueSource.UNISOUND

    // Live: the queue is the broadcast schedule; the listener can only enter and leave.
    private lateinit var liveManager: LiveManager
    private lateinit var liveAdapter: LivePlaybackAdapter
    private val isLiveMode: Boolean
        get() = PlaybackStateRepository.activeQueue?.sourceType == QueueSource.LIVE

    /** The queue belongs to the server (a room or a broadcast): no skips, crossfade or Connect. */
    private val isSharedMode: Boolean
        get() = isUnisoundMode || isLiveMode
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
                    when {
                        inUnisoundRoom() -> unisoundManager.setLocalHold(false)
                        inLive() -> liveManager.rejoin()
                        else -> exoPlayer?.play()
                    }
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d("AudioFocus", "Foco perdido permanentemente")
                resumeOnAudioFocusGain = false
                duckedByAudioFocus = false
                pauseForAudioFocus(shouldResume = false)
                abandonAudioFocus()
                holdSharedLocally()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d("AudioFocus", "Foco perdido temporalmente")
                duckedByAudioFocus = false
                pauseForAudioFocus(shouldResume = exoPlayer?.isPlaying == true)
                holdSharedLocally()
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
                if (inSharedMode()) holdSharedLocally() else pause()
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

            // Tras un cambio de canción el player puede seguir unos instantes en el item anterior
            // (posición y duración viejas). Comparar esa posición con el disparo de la canción nueva
            // lanzaba una transición al instante y se saltaba la canción.
            val currentSongId = PlaybackStateRepository.currentSong?.id
            val playerSongId = player.currentMediaItem?.mediaId
            if (currentSongId != null && playerSongId != null && playerSongId != currentSongId) {
                seekBarHandler.postDelayed(this, 200)
                return
            }

            val triggerPosition = calculateTriggerPosition()

            val transitionSuppressed = transitionSuppressedForSongId != null &&
                transitionSuppressedForSongId == PlaybackStateRepository.currentSong?.id
            if (triggerPosition > 0 && position >= triggerPosition && !transitionSuppressed) {
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
            playbackConnectRepository.ensureRealtimeConnected()
            if (!playbackConnectRepository.realtimeConnected.value) {
                syncPlaybackConnect()
            }
            connectHandler.postDelayed(this, CONNECT_FALLBACK_INTERVAL_MS)
        }
    }

    private fun startConnectHeartbeats() {
        connectHandler.removeCallbacks(connectHeartbeatRunnable)
        playbackConnectRepository.startRealtime()
        connectHandler.post(connectHeartbeatRunnable)
    }

    private fun stopConnectHeartbeats() {
        connectHandler.removeCallbacks(connectHeartbeatRunnable)
        if (::playbackConnectRepository.isInitialized) {
            playbackConnectRepository.stopRealtime()
        }
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
        // In a room or a broadcast the server decides what plays next.
        if (isSharedMode) return
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
     * La cola ha terminado: se pide un lote de Flow (o se espera al que ya está en vuelo)
     * y se avanza a él en cuanto llega. Si no llega en [FlowQueuePolicy.QUEUE_END_TIMEOUT_MS]
     * la cola se queda terminada y usable.
     */
    @androidx.annotation.MainThread
    private fun tryStartRadioAtQueueEnd(queue: PlaybackQueue) {
        if (!isFlowEligible(queue)) return
        flowAdvancePending = true
        flowHandler.removeCallbacks(flowAdvanceTimeout)
        flowHandler.postDelayed(flowAdvanceTimeout, FlowQueuePolicy.QUEUE_END_TIMEOUT_MS)
        requestFlowBatch(queue)
    }
    override fun playPrevious() {
        if (isSharedMode) return
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
        transitionSuppressedForSongId = null
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
        // onMediaItemTransition no lo ve como cambio porque el índice ya se actualizó arriba.
        recordRecentSong(targetSong.id)
        maybePrefetchRadio()
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
            resetRadioSession()
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
                if (!enabled) {
                    // Apagar Flow surte efecto ya: se descarta el lote en vuelo.
                    flowGeneration.incrementAndGet()
                    withContext(Dispatchers.Main) { cancelFlowAdvance() }
                }
            }
        }
        serviceScope.launch {
            settingsManager.flowDiscoveryLevelFlow.collect { level -> flowDiscoveryLevel = level }
        }
        serviceScope.launch {
            settingsManager.liveDjModeFlow.collect { mode -> liveDjMode = mode }
        }
        serviceScope.launch { FlowPreferencesManager.syncOnStart(applicationContext) }
        // Escuchas que quedaron pendientes (sin red, proceso muerto…): se reenvían al arrancar.
        serviceScope.launch { runCatching { StreamReporter.flush(applicationContext) } }
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
                        if (inSharedMode()) holdSharedLocally() else pause()
                    }
                }
            }
        }

        // Commands now arrive immediately over SignalR. Durable command IDs
        // and acknowledgements still make reconnect/redelivery safe.
        serviceScope.launch {
            playbackConnectRepository.commandEvents.collect { command ->
                processPlaybackConnectCommands(listOf(command))
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
            onTransitionAudible = ::handleTransitionAudible,
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
        unisoundManager = UnisoundManager.get(applicationContext)
        unisoundAdapter = UnisoundPlaybackAdapter(
            manager = unisoundManager,
            host = unisoundHost,
            reportDrift = ::reportUnisoundDrift
        )
        liveManager = LiveManager.get(applicationContext)
        liveAdapter = LivePlaybackAdapter(
            state = liveManager.state,
            clock = liveManager.clock,
            host = liveHost,
            onScheduleEnded = liveManager::onScheduleEnded,
            reportDrift = ::reportLiveDrift,
            log = { Log.d(LIVE_TAG, it) },
            syncLog = if (BuildConfig.DEBUG) { line -> Log.d(LiveSyncLog.TAG, line) } else null
        )
        liveDj = LiveDjController(
            speaker = LiveDjPlayer(applicationContext),
            duck = ::setDjDuck,
            mode = { liveDjMode },
            featureOn = CapabilitiesManager::isAriaDjEnabled,
            scope = CoroutineScope(serviceScope.coroutineContext + Dispatchers.Main),
            log = { Log.d(LIVE_DJ_TAG, it) },
            onPresented = { mode -> logLiveDjEvent("live_dj_presented", "mode", mode) },
            onInterrupted = { reason -> logLiveDjEvent("live_dj_interrupted", "reason", reason) }
        )
        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            sessionPlayerFor(requireNotNull(exoPlayer)),
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
        // Playback in this app is driven entirely through Intent actions
        // (ACTION_PLAY, ACTION_PAUSE, ...), never through a bound
        // MediaController. Media3's automatic media notification / foreground
        // promotion is only wired up once a session is registered via
        // addSession() (normally triggered by onGetSession() the first time a
        // MediaController connects) — since that never happens here, we must
        // register it explicitly or the service never becomes a real
        // foreground service and gets killed by the background execution
        // time limit while playing off-screen.
        addSession(requireNotNull(mediaLibrarySession))

        startConnectHeartbeats()
        unisoundAdapter.start(serviceScope)
        liveAdapter.start(serviceScope)
        serviceScope.launch { unisoundManager.restore() }
        com.example.resonant.widget.WidgetUpdateCoordinator.attach(this)
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // El usuario ha pedido explícitamente que la música se detenga al quitar la app
        // de la lista de tareas recientes.
        exoPlayer?.pause()
        stopSelf()
    }
    override fun onDestroy() {
        // Before PlaybackStateRepository.reset(): the widget stops observing and is repainted paused.
        com.example.resonant.widget.WidgetUpdateCoordinator.detach()
        if (::playbackStateStore.isInitialized) {
            if (clearPlaybackStateOnDestroy) {
                playbackStateStore.clear()
            } else {
                persistPlaybackState(force = true, synchronous = true)
            }
        }
        stopSeekBarUpdates()
        stopConnectHeartbeats()
        if (::unisoundAdapter.isInitialized) unisoundAdapter.stop()
        if (::liveAdapter.isInitialized) liveAdapter.stop()
        if (::liveDj.isInitialized) liveDj.release()
        unisoundApplyRunnable?.let(unisoundHandler::removeCallbacks)

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
        val flowSongIds = queue.flowSongIds()
        queue.songs = newSongs.toMutableList()
        queue.resetEntriesForSongs()
        queue.remarkFlowSongs(flowSongIds)

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

                listenClock.onPlayingChanged(isPlayingUpdate, System.currentTimeMillis())
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
                        // In Live the schedule moved on: the previous song was listened to, not skipped.
                        if (isLiveMode) reportCurrentSongFinished(wasSkipped = false)

                        expectedSeekPositionMs = -1L
                        progressiveFallbackSongId = null
                        retryCount = 0
                        transitionSuppressedForSongId = null

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
                        if (isLiveMode) liveManager.onSongChanged()
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
                                updateSongs(keepingFlowBlock(newSongs))
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
                    if (isUnisoundMode) {
                        Log.d("PlayerListener", "Fin de la cola espejo: se espera al estado de la sala.")
                        return
                    }
                    if (isLiveMode) {
                        // The schedule decides what comes next; the Live loop takes it from here.
                        reportCurrentSongFinished(wasSkipped = false)
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
                if (isLiveMode) {
                    // Never retried: the schedule moves on, the Live loop jumps to the next slot on time.
                    markCurrentLiveSlotFailed()
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
                
                // Solo aplicamos la normalización de volumen al vuelo si la canción acaba
                // de empezar. Si el volumen cambia a mitad de canción (por ejemplo, al
                // recibir los metadatos de audio retrasados de red), es muy molesto para el usuario.
                if ((exoPlayer?.currentPosition ?: 0L) < 2000L) {
                    applyLoudnessNormalization(enrichedSong)
                }
                
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
        // Everyone in a room or a broadcast must hear the same cut: no crossfade or transitions.
        if (isSharedMode) return -1L
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
            val transition = PlaymixMixMode.advancedOrNull(playmixTransitions["${song.id}:${nextSong.id}"])
            if (transition != null) {
                // Resonant Studio: con clip renderizado se dispara antes para precargar clip y B.
                val nowMs = System.currentTimeMillis()
                RenderedTransition.planFor(transition, nowMs)?.let { plan ->
                    return RenderedTransition.triggerPositionMs(plan)
                }
                if (RenderedTransition.needsRefresh(transition, nowMs)) refreshPlaymixTransitions()

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
            // Blend automático (o pareja sin transición): el motor inteligente decide la mezcla.
            val smartPlan = transitionPlanFor(song, nextSong, playmixSmart = true) ?: return -1L
            return (smartPlan.doc.a.startMs - TRANSITION_LEAD_MS).coerceAtLeast(1L)
        }

        val player = exoPlayer
        val exoDurationMs = if (player != null && player.duration > 0) player.duration else -1L
        val analysis = song.audioAnalysis
        val analysisDurationMs = (analysis?.durationMs ?: 0).toLong()
        if (TransitionTiming.effectiveDurationMs(exoDurationMs, analysisDurationMs) <= 0) return -1L

        if (isAlbumMode) return -1L  // album non-automix: handled elsewhere

        // Suave, corte e inteligente (y recetas): el plan dice dónde empieza la mezcla sobre A.
        val plan = transitionPlanFor(song, nextSong) ?: return -1L
        return (plan.doc.a.startMs - TRANSITION_LEAD_MS).coerceAtLeast(1L) // 0 = sin disparo para el seekbar
    }



    private fun loadArtworkForSong(song: Song) {
        // Invalidate whatever load is in flight before doing anything else,
        // so a fast skip can never let an older request's callback win —
        // even one that hasn't been dispatched to Glide yet (see the
        // generation check right after the coroutine hop below).
        val generation = ++artworkLoadGeneration
        currentArtworkTarget?.let { previousTarget ->
            Glide.with(this@MusicPlaybackService).clear(previousTarget)
        }
        currentArtworkTarget = null

        val imageUrl = song.coverUrl
        if (imageUrl.isNullOrBlank()) {
            // Si no hay URL, limpiamos el bitmap en el repositorio
            PlaybackStateRepository.setCurrentSongBitmap(null)
            return
        }

        serviceScope.launch(Dispatchers.Main) {
            // A newer loadArtworkForSong() call may have already superseded
            // this one before this coroutine even got to run.
            if (generation != artworkLoadGeneration) return@launch

            val target = object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    // The load can finish after a later skip already moved
                    // on; only the most recent generation is allowed to set
                    // the bitmap.
                    if (generation != artworkLoadGeneration) return
                    Log.d("ArtworkLoader", "✅ Imagen cargada para ${song.title}")
                    PlaybackStateRepository.setCurrentSongBitmap(resource)
                    updateMediaSessionMetadata()
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    if (generation != artworkLoadGeneration) return
                    Log.w("ArtworkLoader", "⚠️ Falló la carga de imagen para ${song.title}")
                    PlaybackStateRepository.setCurrentSongBitmap(null)
                    updateMediaSessionMetadata()
                }

                override fun onLoadCleared(placeholder: Drawable?) {}
            }
            currentArtworkTarget = target
            Glide.with(this@MusicPlaybackService)
                .asBitmap()
                .load(imageUrl)
                .into(target)
        }
    }
    // region Unisound room mode
    //
    // The room state lives in UnisoundManager; UnisoundPlaybackAdapter tells this service what
    // changed. Everything below runs on Main and only acts while the queue is the room mirror.

    private val unisoundHost = object : UnisoundPlaybackAdapter.Host {
        override fun applyUnisoundState(state: UnisoundRoomState?, change: UnisoundMirrorChange) =
            this@MusicPlaybackService.applyUnisoundState(state, change)

        override fun unisoundPlayerSample(): UnisoundPlaybackAdapter.PlayerSample? {
            val player = exoPlayer ?: return null
            if (!isUnisoundMode || player.mediaItemCount == 0) return null
            val nextIndex = player.currentMediaItemIndex + 1
            val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
            return UnisoundPlaybackAdapter.PlayerSample(
                positionMs = player.currentPosition,
                mediaId = player.currentMediaItem?.mediaId,
                nextMediaId = if (nextIndex < player.mediaItemCount) player.getMediaItemAt(nextIndex).mediaId else null,
                remainingMs = duration?.let { it - player.currentPosition } ?: -1L,
                buffering = player.playbackState == Player.STATE_BUFFERING,
                playing = player.isPlaying
            )
        }

        override fun applyUnisoundDrift(action: UnisoundDriftAction, state: UnisoundRoomState) =
            this@MusicPlaybackService.applyUnisoundDrift(action, state)
    }

    private fun inUnisoundRoom(): Boolean =
        ::unisoundManager.isInitialized && unisoundManager.state.value != null

    /** Pause only this device (focus loss, noisy output, Connect); the room keeps going. */
    private fun holdUnisoundLocally() {
        if (inUnisoundRoom()) unisoundManager.setLocalHold(true)
    }

    private fun shouldSoundUnisound(state: UnisoundRoomState): Boolean =
        state.isPlaying && !state.localHold && state.current != null

    /** Controls coming from the MediaSession (notification, lock screen, headset, Bluetooth). */
    private fun interceptUnisoundControl(control: UnisoundControl, positionMs: Long?): Boolean {
        if (!inUnisoundRoom()) return false
        unisoundManager.control(control, positionMs ?: exoPlayer?.currentPosition, UnisoundControlOrigin.SYSTEM)
        return true
    }

    /** In-app controls and Aria arrive as Intents; in a room they become room orders. */
    private fun interceptUnisoundAction(action: String, intent: Intent): Boolean {
        if (!inUnisoundRoom()) return false
        val position = exoPlayer?.currentPosition
        when (action) {
            ACTION_PAUSE -> unisoundManager.control(UnisoundControl.PAUSE, position, UnisoundControlOrigin.APP)
            ACTION_RESUME -> unisoundManager.control(UnisoundControl.PLAY, position, UnisoundControlOrigin.APP)
            ACTION_NEXT -> unisoundManager.control(UnisoundControl.NEXT, position, UnisoundControlOrigin.APP)
            ACTION_PREVIOUS -> unisoundManager.control(UnisoundControl.PREVIOUS, position, UnisoundControlOrigin.APP)
            ACTION_SEEK_TO -> unisoundManager.control(
                UnisoundControl.SEEK,
                intent.getIntExtra(EXTRA_SEEK_POSITION, 0).toLong(),
                UnisoundControlOrigin.APP
            )
            ACTION_PLAY -> {
                // Own music while in a room: the visible screen asks "Saldrás de la sala".
                if (!UnisoundPlaybackBridge.offerConflict(Intent(intent))) {
                    unisoundManager.notify(UNISOUND_LEAVE_TO_PLAY_MESSAGE)
                }
            }
            ACTION_TOGGLE_SHUFFLE, ACTION_TOGGLE_REPEAT, ACTION_UPDATE_QUEUE,
            ACTION_PLAY_CONNECT_CONFIRMED -> Unit
            else -> return false
        }
        return true
    }

    private fun applyUnisoundState(state: UnisoundRoomState?, change: UnisoundMirrorChange) {
        when (change) {
            UnisoundMirrorChange.NONE -> return
            UnisoundMirrorChange.EXIT -> {
                exitUnisoundMode()
                return
            }
            UnisoundMirrorChange.REBUILD -> rebuildUnisoundQueue(requireNotNull(state))
            UnisoundMirrorChange.QUEUE -> replaceUnisoundPending(requireNotNull(state))
            UnisoundMirrorChange.STATE -> Unit
        }
        applyUnisoundPlayback(requireNotNull(state))
    }

    private fun enterUnisoundModeIfNeeded() {
        if (isUnisoundMode) return
        reportCurrentSongFinished(wasSkipped = true)
        cancelCurrentTransitionAndRestoreEffects()
        resetRadioSession()
        playmixTransitions = emptyMap()
        playmixSongIdMap = emptyMap()
        originalQueueSongs = null
        exoPlayer?.repeatMode = Player.REPEAT_MODE_OFF
    }

    private fun rebuildUnisoundQueue(state: UnisoundRoomState) {
        enterUnisoundModeIfNeeded()
        val player = exoPlayer ?: return
        val songs = UnisoundMirrorPlan.songsOf(state)
        val current = songs.firstOrNull()
        if (current != null && isUnisoundMode && playerIsOnOrEnteringSong(player, current.id)) {
            // ExoPlayer already moved (or is about to move) to this song gaplessly: keep it.
            replaceUnisoundPending(state)
            PlaybackStateRepository.setCurrentSong(current)
            loadArtworkForSong(current)
            updateUiAndSystem()
            updateMediaSessionMetadata()
            return
        }

        reportCurrentSongFinished(wasSkipped = true)
        PlaybackStateRepository.activeQueue = PlaybackQueue(
            sourceId = state.sessionId,
            sourceType = QueueSource.UNISOUND,
            songs = songs,
            currentIndex = 0,
            entryIds = UnisoundMirrorPlan.entryIdsOf(state)
        )
        if (current == null) {
            player.stop()
            player.clearMediaItems()
            PlaybackStateRepository.setCurrentSong(null)
            PlaybackStateRepository.setIsPlaying(false)
            stopSeekBarUpdates()
            updateUiAndSystem()
            return
        }
        playSongFromQueue(playWhenReady = shouldSoundUnisound(state))
    }

    private fun playerIsOnOrEnteringSong(player: ExoPlayer, songId: String): Boolean {
        if (player.mediaItemCount == 0) return false
        if (player.currentMediaItem?.mediaId == songId) return true
        val nextIndex = player.currentMediaItemIndex + 1
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: return false
        return nextIndex < player.mediaItemCount &&
            player.getMediaItemAt(nextIndex).mediaId == songId &&
            duration - player.currentPosition <= UnisoundMirrorPlan.SONG_BOUNDARY_GRACE_MS
    }

    /** Same current song: swap only the items after it, so ExoPlayer keeps buffering. */
    private fun replaceUnisoundPending(state: UnisoundRoomState) {
        val player = exoPlayer ?: return
        val queue = PlaybackStateRepository.activeQueue?.takeIf { isUnisoundMode }
        val songs = UnisoundMirrorPlan.songsOf(state)
        val currentId = songs.firstOrNull()?.id ?: return
        if (queue == null || !playerIsOnOrEnteringSong(player, currentId)) {
            unisoundAdapter.forceRebuild()
            return
        }
        queue.songs = songs
        queue.entryIds = UnisoundMirrorPlan.entryIdsOf(state)
        queue.currentIndex = 0

        val playingIndex = player.currentMediaItemIndex
        val currentIndex = if (player.currentMediaItem?.mediaId == currentId) playingIndex else playingIndex + 1
        if (currentIndex + 1 < player.mediaItemCount) {
            player.removeMediaItems(currentIndex + 1, player.mediaItemCount)
        }
        player.addMediaItems(songs.drop(1).map(playbackMediaItemFactory::create))
        if (currentIndex == playingIndex && playingIndex > 0) player.removeMediaItems(0, playingIndex)
        PlaybackStateRepository.publishQueue()
    }

    /** Play/pause/seek exactly at the order's anchor, converted to this device's clock. */
    private fun applyUnisoundPlayback(state: UnisoundRoomState) {
        unisoundApplyRunnable?.let(unisoundHandler::removeCallbacks)
        val runnable = Runnable { applyUnisoundPlaybackNow(state) }
        unisoundApplyRunnable = runnable
        val delayMs = unisoundAdapter.clock.toDeviceTime(state.playback.anchorServerTimeUtcMs) -
            System.currentTimeMillis()
        unisoundHandler.postDelayed(runnable, delayMs.coerceIn(0L, UNISOUND_MAX_ANCHOR_WAIT_MS))
    }

    private fun applyUnisoundPlaybackNow(state: UnisoundRoomState) {
        val player = exoPlayer ?: return
        val current = state.current ?: return
        // Still loading the song: playSongFromQueue starts it and the drift loop aligns it.
        if (!isUnisoundMode || player.currentMediaItem?.mediaId != current.songId) return

        if (shouldSoundUnisound(state)) {
            val expected = state.expectedPositionMs(unisoundAdapter.clock.now())
            if (kotlin.math.abs(player.currentPosition - expected) > UnisoundDriftPolicy.TOLERANCE_MS) {
                player.seekTo(expected)
            }
            if (!player.isPlaying && requestAudioFocus()) {
                registerBecomingNoisyReceiver()
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                if (currentSongStartTimeMs <= 0L) currentSongStartTimeMs = System.currentTimeMillis()
                player.play()
            }
            return
        }

        player.playbackParameters = PlaybackParameters.DEFAULT
        if (player.playWhenReady) player.pause()
        if (!state.isPlaying) {
            player.seekTo(state.playback.positionMsAtAnchor.coerceIn(0L, current.durationMs.coerceAtLeast(0L)))
        }
        // Same reason as pause(): keep the observable state authoritative.
        PlaybackStateRepository.setIsPlaying(false)
        stopSeekBarUpdates()
        updateUiAndSystem()
    }

    private fun applyUnisoundDrift(action: UnisoundDriftAction, state: UnisoundRoomState) {
        val player = exoPlayer ?: return
        when (action) {
            is UnisoundDriftAction.Seek -> {
                val songId = state.current?.songId
                val index = (0 until player.mediaItemCount).firstOrNull {
                    player.getMediaItemAt(it).mediaId == songId
                }
                if (index == null) {
                    unisoundAdapter.forceRebuild()
                    return
                }
                player.playbackParameters = PlaybackParameters.DEFAULT
                if (index == player.currentMediaItemIndex) player.seekTo(action.positionMs)
                else player.seekTo(index, action.positionMs)
            }
            is UnisoundDriftAction.SetSpeed -> player.playbackParameters = PlaybackParameters(action.speed)
            UnisoundDriftAction.None -> Unit
        }
    }

    /** Back to normal playback: room audio stops and personal settings apply again. */
    private fun exitUnisoundMode() {
        unisoundApplyRunnable?.let(unisoundHandler::removeCallbacks)
        val player = exoPlayer
        if (!isLiveMode) restorePersonalPlayerSettings()
        // The user may already be playing their own music (left to play an album).
        if (!isUnisoundMode) return
        reportCurrentSongFinished(wasSkipped = true)
        player?.stop()
        player?.clearMediaItems()
        stopSeekBarUpdates()
        unregisterBecomingNoisyReceiver()
        abandonAudioFocus()
        PlaybackStateRepository.reset()
        updateUiAndSystem()
    }

    private fun reportUnisoundDrift(report: UnisoundDriftReport) {
        FirebaseAnalytics.getInstance(applicationContext).logEvent(
            "unisound_drift",
            Bundle().apply {
                putLong("p50_ms", report.p50Ms)
                putLong("max_ms", report.maxMs)
                putLong("seeks", report.seeks.toLong())
                putLong("samples", report.samples.toLong())
            }
        )
    }

    // endregion

    /** Normal speed and the user's own repeat mode, after a room or a broadcast. */
    private fun restorePersonalPlayerSettings() {
        val player = exoPlayer ?: return
        player.playbackParameters = PlaybackParameters.DEFAULT
        player.repeatMode = when (PlaybackStateRepository.repeatModeLiveData.value) {
            PlaybackStateRepository.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ONE
            PlaybackStateRepository.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // region Live broadcast mode
    //
    // The schedule lives in LiveManager; LivePlaybackAdapter tells this service what changed and
    // what the schedule asks for on every tick. Everything below runs on Main and only acts while
    // the queue is the broadcast.

    private val liveHost = object : LivePlaybackAdapter.Host {
        override fun applyLiveState(state: LiveSessionState?, change: LiveMirrorChange) =
            this@MusicPlaybackService.applyLiveState(state, change)

        override fun livePlayerSnapshot(): LivePlayerSnapshot? {
            val player = exoPlayer ?: return null
            if (!isLiveMode || player.mediaItemCount == 0) return null
            val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
            return LivePlayerSnapshot(
                index = player.currentMediaItemIndex,
                positionMs = player.currentPosition,
                remainingMs = duration?.let { it - player.currentPosition } ?: -1L,
                isPlaying = player.isPlaying,
                isBuffering = player.playWhenReady && player.playbackState == Player.STATE_BUFFERING
            )
        }

        override fun liveQueuedKeys(): List<String> =
            PlaybackStateRepository.activeQueue?.takeIf { isLiveMode }?.entryIds.orEmpty()

        override fun applyLiveTick(action: LiveTickAction) =
            this@MusicPlaybackService.applyLiveTick(action)

        override fun onLiveCursor(cursor: LiveCursor) {
            if (!::liveDj.isInitialized || !isLiveMode) return
            when (cursor) {
                is LiveCursor.Playing -> liveDj.onTick(cursor.slot, cursor.positionMs)
                else -> liveDj.onTick(null, 0L)
            }
        }
    }

    private fun inLive(): Boolean = ::liveManager.isInitialized && liveManager.state.value != null

    private fun inSharedMode(): Boolean = inUnisoundRoom() || inLive()

    /** Pause only this device (focus loss, noisy output, Connect); the room or the broadcast goes on. */
    private fun holdSharedLocally() {
        if (::liveDj.isInitialized) liveDj.stop("hold")
        holdUnisoundLocally()
        if (inLive()) liveManager.hold()
    }

    private fun sessionPlayerFor(player: Player): UnisoundAwarePlayer =
        UnisoundAwarePlayer(player, restrictControls = { isLiveMode }, intercept = ::interceptSystemControl)

    /** Makes the session read the available commands again: the notification drops (or regains) skip and seek. */
    private fun refreshSessionPlayer() {
        val player = exoPlayer ?: return
        mediaLibrarySession?.player = sessionPlayerFor(player)
    }

    /** Controls from the MediaSession: in Live they only hold or rejoin; otherwise Unisound decides. */
    private fun interceptSystemControl(control: UnisoundControl, positionMs: Long?): Boolean {
        if (!inLive()) return interceptUnisoundControl(control, positionMs)
        applyLiveRoute(LiveControlPolicy.routeSystem(control))
        return true
    }

    /** In-app controls and Aria arrive as Intents; in Live they can only hold, rejoin or leave. */
    private fun interceptLiveAction(action: String): Boolean {
        if (!inLive()) return false
        val command = when (action) {
            ACTION_PAUSE -> LiveCommand.PAUSE
            ACTION_RESUME -> LiveCommand.RESUME
            ACTION_NEXT -> LiveCommand.NEXT
            ACTION_PREVIOUS -> LiveCommand.PREVIOUS
            ACTION_SEEK_TO -> LiveCommand.SEEK
            ACTION_TOGGLE_SHUFFLE -> LiveCommand.SHUFFLE
            ACTION_TOGGLE_REPEAT -> LiveCommand.REPEAT
            ACTION_UPDATE_QUEUE -> LiveCommand.UPDATE_QUEUE
            ACTION_PLAY, ACTION_PLAY_CONNECT_CONFIRMED -> LiveCommand.PLAY_OWN
            // The seek bar cannot be dragged in Live.
            ACTION_START_SEEK, ACTION_STOP_SEEK -> return true
            else -> LiveCommand.OTHER
        }
        return applyLiveRoute(LiveControlPolicy.route(command))
    }

    /** True when the route consumed the control; [LiveRoute.Leave] lets the caller handle it after leaving. */
    private fun applyLiveRoute(route: LiveRoute): Boolean = when (route) {
        LiveRoute.Hold -> {
            liveManager.hold()
            true
        }
        LiveRoute.Rejoin -> {
            liveManager.rejoin()
            true
        }
        LiveRoute.Leave -> {
            liveManager.leave()
            false
        }
        is LiveRoute.Denied -> {
            liveManager.notify(route.message)
            true
        }
        LiveRoute.Ignored -> true
        LiveRoute.PassThrough -> false
    }

    private fun applyLiveState(state: LiveSessionState?, change: LiveMirrorChange) {
        applyLiveDjChange(change)
        when (change) {
            LiveMirrorChange.NONE -> Unit
            LiveMirrorChange.ENTER, LiveMirrorChange.REJOIN -> rebuildLiveQueue(requireNotNull(state))
            LiveMirrorChange.APPEND -> appendLiveItems(requireNotNull(state))
            LiveMirrorChange.HOLD -> holdLivePlayer()
            LiveMirrorChange.EXIT -> exitLiveMode()
        }
    }

    private fun enterLiveModeIfNeeded() {
        if (isLiveMode) return
        reportCurrentSongFinished(wasSkipped = true)
        cancelCurrentTransitionAndRestoreEffects()
        resetRadioSession()
        playmixTransitions = emptyMap()
        playmixSongIdMap = emptyMap()
        originalQueueSongs = null
        exoPlayer?.repeatMode = Player.REPEAT_MODE_OFF
    }

    /** Loads the schedule from the slot on air (or the next playable one) and starts it on time. */
    private fun rebuildLiveQueue(state: LiveSessionState) {
        val wasLive = isLiveMode
        enterLiveModeIfNeeded()
        val player = exoPlayer ?: return
        val from = liveAdapter.slotToLoad()
        // Empty until loaded, so the Live loop never acts on the previous queue's items.
        player.stop()
        player.clearMediaItems()
        if (from == null) {
            PlaybackStateRepository.setIsPlaying(false)
            stopSeekBarUpdates()
            updateUiAndSystem()
            return
        }
        val entries = state.timeline.songsFrom(from)
        PlaybackStateRepository.activeQueue = PlaybackQueue(
            sourceId = state.slug,
            sourceType = QueueSource.LIVE,
            songs = entries.map { it.second },
            currentIndex = 0,
            entryIds = entries.map { it.first }
        )
        if (!wasLive) refreshSessionPlayer()
        val onAir = state.timeline.at(liveAdapter.clock.now()) is LiveCursor.Playing
        playSongFromQueue(playWhenReady = onAir)
    }

    /** The next block arrived: queue its songs after the known ones without cutting the current one. */
    private fun appendLiveItems(state: LiveSessionState) {
        val player = exoPlayer ?: return
        val queue = PlaybackStateRepository.activeQueue?.takeIf { isLiveMode }
        // Still loading (or out of step): load everything again from the current point.
        if (queue == null || player.mediaItemCount != queue.songs.size) {
            liveAdapter.forceRebuild()
            return
        }
        val known = queue.entryIds.toSet()
        val first = state.timeline.slotsWithSong().firstOrNull { it.key !in known } ?: return
        val added = state.timeline.songsFrom(first).filter { it.first !in known }
        queue.songs = queue.songs + added.map { it.second }
        queue.entryIds = queue.entryIds + added.map { it.first }
        player.addMediaItems(added.map { playbackMediaItemFactory.create(it.second) })
        PlaybackStateRepository.publishQueue()
    }

    private fun holdLivePlayer() {
        val player = exoPlayer ?: return
        reportCurrentSongFinished(wasSkipped = true)
        player.playbackParameters = PlaybackParameters.DEFAULT
        if (player.playWhenReady) player.pause()
        PlaybackStateRepository.setIsPlaying(false)
        stopSeekBarUpdates()
        updateUiAndSystem()
    }

    private fun applyLiveTick(action: LiveTickAction) {
        val player = exoPlayer ?: return
        if (!isLiveMode) return
        when (action) {
            is LiveTickAction.SeekTo -> {
                player.playbackParameters = PlaybackParameters.DEFAULT
                if (action.index == player.currentMediaItemIndex) player.seekTo(action.positionMs)
                else player.seekTo(action.index, action.positionMs)
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                if (!player.playWhenReady && requestAudioFocus()) {
                    registerBecomingNoisyReceiver()
                    // Listening starts now, not when the player was parked for a gap.
                    currentSongStartTimeMs = System.currentTimeMillis()
                    player.play()
                }
            }
            is LiveTickAction.Drift -> when (val drift = action.action) {
                is UnisoundDriftAction.Seek -> {
                    player.playbackParameters = PlaybackParameters.DEFAULT
                    player.seekTo(drift.positionMs)
                }
                is UnisoundDriftAction.SetSpeed -> player.playbackParameters = PlaybackParameters(drift.speed)
                UnisoundDriftAction.None -> Unit
            }
            is LiveTickAction.HoldForGap -> {
                player.playbackParameters = PlaybackParameters.DEFAULT
                if (player.playWhenReady) player.pause()
                action.nextIndex?.let { if (it != player.currentMediaItemIndex) player.seekTo(it, 0L) }
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                PlaybackStateRepository.setIsPlaying(false)
                updateUiAndSystem()
            }
            LiveTickAction.Ended -> if (player.playWhenReady) player.pause()
            LiveTickAction.None, LiveTickAction.Rebuild -> Unit
        }
    }

    /** The audio of the current slot failed: skip it, keeping the schedule. */
    private fun markCurrentLiveSlotFailed() {
        val index = exoPlayer?.currentMediaItemIndex ?: return
        val key = PlaybackStateRepository.activeQueue?.entryIds?.getOrNull(index) ?: return
        reportCurrentSongFinished(wasSkipped = true)
        liveManager.markFailed(key)
    }

    /** Back to normal playback: broadcast audio stops and personal settings apply again. */
    private fun exitLiveMode() {
        if (!isUnisoundMode) restorePersonalPlayerSettings()
        refreshSessionPlayer()
        // The user may already be playing their own music (left Live to play an album).
        if (!isLiveMode) return
        reportCurrentSongFinished(wasSkipped = true)
        val player = exoPlayer
        player?.stop()
        player?.clearMediaItems()
        stopSeekBarUpdates()
        unregisterBecomingNoisyReceiver()
        abandonAudioFocus()
        PlaybackStateRepository.reset()
        updateUiAndSystem()
    }

    /** Aria DJ follows the broadcast: any change of channel, hold or exit cuts the voice. */
    private fun applyLiveDjChange(change: LiveMirrorChange) {
        if (!::liveDj.isInitialized) return
        when (change) {
            LiveMirrorChange.ENTER, LiveMirrorChange.REJOIN -> {
                liveDj.stop("channel_changed")
                liveDj.prepare()
            }
            LiveMirrorChange.HOLD -> liveDj.stop("hold")
            LiveMirrorChange.EXIT -> {
                liveDj.release()
                resetDjDuck()
            }
            LiveMirrorChange.APPEND, LiveMirrorChange.NONE -> Unit
        }
    }

    /** Ramps the music to 30 % (or back to 100 %) for the Aria DJ voice, on Main. Never pauses. */
    private fun setDjDuck(active: Boolean) {
        djDuckJob?.cancel()
        val steps = LiveDjDuckRamp.steps(djDuckMultiplier, if (active) LiveDjDuckRamp.DUCKED else LiveDjDuckRamp.FULL)
        djDuckJob = serviceScope.launch(Dispatchers.Main) {
            for (value in steps) {
                djDuckMultiplier = value
                applyLoudnessNormalization(PlaybackStateRepository.currentSong)
                kotlinx.coroutines.delay(LiveDjDuckRamp.STEP_MS)
            }
        }
    }

    /** Leaving Live: no voice and full volume, right away. */
    private fun resetDjDuck() {
        djDuckJob?.cancel()
        djDuckJob = null
        if (djDuckMultiplier != LiveDjDuckRamp.FULL) {
            djDuckMultiplier = LiveDjDuckRamp.FULL
            applyLoudnessNormalization(PlaybackStateRepository.currentSong)
        }
    }

    private fun logLiveDjEvent(name: String, key: String, value: String) {
        runCatching {
            FirebaseAnalytics.getInstance(applicationContext).logEvent(name, Bundle().apply { putString(key, value) })
        }
    }

    private fun reportLiveDrift(report: UnisoundDriftReport) {
        FirebaseAnalytics.getInstance(applicationContext).logEvent(
            "live_drift",
            Bundle().apply {
                putLong("p50_ms", report.p50Ms)
                putLong("max_ms", report.maxMs)
                putLong("seeks", report.seeks.toLong())
                putLong("samples", report.samples.toLong())
            }
        )
    }

    // endregion

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

                val startPosition = when (queue.sourceType) {
                    QueueSource.UNISOUND -> unisoundAdapter.expectedPositionMs() ?: pendingStartPositionMs
                    QueueSource.LIVE -> liveAdapter.startPositionMs(queue.entryIds.getOrNull(index))
                    else -> pendingStartPositionMs
                }
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
                prepareNextTransitionPlayer(index)
                recordRecentSong(enrichedCurrentSong.id)
                maybePrefetchRadio()
            }
        }
    }

    private fun syncPlaybackConnect() {
        // A room or a broadcast is not this user's own playback: never publish it to Connect.
        if (isSharedMode) return
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

                processPlaybackConnectCommands(commands)
            } finally {
                connectSyncInFlight.set(false)
            }
        }
    }

    private suspend fun processPlaybackConnectCommands(
        commands: List<PlaybackConnectCommandDTO>
    ) = connectCommandMutex.withLock {
        commands
            .sortedBy(PlaybackConnectCommandDTO::sequence)
            .forEach { command ->
                if (playbackConnectRepository.wasCommandProcessed(command.commandId)) {
                    playbackConnectRepository.acknowledge(command, successful = true)
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
                    playbackConnectRepository.markCommandProcessed(command.commandId)
                }
                playbackConnectRepository.acknowledge(
                    command = command,
                    successful = applied,
                    errorCode = if (applied) null else "command_not_applied"
                )
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
                withContext(Dispatchers.Main) {
                    if (inSharedMode()) holdSharedLocally() else pause()
                }
                true
            }
            "RESUME" -> {
                withContext(Dispatchers.Main) { if (!inSharedMode()) resume() }
                true
            }
            "TRANSFER_IN" -> {
                if (inUnisoundRoom()) {
                    Log.w("MusicService", "Connect TRANSFER_IN ignorado: el dispositivo está en una sala de Unisound.")
                    return true
                }
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
                    pendingStartPositionMs = state.anchoredPositionMs()
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
        if (isSharedMode) return
        val queue = PlaybackStateRepository.activeQueue ?: return
        val nextSong = queue.songs.getOrNull(currentQueueIndex + 1)
        val needsSecondPlayer = nextSong != null && (
            queue.sourceType == QueueSource.PLAYMIX ||
                crossfadeMode == CrossfadeMode.INTELLIGENT_EQ ||
                (queue.sourceType == QueueSource.ALBUM && automixEnabled) ||
                crossfadeDurationMs > 0L
            )

        if (needsSecondPlayer && nextSong != null) {
            transitionManager.preloadNextSong(nextSong, resolveNextEntryPointMs(queue, currentQueueIndex, nextSong))
            queue.songs.getOrNull(currentQueueIndex)?.let { current -> prefetchRecipe(queue, current, nextSong) }
        } else {
            cancelCurrentTransitionAndRestoreEffects()
        }
    }

    /** Punto de entrada con el que se precarga la siguiente canción (design D3). */
    private fun resolveNextEntryPointMs(queue: PlaybackQueue, currentQueueIndex: Int, nextSong: Song): Long {
        val currentSong = queue.songs.getOrNull(currentQueueIndex)
        return when {
            queue.sourceType == QueueSource.PLAYMIX -> {
                val transition = currentSong?.let { playmixTransitions["${it.id}:${nextSong.id}"] }
                if (PlaymixMixMode.isSmart(transition)) {
                    currentSong?.let { transitionPlanFor(it, nextSong, playmixSmart = true) }?.doc?.b?.startMs?.toLong()
                        ?: TransitionTiming.resolveEntryPointMs(
                            TransitionTiming.EntryMode.SIMPLE, null, null, nextSong.audioAnalysis?.audioStartMs
                        )
                } else {
                    TransitionTiming.resolveEntryPointMs(TransitionTiming.EntryMode.PLAYMIX, transition?.entryPointMs, null, null)
                }
            }
            queue.sourceType == QueueSource.ALBUM -> 0L
            else -> currentSong?.let { transitionPlanFor(it, nextSong) }?.doc?.b?.startMs?.toLong()
                ?: TransitionTiming.resolveEntryPointMs(
                    TransitionTiming.EntryMode.SIMPLE, null, null, nextSong.audioAnalysis?.audioStartMs
                )
        }
    }

    // ── Transiciones automáticas: un plan (documento de mezcla) por pareja (unify-transition-engine) ──

    private var cachedPlanKey: String? = null
    private var cachedPlan: TransitionPlanner.Plan? = null

    /**
     * Plan de la transición [from] → [to] con el modo y la duración de los ajustes. En modo inteligente
     * la receta del servidor, si ya llegó, tiene prioridad. Se cachea: se consulta en cada tick del seekbar.
     * [playmixSmart]: blend automático de PlayMix, siempre en modo inteligente sin mirar los ajustes.
     */
    private fun transitionPlanFor(from: Song, to: Song, playmixSmart: Boolean = false): TransitionPlanner.Plan? {
        val player = exoPlayer
        val exoDurationMs = if (player != null && player.duration > 0 &&
            player.currentMediaItem?.mediaId == from.id) player.duration else -1L
        val crossfadeMode = if (playmixSmart) CrossfadeMode.INTELLIGENT_EQ else crossfadeMode
        val recipe = if (crossfadeMode == CrossfadeMode.INTELLIGENT_EQ) recipeFor(from, to) else null
        // La identidad del análisis entra en la clave: las canciones se sustituyen por copias enriquecidas.
        val key = "${from.id}:${to.id}:$crossfadeMode:$crossfadeDurationMs:$exoDurationMs:${recipe != null}:" +
            "${System.identityHashCode(from.audioAnalysis)}:${System.identityHashCode(to.audioAnalysis)}"
        if (key == cachedPlanKey) return cachedPlan

        val a = TransitionPlanner.trackOf(from.audioAnalysis, exoDurationMs)
        val b = TransitionPlanner.trackOf(to.audioAnalysis, -1L)
            ?: TransitionPlanner.Track(Long.MAX_VALUE, 0L, Long.MAX_VALUE, null, null, null)
        val plan = when {
            a == null -> null
            recipe != null -> {
                val ratio = TransitionPlanner.beatmatchRatio(a, b)
                TransitionPlanner.Plan(recipe, "recipe", beatSync = ratio != null, bpmRatio = ratio ?: 1.0)
            }
            crossfadeMode == CrossfadeMode.INTELLIGENT_EQ -> TransitionPlanner.plan(TransitionPlanner.Mode.SMART, a, b, 0L)
            crossfadeDurationMs <= 0L -> null // 0 s = transiciones desactivadas
            crossfadeMode == CrossfadeMode.DIRECT_CUT -> TransitionPlanner.plan(TransitionPlanner.Mode.CUT, a, b, crossfadeDurationMs)
            else -> TransitionPlanner.plan(TransitionPlanner.Mode.SOFT, a, b, crossfadeDurationMs)
        }
        cachedPlanKey = key
        cachedPlan = plan
        plan?.let { Log.d("MusicService", "🎚️ Plan ${it.label} ${from.title} → ${to.title}: A=${it.doc.a.startMs}ms, B=${it.doc.b.startMs}ms, ${it.doc.durationMs}ms") }
        return plan
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

    private fun isFlowEligible(queue: PlaybackQueue): Boolean = FlowQueuePolicy.isEligible(
        sourceType = queue.sourceType,
        repeatMode = PlaybackStateRepository.repeatModeLiveData.value ?: PlaybackStateRepository.REPEAT_MODE_OFF,
        enabled = autoplayRadioEnabled
    )

    /**
     * Precarga Flow cuando quedan [FlowQueuePolicy.PREFETCH_THRESHOLD] canciones o menos.
     * Se llama en cada cambio de canción: automático, manual, relevo de crossfade, inicio
     * de cola y restauración.
     */
    @androidx.annotation.MainThread
    private fun maybePrefetchRadio() {
        if (isSeeking) return
        val queue = PlaybackStateRepository.activeQueue ?: return
        if (!isFlowEligible(queue)) return
        if (!FlowQueuePolicy.shouldPrefetch(queue.currentIndex, queue.songs.size, radioPrefetchInFlight.get())) return
        requestFlowBatch(queue)
    }

    /** Lanza la petición de radio si no hay otra en vuelo. Main: lee la cola. */
    @androidx.annotation.MainThread
    private fun requestFlowBatch(queue: PlaybackQueue) {
        if (!radioPrefetchInFlight.compareAndSet(false, true)) return

        val generation = flowGeneration.get()
        val sourceType = queue.flowSeedSourceType.name
        val sourceId = queue.flowSeedSourceId
        val lastSongId = queue.songs.getOrNull(queue.currentIndex)?.id
        val recentIds = radioRecentSongIds.toList()
        val discoveryLevel = flowDiscoveryLevel.takeIf { CapabilitiesManager.isFlowV2Enabled() }
        Log.d(FLOW_TAG, "Pidiendo lote (seed=$sourceType, nivel=$discoveryLevel, generación=$generation)")

        serviceScope.launch {
            try {
                val batch = radioRepository.fetchNextBatch(
                    sourceType = sourceType,
                    sourceId = sourceId,
                    lastSongId = lastSongId,
                    recentSongIds = recentIds,
                    discoveryLevel = discoveryLevel
                )
                withContext(Dispatchers.Main) { onFlowBatch(batch, generation) }
            } finally {
                radioPrefetchInFlight.set(false)
            }
        }
    }

    @androidx.annotation.MainThread
    private fun onFlowBatch(batch: RadioBatch, generation: Long) {
        val queue = PlaybackStateRepository.activeQueue
        val eligible = queue != null && isFlowEligible(queue)
        if (queue == null || !FlowQueuePolicy.shouldApply(generation, flowGeneration.get(), eligible)) {
            Log.d(FLOW_TAG, "Lote descartado: el contexto cambió mientras se pedía.")
            return
        }

        val firstNewIndex = queue.songs.size
        appendRadioSongsToQueue(batch)
        if (!flowAdvancePending) return
        cancelFlowAdvance()
        // Solo se avanza si seguimos parados en la última canción de antes del lote.
        val stillAtEnd = queue.currentIndex == firstNewIndex - 1 && exoPlayer?.isPlaying != true
        if (stillAtEnd && queue.songs.size > firstNewIndex) {
            moveToQueueIndex(firstNewIndex)
        }
    }

    @androidx.annotation.MainThread
    private fun cancelFlowAdvance() {
        flowAdvancePending = false
        flowHandler.removeCallbacks(flowAdvanceTimeout)
    }

    /**
     * Appends radio-fetched songs to the active queue in place, marked as Flow.
     * Runs on main because it mutates the ExoPlayer timeline.  Preserves the
     * queue's original [QueueSource] — the sourceType stays as e.g. ALBUM
     * for Connect purposes, and we surface the radio state separately via
     * [PlaybackStateRepository.radioSessionLiveData].
     */
    @androidx.annotation.MainThread
    private fun appendRadioSongsToQueue(batch: RadioBatch) {
        val queue = PlaybackStateRepository.activeQueue ?: return
        val toAppend = FlowQueuePolicy.songsToAppend(queue.songs.map { it.id }, batch.songs)
        if (toAppend.isEmpty()) return

        val hadNextSong = queue.currentIndex < queue.songs.lastIndex
        queue.appendFlowSongs(toAppend, batch.reasonsBySongId)
        // Las canciones de radio se reportan como RADIO con el requestId de su lote, aunque la cola
        // conserve su QueueSource original.
        queue.attributeLast(toAppend.size, StreamAttribution(batch.requestId, PlaySourceMapper.RADIO))
        PlaybackStateRepository.publishQueue()
        PlaybackStateRepository.setRadioSession(radioRepository.currentSession)

        // Push the new items into the ExoPlayer timeline too, otherwise the
        // native "play next" would still hit end-of-queue.
        val player = exoPlayer
        if (player != null) {
            player.addMediaItems(toAppend.map(playbackMediaItemFactory::create))
            // Si la actual era la última, ahora tiene siguiente: se prepara el relevo.
            if (!hadNextSong) prepareNextTransitionPlayer(queue.currentIndex)
        }
        persistPlaybackState(force = true)

        Log.d(
            FLOW_TAG,
            "🎧 Resonant Radio: added ${toAppend.size} songs " +
                "(seed=${radioRepository.currentSession?.seedName})"
        )
    }

    /** Canciones nuevas de la playlist de origen seguidas del bloque Flow actual. */
    private fun keepingFlowBlock(userSongs: List<Song>): List<Song> {
        val queue = PlaybackStateRepository.activeQueue ?: return userSongs
        val flowSongs = queue.songs.filterIndexed { index, _ -> queue.isFlowEntry(index) }
        if (flowSongs.isEmpty()) return userSongs
        val flowIds = flowSongs.mapTo(HashSet()) { it.id }
        return userSongs.filter { it.id !in flowIds } + flowSongs
    }

    private fun flowSessionSnapshot(): FlowSessionSnapshot =
        FlowSessionSnapshot(radioRepository.currentSession, radioRecentSongIds.toList())

    /**
     * Clears the current radio session — call whenever the user starts a
     * brand-new playback context (tapped a song, playlist, album,
     * received TRANSFER_IN, etc.) so the next queue-end starts a fresh
     * station instead of continuing the previous one.
     */
    private fun resetRadioSession() {
        flowGeneration.incrementAndGet()
        cancelFlowAdvance()
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

    /**
     * [force] bypasses the 5s throttle so this state change is captured
     * immediately instead of possibly being skipped until the next window.
     * [synchronous] blocks the caller (Main) until the write is durable on
     * disk — only worth paying for when the process might die right after
     * this call returns, which today is only true in onDestroy(). Every
     * other call site is async: the snapshot is still built synchronously
     * here (cheap — field reads and a per-song `copy()`), only the Gson
     * encode + SharedPreferences write move off Main.
     */
    private fun persistPlaybackState(force: Boolean = false, synchronous: Boolean = false) {
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
        val currentSongId = player?.currentMediaItem?.mediaId ?: currentSong?.id
        val positionMs = player?.currentPosition ?: 0L
        val repeatMode = PlaybackStateRepository.repeatModeLiveData.value
            ?: PlaybackStateRepository.REPEAT_MODE_OFF
        val shuffleEnabled = PlaybackStateRepository.isShuffleEnabledLiveData.value ?: false

        if (synchronous) {
            playbackStateStore.save(
                queue = queue,
                currentSongId = currentSongId,
                positionMs = positionMs,
                durationMs = durationMs,
                repeatMode = repeatMode,
                shuffleEnabled = shuffleEnabled,
                synchronous = true,
                flowSession = flowSessionSnapshot()
            )
        } else {
            playbackStateStore.saveAsync(
                scope = serviceScope,
                queue = queue,
                currentSongId = currentSongId,
                positionMs = positionMs,
                durationMs = durationMs,
                repeatMode = repeatMode,
                shuffleEnabled = shuffleEnabled,
                flowSession = flowSessionSnapshot()
            )
        }
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
        restored.flowSession?.let { flow ->
            radioRepository.restoreSession(flow.radioSession)
            radioRecentSongIds.clear()
            radioRecentSongIds.addAll(flow.recentSongIds.takeLast(RADIO_RECENT_WINDOW_SIZE))
            PlaybackStateRepository.setRadioSession(flow.radioSession)
        }

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
        maybePrefetchRadio()
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
        val activeQueue = PlaybackStateRepository.activeQueue
        val attribution = activeQueue?.attributionForSong(songToReport.id, activeQueue.currentIndex)
        val playSource = attribution?.playSource ?: PlaySourceMapper.canonical(activeQueue?.sourceType)

        // 2. Calculamos la duración escuchada (sin pausas, nunca más que la canción)
        val songDurationSeconds = (songToReport.audioAnalysis?.durationMs ?: 0) / 1000
        val durationInSeconds = listenClock.listenedSeconds(
            startTime,
            System.currentTimeMillis(),
            songDurationSeconds
        )

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
            playSource = playSource,
            recommendationRequestId = attribution?.requestId
        )

        // 5. Marcamos como reportada para evitar duplicados
        PlaybackStateRepository.streamReported = true
    }
    private fun reportStream(
        songId: String,
        durationInSeconds: Int,
        wasSkipped: Boolean,
        playSource: String,
        recommendationRequestId: String? = null
    ) {
        // El id y la hora se fijan al terminar la escucha: un reintento desde la bandeja reutiliza ambos.
        val playedAtMs = System.currentTimeMillis()
        serviceScope.launch {
            try {
                val userManager = UserManager(applicationContext)
                val userId = userManager.getUserId()

                if (userId.isNullOrEmpty()) {
                    Log.e("StreamAPI", "❌ Error al reportar stream: UserId no encontrado. Abortando.")
                    return@launch // Abortar la corrutina si no hay UserId
                }

                val pending = StreamReporter.newPending(
                    userId = userId,
                    songId = songId,
                    listenDurationInSeconds = durationInSeconds,
                    wasSkipped = wasSkipped,
                    playSource = playSource,
                    playedAtMs = playedAtMs,
                    recommendationRequestId = recommendationRequestId
                )

                Log.i("StreamAPI", "Reportando stream: $songId (${durationInSeconds}s, $playSource)")
                StreamReporter.report(applicationContext, pending)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("StreamAPI", "❌ Error al reportar stream", e)
            }
        }
    }
    private fun updateUiAndSystem() {
        triggerNotificationUpdate()
        com.example.resonant.widget.WidgetUpdateCoordinator.requestUpdate()

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
        ).also { queue ->
            // Atribución de recomendación (solo en memoria). Sin extras, la cola se reporta como siempre,
            // con la fuente canónica de su QueueSource.
            val requestId = originalIntent.getStringExtra(EXTRA_RECOMMENDATION_REQUEST_ID)?.takeIf { it.isNotBlank() }
            val canonicalSource = originalIntent.getStringExtra(EXTRA_PLAY_SOURCE_CANONICAL)?.takeIf { it.isNotBlank() }
            if (requestId != null || canonicalSource != null) {
                queue.attributeAll(
                    StreamAttribution(requestId, canonicalSource ?: PlaySourceMapper.canonical(sourceType))
                )
            }
        }

        if (sourceType == QueueSource.PLAYMIX && sourceId.isNotBlank()) {
            playmixTransitions = emptyMap()
            playmixSongIdMap   = emptyMap()
            loadPlaymixTransitions(sourceId)
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
        if (interceptLiveAction(action)) return
        if (interceptUnisoundAction(action, intent)) return
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
                // Logout (both paths send this): the widget forgets the user's music.
                com.example.resonant.widget.WidgetStateStore.get(applicationContext).clearUserState()
                com.example.resonant.widget.ResonantWidgetProvider.refreshAll(applicationContext)
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
                            updateSongs(keepingFlowBlock(newSongs))
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
        if (isUnisoundMode) {
            return queueCommandResult(
                SessionResult.RESULT_ERROR_INVALID_STATE,
                "La cola es de la sala; se gestiona desde la pantalla de sala"
            )
        }
        if (isLiveMode) {
            return queueCommandResult(SessionResult.RESULT_ERROR_INVALID_STATE, "En directo no se puede editar la cola")
        }
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
            arguments.classLoader = Song::class.java.classLoader
            val suppliedResolvedSong = arguments
                .getParcelable(QueueCommands.ARG_RESOLVED_SONG, Song::class.java)
            if (
                suppliedResolvedSong != null &&
                !suppliedResolvedSong.id.equals(songId, ignoreCase = true)
            ) {
                return queueCommandResult(
                    SessionResult.RESULT_ERROR_BAD_VALUE,
                    "La canción resuelta no coincide con la solicitada"
                )
            }
            val song = suppliedResolvedSong
                ?: SongManager(applicationContext).getSongById(songId)
                ?: return queueCommandResult(
                    SessionResult.RESULT_ERROR_BAD_VALUE,
                    "La canción ya no está disponible"
                )
            if (suppliedResolvedSong != null) {
                song
            } else {
                // ID-only requests (including Aria) resolve inside the service before mutating
                // the queue, keeping the current playback untouched if resolution fails.
                playbackQueueEnricher.enrich(listOf(song), 0).firstOrNull() ?: song
            }
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

                QueueCommands.FLOW_FEEDBACK_APPLY -> applyFlowFeedback(
                    arguments.getString(QueueCommands.ARG_TARGET_TYPE).orEmpty(),
                    arguments.getString(QueueCommands.ARG_TARGET_ID).orEmpty()
                )

                QueueCommands.FLOW_FEEDBACK_UNDO -> undoFlowFeedback()
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
            FlowQueuePolicy.insertionIndexForAdd(entryIds, existingQueue.flowEntryIds, existingQueue.currentIndex)
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
        val shuffledUpcoming = FlowQueuePolicy.reorderPreservingFlow(
            items = queue.songs.drop(upcomingStart).zip(queue.entryIds.drop(upcomingStart)),
            isFlow = { (_, entryId) -> entryId in queue.flowEntryIds },
            reorderUser = { it.shuffled() }
        )
        queue.songs = prefixSongs + shuffledUpcoming.map { it.first }
        queue.entryIds = prefixIds + shuffledUpcoming.map { it.second }
        finishQueueMutation(queue)
        return queueCommandResult(SessionResult.RESULT_SUCCESS, "Próximas canciones mezcladas")
    }

    /** Entradas Flow quitadas por el último feedback, para poder deshacerlo. */
    private data class RemovedFlowEntry(val index: Int, val song: Song, val entryId: String, val reason: String?)
    private var lastFlowRemoval: List<RemovedFlowEntry> = emptyList()

    private fun applyFlowFeedback(targetType: String, targetId: String): SessionResult {
        val queue = PlaybackStateRepository.activeQueue
            ?: return queueCommandResult(SessionResult.RESULT_ERROR_INVALID_STATE, "No hay una cola activa")
        val indices = FlowQueuePolicy.upcomingFlowMatches(
            queue.songs, queue::isFlowEntry, queue.currentIndex, targetType, targetId
        )
        lastFlowRemoval = indices.map { index ->
            val song = queue.songs[index]
            RemovedFlowEntry(index, song, queue.entryIds[index], queue.flowReasons[song.id])
        }
        if (indices.isEmpty()) return queueCommandResult(SessionResult.RESULT_SUCCESS, "")

        cancelCurrentTransitionAndRestoreEffects()
        val removed = indices.toHashSet()
        queue.songs = queue.songs.filterIndexed { index, _ -> index !in removed }
        queue.entryIds = queue.entryIds.filterIndexed { index, _ -> index !in removed }
        finishQueueMutation(queue)
        return queueCommandResult(SessionResult.RESULT_SUCCESS, "")
    }

    private fun undoFlowFeedback(): SessionResult {
        val queue = PlaybackStateRepository.activeQueue
        val removal = lastFlowRemoval
        lastFlowRemoval = emptyList()
        if (queue == null || removal.isEmpty()) return queueCommandResult(SessionResult.RESULT_SUCCESS, "")

        cancelCurrentTransitionAndRestoreEffects()
        val songs = queue.songs.toMutableList()
        val entryIds = queue.entryIds.toMutableList()
        val present = songs.mapTo(HashSet()) { it.id }
        for (entry in removal.sortedBy { it.index }) {
            if (!present.add(entry.song.id)) continue
            val index = FlowQueuePolicy.restoreIndex(entry.index, queue.currentIndex, songs.size)
            songs.add(index, entry.song)
            entryIds.add(index, entry.entryId)
        }
        queue.songs = songs
        queue.entryIds = entryIds
        queue.flowEntryIds = queue.flowEntryIds + removal.map { it.entryId }
        queue.flowReasons = queue.flowReasons + removal.mapNotNull { e -> e.reason?.let { e.song.id to it } }
        finishQueueMutation(queue)
        return queueCommandResult(SessionResult.RESULT_SUCCESS, "")
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
        queue.pruneFlowEntries()
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

        // El bloque Flow nunca se mezcla: sigue detrás de las canciones del usuario.
        val flowSongIds = queue.flowSongIds()
        if (newShuffleState) {
            // Activar Shuffle
            if (originalQueueSongs == null) {
                originalQueueSongs = queue.songs.filter { it.id !in flowSongIds }
            }
            val currentSongId = PlaybackStateRepository.currentSong?.id
            val shuffled = FlowQueuePolicy.reorderPreservingFlow(queue.songs, { it.id in flowSongIds }) { userSongs ->
                val mixed = userSongs.shuffled().toMutableList()
                // Mover canción actual al inicio para no interrumpir
                if (currentSongId != null) {
                    val currentSong = mixed.find { it.id == currentSongId }
                    if (currentSong != null) {
                        mixed.remove(currentSong)
                        mixed.add(0, currentSong)
                    }
                }
                mixed
            }
            updateSongs(shuffled)
        } else {
            // Desactivar Shuffle (Restaurar)
            val original = originalQueueSongs
            if (original != null) {
                updateSongs(keepingFlowBlock(original))
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
                djDuckMultiplier *
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

    // ── Presets v2: recetas sugeridas por el backend para el modo inteligente ──────────────────

    private val recipeDocuments = java.util.concurrent.ConcurrentHashMap<String, MixDocumentDTO>()
    private val recipeMisses: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    @Volatile
    private var recipesUnavailable = false

    private fun recipeKey(from: Song, to: Song) = "${from.id}:${to.id}"

    fun recipeFor(from: Song, to: Song): MixDocumentDTO? = recipeDocuments[recipeKey(from, to)]

    /** Pide al backend la mejor receta para la pareja; un 404 (flag apagado) desactiva las peticiones en la sesión. */
    private fun prefetchRecipe(queue: PlaybackQueue, from: Song, to: Song) {
        if (recipesUnavailable || crossfadeMode != CrossfadeMode.INTELLIGENT_EQ) return
        if (queue.sourceType == QueueSource.PLAYMIX || queue.sourceType == QueueSource.ALBUM) return
        val key = recipeKey(from, to)
        if (recipeDocuments.containsKey(key) || !recipeMisses.add(key)) return
        serviceScope.launch(Dispatchers.IO) {
            try {
                val response = ApiClient.getTransitionPresetService(applicationContext)
                    .suggest(TransitionSuggestRequest(fromSongId = from.id, toSongId = to.id, limit = 1))
                if (response.code() == 404) {
                    recipesUnavailable = true
                    return@launch
                }
                response.body()?.suggestions?.firstOrNull()?.document?.let { doc ->
                    recipeDocuments[key] = doc
                    recipeMisses.remove(key)
                    Log.d("MusicService", "🧩 Receta para ${from.title} → ${to.title}: ${doc.durationMs}ms")
                }
            } catch (e: Exception) {
                Log.w("MusicService", "Sin receta para la pareja: ${e.message}")
            }
        }
    }

    /** Carga las transiciones de la Playmix (con sus clips de Studio) en segundo plano. */
    private fun loadPlaymixTransitions(playmixId: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val detail = PlaymixManager(applicationContext).getPlaymixDetail(playmixId)
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
                    // La cola puede haber cambiado mientras se cargaba.
                    if (PlaybackStateRepository.activeQueue?.sourceId != playmixId) return@withContext
                    playmixSongIdMap   = songIdToPlaymixId
                    playmixTransitions = transitionMap
                    Log.d("MusicService", "🔌 PlayMix transitions loaded: ${transitionMap.size}")
                }
            } catch (e: Exception) {
                Log.e("MusicService", "Failed to load PlayMix transitions", e)
            }
        }
    }

    private var lastPlaymixRefreshUptime = 0L

    /** Las URL firmadas de los clips caducan: se vuelve a pedir el detalle, como mucho una vez por minuto. */
    private fun refreshPlaymixTransitions() {
        val queue = PlaybackStateRepository.activeQueue ?: return
        if (queue.sourceType != QueueSource.PLAYMIX || queue.sourceId.isBlank()) return
        val now = SystemClock.uptimeMillis()
        if (lastPlaymixRefreshUptime != 0L && now - lastPlaymixRefreshUptime < PLAYMIX_REFRESH_MIN_INTERVAL_MS) return
        lastPlaymixRefreshUptime = now
        Log.d("MusicService", "🔄 URL de clip de Studio caducada: refrescando transiciones")
        loadPlaymixTransitions(queue.sourceId)
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
        // Blend automático (mixMode "smart" o pareja sin transición): motor inteligente, no el de PlayMix.
        val playmixTransition = if (isPlaymixMode) {
            PlaymixMixMode.advancedOrNull(playmixTransitions["${oldSong.id}:${nextSong.id}"])
        } else null

        // D1: aislar al player saliente. Solo puede terminar, nunca avanzar a B por su cuenta.
        // La cola completa se reconstruye en el player promocionado tras el relevo.
        try {
            val currentIdx = player.currentMediaItemIndex
            if (currentIdx + 1 < player.mediaItemCount) {
                player.removeMediaItems(currentIdx + 1, player.mediaItemCount)
            }
            player.repeatMode = Player.REPEAT_MODE_OFF
        } catch (e: Exception) {
            Log.w("TransitionTrigger", "No se pudo aislar al player saliente", e)
        }

        // La UI y la cola cambian a B cuando B empieza a sonar (handleTransitionAudible, D6).
        val plan = if (playmixTransition == null) transitionPlanFor(oldSong, nextSong, playmixSmart = isPlaymixMode) else null
        // Un fundido sin EQ propio deja el ecualizador del usuario en A (y se copia a B al entrar):
        // quitarlo de golpe al empezar la transición se oía como un salto de volumen y timbre.
        if (playmixTransition != null || plan == null || plan.hasEq) {
            audioEqualizerController.suspendForTransition()
        }
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
                plan = plan,
                isAlbumMode = queue.sourceType == QueueSource.ALBUM,
                automixEnabled = automixEnabled,
                oldSongTargetVolume = getEffectivePlaybackVolume(oldSong),
                nextSongTargetVolume = getEffectivePlaybackVolume(nextSong)
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
        // Without this, the MediaLibrarySession keeps observing the released
        // oldPlayer: the system-facing playback state freezes (NONE, stale
        // position) right after every crossfade even though audio keeps
        // playing locally via newPlayer — breaking Bluetooth/AVRCP metadata,
        // Android Auto's now-playing screen, lock-screen controls, and
        // eventually the notification/foreground promotion itself. Verified
        // via dumpsys media_session with/without this line.
        mediaLibrarySession?.player = sessionPlayerFor(newPlayer)
        newPlayer.addListener(playerListener)
        newPlayer.addAnalyticsListener(playbackQoeTracker)
        applyStreamingQualityToPlayer(newPlayer)
        audioEqualizerController.resumeAfterTransition(newPlayer.audioSessionId)
        transitionSuppressedForSongId = null

        // D7: el player promocionado hereda el modo de repetición del usuario antes de recibir la cola.
        newPlayer.repeatMode = when (PlaybackStateRepository.repeatModeLiveData.value) {
            PlaybackStateRepository.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ONE
            PlaybackStateRepository.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }

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


        // onMediaItemTransition se ignora durante el relevo: Flow se revisa aquí.
        recordRecentSong(completedSong.id)
        maybePrefetchRadio()

        val nextSong = PlaybackStateRepository.getNextSong(newQueueIndex.takeIf { it >= 0 } ?: newPlayer.currentMediaItemIndex)
        transitionManager.preloadNextSong(nextSong)
    }

    /**
     * D6: B ha empezado a sonar. Ahora sí la UI, la cola, la notificación y Connect pasan a B.
     * Hasta este instante la canción actual seguía siendo A.
     */
    private fun handleTransitionAudible(nextSong: Song, nextIndex: Int) {
        val queue = PlaybackStateRepository.activeQueue
        val resolvedIndex = nextIndex.takeIf { queue?.songs?.getOrNull(it)?.id == nextSong.id }
            ?: queue?.songs?.indexOfFirst { it.id == nextSong.id }
            ?: -1

        // A ha terminado de sonar a efectos de historial (se reporta mientras aún es la actual).
        reportCurrentSongFinished(wasSkipped = false)
        // Si el EQ del usuario sigue activo (fundido sin EQ propio), B suena con él desde que entra.
        audioEqualizerController.mirrorOnTransitionSession(transitionManager.transitionPlayerSessionId)

        PlaybackStateRepository.setCurrentSong(nextSong)
        loadArtworkForSong(nextSong)
        if (resolvedIndex >= 0) queue?.currentIndex = resolvedIndex
        PlaybackStateRepository.publishQueue()

        val newDuration = nextSong.audioAnalysis?.durationMs ?: 0
        PlaybackStateRepository.updatePlaybackPosition(0, newDuration)
        currentSongStartTimeMs = System.currentTimeMillis()
        PlaybackStateRepository.streamReported = false
        updateUiAndSystem()
    }

    /**
     * D5: la transición no se hizo o falló. A sigue sonando hasta su final natural; como ya
     * no tiene items posteriores (D1), llegará a STATE_ENDED y `playNext()` avanzará exactamente
     * una posición. Si A ya había terminado, se avanza ahora.
     */
    private fun handleTransitionFailed(oldPlayer: ExoPlayer, oldSong: Song?, nextSong: Song?, wasAudible: Boolean) {
        Log.w("MusicService", "Transición fallida (audible=$wasAudible). A continúa; B=${nextSong?.title}")

        audioEqualizerController.resumeAfterTransition(oldPlayer.audioSessionId)

        if (wasAudible && oldSong != null) {
            // La UI ya mostraba B: volver a A, que es lo que suena.
            val queue = PlaybackStateRepository.activeQueue
            val oldIndex = queue?.songs?.indexOfFirst { it.id == oldSong.id } ?: -1
            PlaybackStateRepository.setCurrentSong(oldSong)
            if (oldIndex >= 0) queue?.currentIndex = oldIndex
            PlaybackStateRepository.publishQueue()
            loadArtworkForSong(oldSong)
            updateUiAndSystem()
        }

        applyLoudnessNormalization(PlaybackStateRepository.currentSong)
        nextSong?.let { playbackUrlResolver.invalidate(it.id) }
        transitionSuppressedForSongId = PlaybackStateRepository.currentSong?.id

        if (oldPlayer.playbackState == Player.STATE_ENDED) {
            Log.i("MusicService", "A ya había terminado: avanzando a la siguiente canción.")
            playNext()
            return
        }

        // A sigue sonando: el seekbar vuelve a correr (sin volver a disparar la transición).
        if (oldPlayer.isPlaying) startSeekBarUpdates()
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
