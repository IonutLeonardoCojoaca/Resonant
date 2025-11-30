package com.example.resonant.services

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private var currentSongStartTimeMs: Long = 0L

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

            if (duration <= 0) {
                seekBarHandler.postDelayed(this, 200)
                return
            }

            // ✅ DELEGA a MediaSessionManager (esto lo conectaremos si falta)
            if (position % 2000 < 50) {
                mediaSessionManager.updatePlaybackState(position)
            }

            // 🔥 --- INICIO DE LA CORRECCIÓN --- 🔥
            // Reemplazamos el placeholder 0L con la llamada a nuestra función.
            val triggerPosition = calculateTriggerPosition()

            if (triggerPosition > 0 && position >= triggerPosition) {
                Log.i("MusicService", "🏁 TRIGGER by SeekBar: Pos(${position}ms) >= Trigger(${triggerPosition}ms).")
                handleTransitionTrigger() // ¡Llama al nuevo coordinador!
                return // Salimos porque la transición ya ha comenzado y este runnable se detendrá.
            }

            // ✅ ENVÍA la actualización a la UI.
            PlaybackStateRepository.updatePlaybackPosition(position, duration)

            // ✅ REPROGRAMACIÓN.
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

        val player = exoPlayer ?: return
        val currentIndex = player.currentMediaItemIndex
        val nextSong = PlaybackStateRepository.getNextSong(currentIndex)
        lastManualChangeTimestamp = System.currentTimeMillis()

        if (nextSong != null) {
            PlaybackStateRepository.setCurrentSong(nextSong)
            loadArtworkForSong(nextSong)

            player.seekToNextMediaItem()
            player.play()
            updateUiAndSystem()
            Log.d("MusicService", "Acción Proactiva: Saltando a ${nextSong.title}")
        }
    }
    override fun playPrevious() {
        reportCurrentSongFinished(wasSkipped = true)

        if (transitionManager.isTransitioning) {
            transitionManager.cancelCurrentTransition()
        }

        lastManualChangeTimestamp = System.currentTimeMillis()

        val player = exoPlayer ?: return
        val currentIndex = player.currentMediaItemIndex
        val previousSong = PlaybackStateRepository.getPreviousSong(currentIndex)

        if (previousSong != null) {
            PlaybackStateRepository.setCurrentSong(previousSong)
            loadArtworkForSong(previousSong)

            player.seekToPreviousMediaItem()
            player.play()
            updateUiAndSystem()
            Log.d("MusicService", "Acción Proactiva: Volviendo a ${previousSong.title}")
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
        val duration = player.duration

        if (duration <= 0) {
            player.seekTo(position)
            return
        }

        val triggerPosition = calculateTriggerPosition()

        if (triggerPosition > 0 && position >= triggerPosition) {
            Log.d("MusicService", "🛡️ SeekTo protegido. El salto ($position) está en la ventana de crossfade (desde $triggerPosition). Se iniciará playNext().")
            playNext()
        } else {
            player.seekTo(position)
        }
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
                    return // No hacemos nada
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
                    Log.i("PlayerListener", "🏁 Canción finalizada naturalmente. Forzando paso a la siguiente.")

                    reportCurrentSongFinished(wasSkipped = false)

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
    private fun calculateTriggerPosition(): Long {
        val song = PlaybackStateRepository.currentSong ?: return -1L
        val analysis = song.audioAnalysis ?: return -1L
        val isAlbumMode = PlaybackStateRepository.activeQueue?.sourceType == QueueSource.ALBUM

        val nextSong = PlaybackStateRepository.getNextSong(exoPlayer?.currentMediaItemIndex ?: -1)
        if (nextSong == null || nextSong.id == song.id) {
            // No hay siguiente canción o está en bucle. No hay trigger.
            return -1L
        }

        Log.d("MusicService", "🔧 calculateTriggerPosition - Modo: $crossfadeMode, Duración: ${crossfadeDurationMs}ms, AlbumMode: $isAlbumMode")

        return if (isAlbumMode && automixEnabled) {
            // 🔥 CAMBIO CRÍTICO: Modo álbum con automix activado
            -1L // Desactivamos el trigger del SeekBar para el modo álbum

        } else if (!isAlbumMode) {
            // 🔥 CAMBIO CLAVE: Para modos no álbum, considerar el modo inteligente
            val actualMixDuration = if (crossfadeMode == CrossfadeMode.INTELLIGENT_EQ) {
                // El modo inteligente SIEMPRE tiene duración, aunque el slider sea 0
                val nextSong = PlaybackStateRepository.getNextSong(exoPlayer?.currentMediaItemIndex ?: -1)
                if (nextSong != null) {
                    val predictedDuration = transitionManager.predictIntelligentMixDuration(song, nextSong)
                    Log.d("MusicService", "🧠 Modo Inteligente - Duración predicha: ${predictedDuration}ms")
                    predictedDuration
                } else {
                    8000L // Duración por defecto para modo inteligente
                }
            } else {
                // Para modos normales, usar la duración del slider
                crossfadeDurationMs
            }

            // Solo activar trigger si hay duración de mezcla
            if (actualMixDuration > 0) {
                val triggerPos = analysis.durationMs.toLong() - actualMixDuration
                Log.d("MusicService", "🎯 Trigger calculado: ${triggerPos}ms (Duración: ${analysis.durationMs}ms - Mix: ${actualMixDuration}ms)")
                triggerPos
            } else {
                Log.d("MusicService", "❌ Sin duración de mezcla - No hay trigger")
                -1L
            }
        } else {
            -1L
        }
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
        val songToPlay = queue?.songs?.getOrNull(queue.currentIndex)
        if (queue == null || songToPlay == null) {
            Log.w("MusicService", "playSongFromQueue: No hay cola o canción válida para reproducir.")
            stop()
            return
        }

        Log.d("MusicService", "Configurando ExoPlayer para: ${songToPlay.title}")

        PlaybackStateRepository.setCurrentSong(songToPlay)
        loadArtworkForSong(songToPlay)

        val mediaItems = queue.songs.map { MediaItem.fromUri(it.url ?: "") }
        exoPlayer?.setMediaItems(mediaItems, queue.currentIndex, 0L)
        exoPlayer?.prepare()
        exoPlayer?.play()

        currentSongStartTimeMs = System.currentTimeMillis()
        PlaybackStateRepository.streamReported = false

        updatePredictedDurationAsync()

        val nextSong = PlaybackStateRepository.getNextSong(queue.currentIndex)
        transitionManager.preloadNextSong(nextSong)

        notificationManager.startForeground(songToPlay, true, null)
    }
    private fun synchronizeExoPlayerQueue() {
        val queue = PlaybackStateRepository.activeQueue ?: return
        val player = exoPlayer ?: return

        val newMediaItems = queue.songs.map { MediaItem.fromUri(it.url ?: "") }
        val newIndex = queue.currentIndex

        val currentPosition = if (player.currentMediaItemIndex == newIndex) player.currentPosition else 0L

        player.setMediaItems(newMediaItems, newIndex, currentPosition)
        player.prepare()
        player.play()
        Log.d("ExoPlayer", "Cola de ExoPlayer sincronizada.")
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
                }
            }
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

            val currentIndex: Int
            val currentSong: Song?
            val nextSong: Song?
            withContext(Dispatchers.Main) {
                currentIndex = exoPlayer?.currentMediaItemIndex ?: -1
                currentSong = PlaybackStateRepository.currentSong
                nextSong = PlaybackStateRepository.getNextSong(currentIndex)
            }

            if (crossfadeMode == CrossfadeMode.INTELLIGENT_EQ &&
                currentSong != null &&
                nextSong != null &&
                currentSong.id != nextSong.id) {

                val duration = transitionManager.predictIntelligentMixDuration(currentSong, nextSong)
                predictedIntelligentDuration = duration // Actualizar la variable cacheada (seguro)
                Log.d("MusicService", "🧠 Duración inteligente cacheada: ${duration}ms")
            }
        }
    }

    private fun handleTransitionTrigger() {
        val player = exoPlayer ?: return
        val oldSong = PlaybackStateRepository.currentSong ?: return

        reportCurrentSongFinished(wasSkipped = false)

        val currentIndex = player.currentMediaItemIndex
        val nextSong = PlaybackStateRepository.getNextSong(currentIndex) ?: return
        val nextIndex = PlaybackStateRepository.activeQueue?.songs?.indexOf(nextSong) ?: -1

        if (nextIndex == -1) {
            Log.w("MusicService", "No se encontró el índice para la siguiente canción. Saltando.")
            player.seekToNextMediaItem()
            return
        }

        Log.d("MusicService", "🎬 INICIANDO TRANSICIÓN DESDE TRIGGER")
        Log.d("MusicService", "⚙️ Parámetros - Modo: $crossfadeMode, Duración: ${crossfadeDurationMs}ms")

        // 1. Detenemos las actualizaciones del SeekBar
        stopSeekBarUpdates()

        // 2. Actualizamos el estado inmediatamente
        PlaybackStateRepository.setCurrentSong(nextSong)
        loadArtworkForSong(nextSong)

        // 3. Notificamos el reset visual
        val newDuration = nextSong.audioAnalysis?.durationMs ?: 0
        PlaybackStateRepository.updatePlaybackPosition(0, newDuration)

        // 4. Iniciamos la transición de audio
        transitionManager.startTransition(
            oldPlayer = player,
            oldSong = oldSong,
            nextSong = nextSong,
            nextSongIndex = nextIndex,
            crossfadeMode = crossfadeMode,
            isAlbumMode = PlaybackStateRepository.activeQueue?.sourceType == QueueSource.ALBUM,
            automixEnabled = automixEnabled,
            crossfadeDurationMs = crossfadeDurationMs
        )
    }
    private fun handleTransitionComplete(newPlayer: ExoPlayer, oldPlayer: ExoPlayer) {
        Log.d("MusicService", "✅ Transición completada. Realizando el cambio de reproductor.")

        val newIndex = newPlayer.currentMediaItemIndex
        val completedSong = PlaybackStateRepository.activeQueue?.songs?.getOrNull(newIndex)

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

        PlaybackStateRepository.setCurrentSong(completedSong)
        PlaybackStateRepository.setIsPlaying(newPlayer.isPlaying)

        loadArtworkForSong(completedSong)
        updateUiAndSystem()

        if (newPlayer.isPlaying) {
            startSeekBarUpdates()
        }

        updatePredictedDurationAsync()

        val nextSong = PlaybackStateRepository.getNextSong(newIndex)
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