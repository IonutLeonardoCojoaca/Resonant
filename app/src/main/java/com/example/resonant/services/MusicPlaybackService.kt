package com.example.resonant.services

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.media.session.MediaButtonReceiver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.example.resonant.data.network.AddStreamDTO
import com.example.resonant.managers.AppNotificationManager
import com.example.resonant.managers.CrossfadeMode
import com.example.resonant.playback.PlaybackQueue
import com.example.resonant.playback.PlaybackStateRepository
import com.example.resonant.playback.PlayerController
import com.example.resonant.managers.PlaylistManager
import com.example.resonant.playback.QueueSource
import com.example.resonant.managers.SettingsManager
import com.example.resonant.managers.TransitionManager
import com.example.resonant.managers.UserManager
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.ApiClient
import com.example.resonant.managers.MediaSessionManager
import com.example.resonant.managers.SongManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MusicPlaybackService : Service(), PlayerController {

    companion object {
        const val EXTRA_CURRENT_SONG = "com.resonant.EXTRA_CURRENT_SONG"
        const val EXTRA_CURRENT_INDEX = "com.resonant.EXTRA_CURRENT_INDEX"
        const val EXTRA_CURRENT_IMAGE_PATH = "com.resonant.EXTRA_CURRENT_IMAGE_PATH"
        const val ACTION_SEEK_TO = "com.resonant.ACTION_SEEK_TO"
        const val EXTRA_SEEK_POSITION = "com.resonant.EXTRA_SEEK_POSITION"
        const val ACTION_REQUEST_STATE = "com.resonant.ACTION_REQUEST_STATE"
        const val EXTRA_QUEUE_SOURCE = "com.resonant.EXTRA_QUEUE_SOURCE"
        const val EXTRA_QUEUE_SOURCE_ID = "com.resonant.EXTRA_QUEUE_SOURCE_ID"
        const val ACTION_START_SEEK = "com.resonant.ACTION_START_SEEK"
        const val ACTION_STOP_SEEK = "com.resonant.ACTION_STOP_SEEK"
        const val ACTION_SHUTDOWN = "com.resonant.ACTION_SHUTDOWN"
        const val ACTION_PLAY = "com.resonant.ACTION_PLAY"
        const val ACTION_PAUSE = "com.resonant.ACTION_PAUSE"
        const val ACTION_RESUME = "com.resonant.ACTION_RESUME"
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
    }

    inner class MusicServiceBinder : Binder() {
        fun getService(): MusicPlaybackService = this@MusicPlaybackService
    }

    private val binder = MusicServiceBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val currentSongLiveData: LiveData<Song?> get() = PlaybackStateRepository.currentSongLiveData

    private lateinit var settingsManager: SettingsManager
    private lateinit var notificationManager: AppNotificationManager
    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var transitionManager: TransitionManager

    private var exoPlayer: ExoPlayer? = null
    private lateinit var playerListener: Player.Listener
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
    private val TARGET_LOUDNESS_LUFS = -14.0f  // Referencia estándar de streaming

    private var currentSongStartTimeMs: Long = 0L
    private var originalQueueSongs: List<Song>? = null

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
            if (position % 5000 < 250) {
                Log.d("SeekBar", "▶ pos=${position}ms dur=${duration}ms song=${PlaybackStateRepository.currentSong?.title} mode=$crossfadeMode")
            }

            if (duration <= 0) {
                seekBarHandler.postDelayed(this, 200)
                return
            }

            if (position % 2000 < 50) {
                mediaSessionManager.updatePlaybackState(position)
            }

            val triggerPosition = calculateTriggerPosition()

            if (triggerPosition > 0 && position >= triggerPosition) {
                Log.i("MusicService", "🏁 TRIGGER by SeekBar: Pos(${position}ms) >= Trigger(${triggerPosition}ms).")
                handleTransitionTrigger()
                return
            }

            PlaybackStateRepository.updatePlaybackPosition(position, duration)
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

    override fun resume() { exoPlayer?.play() }
    override fun pause() {
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
    }
    override fun playNext() {
        reportCurrentSongFinished(wasSkipped = true)

        if (transitionManager.isTransitioning) {
            transitionManager.cancelCurrentTransition()
        }

        val queue = PlaybackStateRepository.activeQueue ?: return
        val nextIndex = queue.currentIndex + 1

        lastManualChangeTimestamp = System.currentTimeMillis()

        if (nextIndex < queue.songs.size) {
            Log.d("MusicService", "Acción Proactiva: Saltando al índice $nextIndex")
            queue.currentIndex = nextIndex
            playSongFromQueue()
        } else {
             Log.d("MusicService", "Fin de la cola alcanzado en playNext")
        }
    }
    override fun playPrevious() {
        reportCurrentSongFinished(wasSkipped = true)

        if (transitionManager.isTransitioning) {
            transitionManager.cancelCurrentTransition()
        }

        lastManualChangeTimestamp = System.currentTimeMillis()

        val queue = PlaybackStateRepository.activeQueue ?: return
        val prevIndex = queue.currentIndex - 1

        if (prevIndex >= 0) {
            Log.d("MusicService", "Acción Proactiva: Volviendo al índice $prevIndex")
            queue.currentIndex = prevIndex
            playSongFromQueue()
        }
    }
    override fun stop() {
        reportCurrentSongFinished(wasSkipped = true)
        if (transitionManager.isTransitioning) {
            transitionManager.cancelCurrentTransition()
        }
        stopForeground(true)
        stopSelf()
    }
    override fun seekTo(position: Long) {
        val player = exoPlayer ?: return
        player.seekTo(position)
        // After a user seek the seekbar trigger will re-evaluate naturally on the next tick.
        // Do not call playNext() here: that would skip the crossfade and leave the UI stale.
    }


    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("MusicService", "Action received: ${intent?.action}")
        val action = intent?.action

        // Manejo del botón multimedia de Android
        if (action == Intent.ACTION_MEDIA_BUTTON) {
            intent.let { MediaButtonReceiver.handleIntent(mediaSessionManager.mediaSession, it) }
            return START_STICKY
        }

        // Manejo de nuestras acciones personalizadas
        if (action != null && intent != null) {
            handleAction(action, intent)
        }

        return START_STICKY
    }
    override fun onCreate() {
        super.onCreate()

        settingsManager = SettingsManager(applicationContext)
        observeSettings() // <-- Movido aquí

        mediaSessionManager = MediaSessionManager(this, this)
        notificationManager = AppNotificationManager(this, mediaSessionManager.sessionToken)
        transitionManager = TransitionManager(
            context = this,
            onTransitionComplete = ::handleTransitionComplete,
            onTransitionFailed = ::handleTransitionFailed,
            onTransitionProgress = { position, duration ->
                PlaybackStateRepository.updatePlaybackPosition(position, duration.toInt())
            }
        )

        setupPlayerListener()
        exoPlayer = createExoPlayer()

        notificationManager.createNotificationChannel()
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stop()
    }
    override fun onDestroy() {
        super.onDestroy()
        stop()

        PlaybackStateRepository.reset()
        exoPlayer?.release()
        exoPlayer = null
        mediaSessionManager.release()
        serviceScope.cancel()
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
        transitionManager.cancelCurrentTransition()

        val currentPositionMs = player.currentPosition
        val oldIndex = queue.currentIndex
        val currentSongId = PlaybackStateRepository.currentSong?.id

        val newIndex = currentSongId?.let { id -> newSongs.indexOfFirst { it.id == id } } ?: -1

        // Actualizamos la cola en el Repository
        queue.songs = newSongs.toMutableList()

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
    }
    fun getCurrentSong(): Song? = PlaybackStateRepository.currentSong
    fun getDuration(): Int = exoPlayer?.duration?.toInt() ?: 0
    fun getCurrentPosition(): Int = exoPlayer?.currentPosition?.toInt() ?: 0
    fun getCurrentIndex(): Int = exoPlayer?.currentMediaItemIndex ?: -1
    fun getCurrentSongUrl(): String? = PlaybackStateRepository.currentSong?.url

    private fun setupPlayerListener() {
        playerListener = object : Player.Listener {

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
                if (isPlayingUpdate) {
                    startSeekBarUpdates()
                } else {
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

                    val newIndex = exoPlayer?.currentMediaItemIndex ?: -1
                    val newSong = PlaybackStateRepository.activeQueue?.songs?.getOrNull(newIndex)

                    if (newSong != null && newSong.id != PlaybackStateRepository.currentSong?.id) {
                        Log.d("PlayerListener", "Transición de ExoPlayer a una NUEVA canción: ${newSong.title}")

                        PlaybackStateRepository.setCurrentSong(newSong)
                        PlaybackStateRepository.activeQueue?.currentIndex = newIndex

                        currentSongStartTimeMs = System.currentTimeMillis()
                        PlaybackStateRepository.streamReported = false

                        loadArtworkForSong(newSong)
                        updateUiAndSystem()
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

                // Source error typically means expired/invalid URL
                val isSourceError = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

                if (isSourceError) {
                    Log.w("PlayerListener", "\uD83D\uDD04 Source error detected. Attempting to refetch URL and retry...")
                    retryCurrentSongWithFreshUrl()
                } else {
                    Log.e("PlayerListener", "Non-recoverable error. Skipping to next song.")
                    playNext()
                }
            }
        }
    }
    private fun createExoPlayer(): ExoPlayer {
        return ExoPlayer.Builder(this)
            .build().apply {
                addListener(playerListener)
                repeatMode = Player.REPEAT_MODE_ALL
            }
    }

    @Volatile
    private var retryCount = 0
    private val MAX_RETRY_COUNT = 2

    private fun retryCurrentSongWithFreshUrl() {
        if (retryCount >= MAX_RETRY_COUNT) {
            Log.e("MusicService", "Max retries ($MAX_RETRY_COUNT) reached. Skipping to next song.")
            retryCount = 0
            playNext()
            return
        }
        retryCount++

        val queue = PlaybackStateRepository.activeQueue ?: run {
            playNext()
            return
        }
        val index = queue.currentIndex
        val currentSong = queue.songs.getOrNull(index) ?: run {
            playNext()
            return
        }

        Log.w("MusicService", "\uD83D\uDD04 Retry #$retryCount: Fetching fresh URL for '${currentSong.title}'")

        serviceScope.launch(Dispatchers.IO) {
            try {
                val songManager = SongManager(applicationContext)
                // Invalidate cache so we get a truly fresh presigned URL
                songManager.invalidateCache(currentSong.id)
                val fetched = songManager.getSongById(currentSong.id)

                if (fetched != null && !fetched.url.isNullOrEmpty()) {
                    // Update the song in the queue
                    val mutableSongs = queue.songs.toMutableList()
                    mutableSongs[index] = fetched
                    queue.songs = mutableSongs.toList()

                    withContext(Dispatchers.Main) {
                        Log.d("MusicService", "\u2705 Got fresh URL for '${fetched.title}'. Replaying.")
                        PlaybackStateRepository.setCurrentSong(fetched)

                        val player = exoPlayer ?: return@withContext
                        val uri = Uri.parse(fetched.url)
                        val mediaItem = MediaItem.Builder()
                            .setUri(uri)
                            .setMediaId(fetched.id)
                            .build()

                        player.setMediaItem(mediaItem)
                        player.prepare()
                        player.play()

                        retryCount = 0 // Reset on success
                        startSeekBarUpdates()
                    }
                } else {
                    Log.e("MusicService", "Retry failed: no URL returned. Skipping.")
                    withContext(Dispatchers.Main) {
                        retryCount = 0
                        playNext()
                    }
                }
            } catch (e: Exception) {
                Log.e("MusicService", "Retry failed with exception: ${e.message}")
                withContext(Dispatchers.Main) {
                    retryCount = 0
                    playNext()
                }
            }
        }
    }

    private fun calculateTriggerPosition(): Long {
        val song = PlaybackStateRepository.currentSong
        if (song == null) {
            Log.d("TriggerPos", "no currentSong")
            return -1L
        }

        val repeatMode = PlaybackStateRepository.repeatModeLiveData.value ?: PlaybackStateRepository.REPEAT_MODE_OFF
        if (repeatMode == PlaybackStateRepository.REPEAT_MODE_ONE) {
            Log.d("TriggerPos", "RepeatOne active — no trigger")
            return -1L
        }

        val isAlbumMode = PlaybackStateRepository.activeQueue?.sourceType == QueueSource.ALBUM
        if (isAlbumMode && automixEnabled) {
            Log.d("TriggerPos", "Album+automix — trigger disabled here")
            return -1L
        }

        val player = exoPlayer
        val exoDurationMs = if (player != null && player.duration > 0) player.duration else -1L

        // Resolve song duration: prefer audioAnalysis (most accurate), fall back to ExoPlayer.
        val analysis = song.audioAnalysis
        val songDurationMs: Long = when {
            (analysis?.durationMs ?: 0) > 0 -> analysis!!.durationMs.toLong()
            exoDurationMs > 0               -> exoDurationMs
            else -> {
                Log.d("TriggerPos", "duration unknown (analysis=${analysis?.durationMs}, exo=$exoDurationMs) — waiting")
                return -1L
            }
        }

        // Need a next song to cross-fade into.
        val queue = PlaybackStateRepository.activeQueue
        val currentQueueIndex = queue?.songs?.indexOfFirst { it.id == song.id } ?: -1
        val nextSong: Song? = when {
            currentQueueIndex >= 0 -> queue?.songs?.getOrNull(currentQueueIndex + 1)
            else -> PlaybackStateRepository.getNextSong(player?.currentMediaItemIndex ?: -1)
        }
        if (nextSong == null || nextSong.id == song.id) {
            Log.d("TriggerPos", "no next song (queueIdx=$currentQueueIndex) — no trigger")
            return -1L
        }

        Log.d("MusicService", "🔧 calculateTriggerPosition - Modo: $crossfadeMode, songDuration: ${songDurationMs}ms, AlbumMode: $isAlbumMode, hasAnalysis: ${analysis != null}")

        if (isAlbumMode) return -1L  // album non-automix: handled elsewhere

        val actualMixDuration: Long = if (crossfadeMode == CrossfadeMode.INTELLIGENT_EQ) {
            // Intelligent mode always picks its own duration, regardless of the slider.
            val cached = predictedIntelligentDuration
            if (cached > 0L) {
                Log.d("MusicService", "🧠 Duración cacheada: ${cached}ms")
                cached
            } else if (analysis != null && nextSong.audioAnalysis != null) {
                // Full analysis available — compute and CACHE the result.
                val predicted = transitionManager.predictIntelligentMixDuration(song, nextSong)
                predictedIntelligentDuration = predicted  // cache so next ticks are cheap
                Log.d("MusicService", "🧠 Duración predicha+cacheada: ${predicted}ms")
                predicted
            } else {
                // Fallback when analysis is missing — use a sensible 8-second default.
                Log.d("MusicService", "🧠 Sin análisis completo — usando 8000ms por defecto")
                8000L
            }
        } else {
            crossfadeDurationMs
        }

        if (actualMixDuration <= 0) {
            Log.d("MusicService", "❌ Sin duración de mezcla (slider=0) — no trigger")
            return -1L
        }

        val triggerPos = songDurationMs - actualMixDuration
        if (triggerPos <= 0) {
            Log.w("MusicService", "⚠️ Trigger negativo ($triggerPos) — duración aún cargando")
            return -1L
        }

        Log.d("MusicService", "🎯 Trigger calculado: ${triggerPos}ms (dur=${songDurationMs}ms - mix=${actualMixDuration}ms)")
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
    private fun playSongFromQueue() {
        val queue = PlaybackStateRepository.activeQueue
        val index = queue?.currentIndex ?: return
        val currentSong = queue.songs.getOrNull(index) ?: return

        serviceScope.launch(Dispatchers.Main) {
            val songManager = SongManager(applicationContext)

            // 1. Fetch Current + Next concurrently (both with fresh URLs)
            val deferredCurrent = async(Dispatchers.IO) {
                try { songManager.getSongById(currentSong.id) } catch (e: Exception) {
                    Log.e("MusicPlaybackService", "Failed to fetch current song ${currentSong.id}", e)
                    null
                }
            }
            val nextSong = queue.songs.getOrNull(index + 1)
            val deferredNext = if (nextSong != null) async(Dispatchers.IO) {
                try { songManager.getSongById(nextSong.id) } catch (e: Exception) {
                    Log.w("MusicPlaybackService", "Failed to prefetch next song ${nextSong.id}", e)
                    null
                }
            } else null

            val fetchedCurrent = deferredCurrent.await()
            val fetchedNext = deferredNext?.await()

            Log.d("MusicPlaybackService", "playSongFromQueue: index=$index, fetchedCurrent=${fetchedCurrent?.id}, urlPresent=${fetchedCurrent?.url != null}")

            // 2. Update Queue in Repository with enriched songs
            if (fetchedCurrent != null) {
                val mutableSongs = queue.songs.toMutableList()
                if (index < mutableSongs.size) {
                    mutableSongs[index] = fetchedCurrent
                }
                if (fetchedNext != null && nextSong != null && (index + 1) < mutableSongs.size) {
                    mutableSongs[index + 1] = fetchedNext
                }
                queue.songs = mutableSongs.toList()

                PlaybackStateRepository.setCurrentSong(fetchedCurrent)
                loadArtworkForSong(fetchedCurrent)
            } else {
                Log.w("MusicPlaybackService", "Failed to fetch full song data for ${currentSong.id}")
                PlaybackStateRepository.setCurrentSong(currentSong)
                loadArtworkForSong(currentSong)
            }

            val player = exoPlayer ?: run {
                Log.e("MusicPlaybackService", "ExoPlayer is NULL!")
                return@launch
            }

            // 3. CHECK VALIDITY — bail if no URL
            val songToPlay = fetchedCurrent ?: currentSong
            if (songToPlay.url.isNullOrEmpty()) {
                Log.e("MusicPlaybackService", "\u274C La canción '${songToPlay.title}' no tiene URL. Intentando siguiente...")
                // Don't just stop — skip to next song
                val nextIdx = index + 1
                if (nextIdx < queue.songs.size) {
                    queue.currentIndex = nextIdx
                    playSongFromQueue()
                }
                return@launch
            }

            // 4. Build media items — ONLY songs that have valid URLs
            //    This prevents ExoPlayer from trying to load empty URIs
            val validItems = mutableListOf<Pair<Int, MediaItem>>()
            var adjustedStartIndex = 0

            queue.songs.forEachIndexed { i, song ->
                val url = song.url
                if (!url.isNullOrEmpty()) {
                    if (i == index) adjustedStartIndex = validItems.size
                    val uri = if (url.startsWith("http") || url.startsWith("https")) {
                        Uri.parse(url)
                    } else {
                        Uri.fromFile(java.io.File(url))
                    }
                    validItems.add(i to MediaItem.Builder().setUri(uri).setMediaId(song.id).build())
                } else {
                    Log.v("MusicPlaybackService", "Skipping song ${song.id} (no URL) from ExoPlayer queue")
                }
            }

            if (validItems.isEmpty()) {
                Log.e("MusicPlaybackService", "No songs with valid URLs in queue!")
                return@launch
            }

            player.setMediaItems(validItems.map { it.second }, adjustedStartIndex, 0L)
            player.prepare()
            player.play()
            applyLoudnessNormalization(songToPlay)
            Log.d("MusicPlaybackService", "\u25B6\uFE0F Playback started for '${songToPlay.title}' (${validItems.size} valid items in ExoPlayer)")

            currentSongStartTimeMs = System.currentTimeMillis()
            PlaybackStateRepository.streamReported = false

            updatePredictedDurationAsync()

            // 5. Preload next song via TransitionManager
            val actualNext = queue.songs.getOrNull(index + 1)
            transitionManager.preloadNextSong(if (fetchedNext != null) fetchedNext else actualNext)

            notificationManager.startForeground(songToPlay, true, null)

            // 6. Proactively preload the song after next (index+2)
            preloadNextSongMetadata(index + 2)
        }
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
             player.play()
             return
        }

        val currentSongId = PlaybackStateRepository.currentSong?.id
        val newSongs = queue.songs
        
        // Buscar dónde debería estar la canción actual en la NUEVA lista
        val newIndexTarget = newSongs.indexOfFirst { it.id == currentSongId }

        // Si la canción actual ya no está en la lista o algo raro pasa, fallback a recarga total
        if (newIndexTarget == -1) {
             val items = newSongs.map { createMediaItem(it) }
             player.setMediaItems(items, 0, 0L)
             player.prepare()
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
        val url = song.url
        val uri = if (url.isNullOrBlank()) {
             Uri.EMPTY // Evita crash con File("")
        } else if (url.startsWith("http") || url.startsWith("https")) {
            Uri.parse(url)
        } else {
            Uri.fromFile(java.io.File(url))
        }
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(song.id)
            .build()
    }
    private fun reportCurrentSongFinished(wasSkipped: Boolean) {
        // Si ya se reportó (ej. en una transición), no hacerlo de nuevo.
        if (PlaybackStateRepository.streamReported) {
            return
        }

        // 1. Obtenemos los datos de la canción que acaba de sonar
        val songToReport = PlaybackStateRepository.currentSong ?: return
        val startTime = currentSongStartTimeMs
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
        val isPlaying = PlaybackStateRepository.isPlaying
        val position = exoPlayer?.currentPosition ?: 0L

        // Su única responsabilidad ahora es el estado de reproducción (Play/Pause y posición)
        mediaSessionManager.updatePlaybackState(position)

        // La notificación se actualiza desde updateMediaSessionMetadata,
        // pero podemos dejar una llamada aquí para el estado Play/Pause
        notificationManager.updateNotification(
            PlaybackStateRepository.currentSong,
            isPlaying,
            PlaybackStateRepository.currentSongBitmapLiveData.value
        )

        Log.d("MusicService", "🔄 Estado de reproducción actualizado")
    }
    private fun handleAction(action: String, intent: Intent) {
        when (action) {
            ACTION_PLAY -> {
                reportCurrentSongFinished(wasSkipped = true)

                transitionManager.cancelCurrentTransition()

                val song = intent.getParcelableExtra<Song>(EXTRA_CURRENT_SONG)
                val songList = intent.getParcelableArrayListExtra<Song>(SONG_LIST)
                val index = intent.getIntExtra(EXTRA_CURRENT_INDEX, -1)

                val sourceType = intent.getSerializableExtra(EXTRA_QUEUE_SOURCE) as? QueueSource
                    ?: QueueSource.UNKNOWN
                val sourceId = intent.getStringExtra(EXTRA_QUEUE_SOURCE_ID) ?: ""

                if (song != null && !songList.isNullOrEmpty() && index != -1) {
                    PlaybackStateRepository.activeQueue = PlaybackQueue(
                        songs = songList,
                        currentIndex = index,
                        sourceId = sourceId,
                        sourceType = sourceType
                    )

                    playSongFromQueue()

                    // Reset shuffle state on new play
                    PlaybackStateRepository.setIsShuffleEnabled(false)
                    originalQueueSongs = null
                }
            }
            ACTION_TOGGLE_SHUFFLE -> toggleShuffle()
            ACTION_TOGGLE_REPEAT -> toggleRepeat()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_NEXT -> playNext()
            ACTION_PREVIOUS -> playPrevious()
            ACTION_SEEK_TO -> seekTo(intent.getIntExtra(EXTRA_SEEK_POSITION, 0).toLong())
            ACTION_UPDATE_QUEUE -> {
                val songList = intent.getParcelableArrayListExtra<Song>(SONG_LIST) ?: return
                updateSongs(songList)
            }
            ACTION_SHUTDOWN -> stop()
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
        val duration = exoPlayer?.duration ?: 0L
        val bitmap = PlaybackStateRepository.currentSongBitmapLiveData.value

        mediaSessionManager.updateMetadata(song, bitmap, duration)
        notificationManager.updateNotification(song, PlaybackStateRepository.isPlaying, bitmap)
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

    /**
     * Aplica el volumen normalizado a ExoPlayer.
     */
    private fun applyLoudnessNormalization(song: Song?) {
        val player = exoPlayer ?: return
        val linearVolume = getNormalizedVolume(song)
        player.volume = linearVolume
        Log.d("Loudness", "Aplicando volumen absoluto normalizado para '${song?.title}': $linearVolume")
    }

    private fun preloadNextSongMetadata(index: Int) {
        val queue = PlaybackStateRepository.activeQueue ?: return
        val nextSong = queue.songs.getOrNull(index) ?: return

        // If URL is already present and looks valid, skip preload
        if (!nextSong.url.isNullOrEmpty()) {
            Log.d("MusicService", "\u26A1 Song at index $index already has URL, skipping preload")
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

        // Update UI immediately to the next song
        PlaybackStateRepository.setCurrentSong(nextSong)
        loadArtworkForSong(nextSong)

        val newDuration = nextSong.audioAnalysis?.durationMs ?: 0
        PlaybackStateRepository.updatePlaybackPosition(0, newDuration)

        transitionManager.startTransition(
            oldPlayer = player,
            oldSong = oldSong,
            nextSong = nextSong,
            nextSongIndex = nextIndex,
            crossfadeMode = crossfadeMode,
            isAlbumMode = PlaybackStateRepository.activeQueue?.sourceType == QueueSource.ALBUM,
            automixEnabled = automixEnabled,
            crossfadeDurationMs = crossfadeDurationMs,
            oldSongTargetVolume = getNormalizedVolume(oldSong),
            nextSongTargetVolume = getNormalizedVolume(nextSong)
        )
    }


    private fun handleTransitionComplete(newPlayer: ExoPlayer, oldPlayer: ExoPlayer) {
        Log.d("MusicService", "✅ Transición completada. Realizando el cambio de reproductor.")

        // Look up the completed song by its mediaId (set on each MediaItem by buildValidMediaItems).
        // This is safe even when empty-URL songs were filtered out of the transition player's
        // queue, because it ignores position entirely and matches by song ID.
        val completedSongId = newPlayer.currentMediaItem?.mediaId
        val completedSong = if (!completedSongId.isNullOrEmpty()) {
            PlaybackStateRepository.activeQueue?.songs?.firstOrNull { it.id == completedSongId }
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

        currentSongStartTimeMs = System.currentTimeMillis()
        PlaybackStateRepository.streamReported = false

        Log.d("MusicService", "Reproductor principal actualizado a la nueva instancia.")

        // Update the queue's currentIndex to match the completed song
        val newQueueIndex = PlaybackStateRepository.activeQueue?.songs?.indexOf(completedSong) ?: -1
        if (newQueueIndex >= 0) {
            PlaybackStateRepository.activeQueue?.currentIndex = newQueueIndex
        }

        PlaybackStateRepository.setCurrentSong(completedSong)
        PlaybackStateRepository.setIsPlaying(newPlayer.isPlaying)

        loadArtworkForSong(completedSong)
        applyLoudnessNormalization(completedSong)
        updateUiAndSystem()

        if (newPlayer.isPlaying) {
            startSeekBarUpdates()
        }

        updatePredictedDurationAsync()

        val nextSong = PlaybackStateRepository.getNextSong(newQueueIndex.takeIf { it >= 0 } ?: newPlayer.currentMediaItemIndex)
        transitionManager.preloadNextSong(nextSong)
    }

    private fun handleTransitionFailed(oldPlayer: ExoPlayer) {
        // La transición no se hizo o falló, así que simplemente saltamos.
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