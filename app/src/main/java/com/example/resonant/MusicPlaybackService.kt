package com.example.resonant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.session.MediaButtonReceiver
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class MusicPlaybackService : Service() {

    companion object {
        const val ACTION_PLAYBACK_STATE_CHANGED = "com.resonant.ACTION_PLAYBACK_STATE_CHANGED"
        const val ACTION_SONG_CHANGED = "com.resonant.ACTION_SONG_CHANGED"
        const val EXTRA_IS_PLAYING = "com.resonant.EXTRA_IS_PLAYING"
        const val EXTRA_CURRENT_SONG = "com.resonant.EXTRA_CURRENT_SONG"
        const val EXTRA_CURRENT_INDEX = "com.resonant.EXTRA_CURRENT_INDEX"
        const val EXTRA_CURRENT_IMAGE_PATH = "com.resonant.EXTRA_CURRENT_IMAGE_PATH"
        const val EXTRA_DURATION = "com.resonant.EXTRA_DURATION"
        const val ACTION_SEEK_BAR_UPDATE = "com.resonant.ACTION_SEEK_BAR_UPDATE"
        const val ACTION_SEEK_BAR_RESET = "com.resonant.ACTION_SEEK_BAR_RESET"
        const val ACTION_SEEK_TO = "com.resonant.ACTION_SEEK_TO"
        const val EXTRA_SEEK_POSITION = "com.resonant.EXTRA_SEEK_POSITION"
        const val ACTION_REQUEST_STATE = "com.resonant.ACTION_REQUEST_STATE"
        const val EXTRA_QUEUE_SOURCE = "com.resonant.EXTRA_QUEUE_SOURCE"
        const val EXTRA_QUEUE_SOURCE_ID = "com.resonant.EXTRA_QUEUE_SOURCE_ID"

        const val ACTION_PLAY = "com.resonant.ACTION_PLAY"
        const val ACTION_PAUSE = "com.resonant.ACTION_PAUSE"
        const val ACTION_RESUME = "com.resonant.ACTION_RESUME"
        const val ACTION_PREVIOUS = "com.resonant.ACTION_PREVIOUS"
        const val ACTION_NEXT = "com.resonant.ACTION_NEXT"
        const val UPDATE_SONGS = "com.resonant.UPDATE_SONGS"
        const val SONG_LIST = "com.resonant.SONG_LIST"

        const val ACTION_PLAYER_READY = "com.resonant.ACTION_PLAYER_READY"
        const val ACTION_UPDATE_QUEUE = "com.example.resonant.action.UPDATE_QUEUE"

        const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "music_channel"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var mediaPlayer: MediaPlayer? = null
    private var isCompletionListenerEnabled = true
    private var isPrepared = false
    private var isPlaying = false

    var songs: MutableList<Song> = mutableListOf()
    private var currentIndex = 0
    private var currentSong: Song? = null
    private val seekBarHandler = Handler(Looper.getMainLooper())
    private var streamReported = false

    private val _currentSongLiveData = MutableLiveData<Song?>()
    val currentSongLiveData: LiveData<Song?> get() = _currentSongLiveData
    private val _isPlayingLiveData = MutableLiveData<Boolean>()
    val isPlayingLiveData: LiveData<Boolean> get() = _isPlayingLiveData

    private val binder = MusicServiceBinder()

    private lateinit var mediaSession: MediaSessionCompat

    private var activeQueue: PlaybackQueue? = null

    inner class MusicServiceBinder : Binder() {
        fun getService(): MusicPlaybackService = this@MusicPlaybackService
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("MusicService", "Action received: ${intent?.action}")
        intent?.let {
            if (Intent.ACTION_MEDIA_BUTTON == it.action) {
                MediaButtonReceiver.handleIntent(mediaSession, intent)
                // Si es MEDIA_BUTTON, no continuar con el resto (opcional)
                return START_STICKY
            }
        }
        when (intent?.action) {
            ACTION_PLAY -> {
                val song = intent.getParcelableExtra<Song>(EXTRA_CURRENT_SONG)
                val index = intent.getIntExtra(EXTRA_CURRENT_INDEX, -1)
                val bitmapPath = intent.getStringExtra(EXTRA_CURRENT_IMAGE_PATH)
                val bitmap = bitmapPath?.let { BitmapFactory.decodeFile(it) }
                val songList = intent.getParcelableArrayListExtra<Song>(SONG_LIST)
                val sourceType = intent.getSerializableExtra(EXTRA_QUEUE_SOURCE) as? QueueSource ?: QueueSource.UNKNOWN
                val sourceId = intent.getStringExtra(EXTRA_QUEUE_SOURCE_ID) ?: ""
                val sourceName = intent.getStringExtra("EXTRA_QUEUE_SOURCE_NAME")

                if (!songList.isNullOrEmpty() && song != null) {
                    val isSameList = activeQueue?.songs?.map { it.id } == songList.map { it.id }
                    if (activeQueue == null ||
                        activeQueue?.sourceId != sourceId ||
                        activeQueue?.sourceType != sourceType ||
                        !isSameList
                    ) {
                        activeQueue = PlaybackQueue(
                            sourceId = sourceId,
                            sourceType = sourceType,
                            songs = songList,
                            currentIndex = index
                        )

                        // ✅ CONSTRUIMOS EL NOMBRE Y LO GUARDAMOS EN EL VIEWMODEL
                        val queueDisplayName = when (sourceType) {
                            QueueSource.PLAYLIST -> "Cola actual:  Playlist ${sourceName ?: "Playlist"}"
                            QueueSource.ALBUM -> "Cola actual: Album ${sourceName ?: "Álbum"}"
                            QueueSource.FAVORITE_SONGS -> "Cola actual: Favoritos"
                            else -> null // No mostramos nada para fuentes desconocidas
                        }
                        SharedViewModelHolder.sharedViewModel.setQueueName(queueDisplayName)

                        Log.d("MusicService", "Nueva cola activa creada: sourceType=$sourceType, sourceId=$sourceId, index=$index, canciones=${songList.size}")
                        songList.forEachIndexed { i, song ->
                            Log.d("MusicService", "[$i] ${song.title} - ${song.artistName} (${song.id})")
                        }
                        SharedViewModelHolder.sharedViewModel.setQueueSource(sourceType)
                        SharedViewModelHolder.sharedViewModel.setQueueSourceId(sourceId)

                    } else {
                        activeQueue?.currentIndex = index
                        Log.d("MusicService", "Cola activa actualizada (mismo contexto y canciones): sourceType=$sourceType, sourceId=$sourceId, nuevo index=$index")
                    }
                    playSongFromQueue(this) // Usa la cola activa
                    SharedViewModelHolder.sharedViewModel.setCurrentSong(song)
                }

                if (bitmap != null) {
                    SharedViewModelHolder.sharedViewModel.setCurrentSongBitmap(bitmap)
                }

                startForegroundNotification(song!!, true)
            }
            ACTION_UPDATE_QUEUE -> {
                val songList = intent.getParcelableArrayListExtra<Song>(SONG_LIST) ?: arrayListOf()
                val sourceType = intent.getSerializableExtra(EXTRA_QUEUE_SOURCE) as? QueueSource ?: QueueSource.UNKNOWN
                val sourceId = intent.getStringExtra(EXTRA_QUEUE_SOURCE_ID) ?: ""
                val requestedIndex = intent.getIntExtra(EXTRA_CURRENT_INDEX, 0)

                if (activeQueue?.sourceType == sourceType && activeQueue?.sourceId == sourceId) {
                    val currentSongId = currentSong?.id
                    val previousIndex = activeQueue?.currentIndex ?: 0

                    val currentSongNewIndex = currentSongId?.let { id ->
                        songList?.indexOfFirst { it.id == id }
                    } ?: -1

                    val removedIndices = activeQueue?.songs
                        ?.mapIndexed { idx, song -> idx to song.id }
                        ?.filter { (idx, id) ->
                            !songList.any { it.id == id }
                        }
                        ?.map { it.first }
                        ?: emptyList()

                    val indexOffset = removedIndices.count { it < previousIndex }

                    val finalIndex = if (currentSongNewIndex != -1) {
                        currentSongNewIndex
                    } else {
                        val adjustedIndex = previousIndex - indexOffset
                        adjustedIndex.coerceAtMost(songList.size - 1)
                    }

                    activeQueue = PlaybackQueue(
                        sourceId = sourceId,
                        sourceType = sourceType,
                        songs = songList?.toList() ?: emptyList(), // <-- SOLUCIÓN
                        currentIndex = finalIndex
                    )
                    SharedViewModelHolder.sharedViewModel.setQueueSource(sourceType)
                    SharedViewModelHolder.sharedViewModel.setQueueSourceId(sourceId)
                    Log.d("MusicService", "Cola de playlist actualizada: $sourceType $sourceId canciones=${songList.size} index=$finalIndex")

                    if (currentSongNewIndex != -1) {
                        SharedViewModelHolder.sharedViewModel.setCurrentSong(songList[finalIndex])
                    } else {
                        // La canción actual ha sido borrada, pero sigue sonando: NO playSongFromQueue aquí
                        // Actualiza la cola para que el siguiente salto vaya a la canción posterior
                        // Solo actualiza la UI si lo deseas
                    }
                } else if (!songList.isNullOrEmpty()) {
                    // Si no hay cola activa, crea una nueva
                    activeQueue = PlaybackQueue(
                        sourceId = sourceId,
                        sourceType = sourceType,
                        songs = songList,
                        currentIndex = requestedIndex.coerceAtMost(songList.size - 1)
                    )
                    SharedViewModelHolder.sharedViewModel.setQueueSource(sourceType)
                    SharedViewModelHolder.sharedViewModel.setQueueSourceId(sourceId)
                    SharedViewModelHolder.sharedViewModel.setCurrentSong(songList[activeQueue!!.currentIndex])
                } else {
                    // La lista está vacía, pero si hay canción sonando, deja que termine
                    if (currentSong != null && mediaPlayer?.isPlaying == true) {
                        Log.d("MusicService", "Playlist vacía pero la canción actual sigue sonando")
                        activeQueue = PlaybackQueue(
                            sourceId = sourceId,
                            sourceType = sourceType,
                            songs = emptyList(),
                            currentIndex = -1
                        )
                        SharedViewModelHolder.sharedViewModel.setQueueSource(null)
                        SharedViewModelHolder.sharedViewModel.setQueueSourceId(null)
                    } else {
                        stopPlayer()
                        SharedViewModelHolder.sharedViewModel.setCurrentSong(null)
                    }
                }
            }
            ACTION_PAUSE -> {
                pause()
                notifyPlaybackStateChanged()
                updateNotification()
            }
            ACTION_RESUME -> {
                resume()
                notifyPlaybackStateChanged()
                updateNotification()
            }
            ACTION_PREVIOUS -> {
                playPrevious(this)
                notifyPlaybackStateChanged()
                updateNotification()
            }
            ACTION_NEXT -> {
                playNext(this)
                notifyPlaybackStateChanged()
                updateNotification()
            }
            UPDATE_SONGS -> {
                val newSongs = intent.getParcelableArrayListExtra(SONG_LIST, Song::class.java)
                if (!newSongs.isNullOrEmpty()) updateSongs(newSongs)
            }
            ACTION_SEEK_TO -> {
                val position = intent.getIntExtra(EXTRA_SEEK_POSITION, 0)
                if (isPrepared) {
                    mediaPlayer?.seekTo(position)
                }
            }
            ACTION_REQUEST_STATE -> {
                notifyPlaybackStateChanged()
                notifySeekBarUpdate()
                currentSong?.let { notifySongChangedBroadcast(it) }
            }
        }
        return START_STICKY
    }

    private fun setMediaPlayerListeners(context: Context) {
        mediaPlayer?.setOnErrorListener { mp, what, extra ->
            Log.e("MediaPlayer", "Error what=$what extra=$extra")
            mp.reset()
            mp.release()
            mediaPlayer = null
            _isPlayingLiveData.postValue(false)
            true
        }

        mediaPlayer?.setOnCompletionListener {
            stopSeekBarUpdates()
            if (!streamReported) {
                currentSong?.id?.let { id ->
                    reportStream(id)
                    streamReported = true
                }
            }
            if (isCompletionListenerEnabled && activeQueue?.songs?.isNotEmpty() == true) {
                // ✅ CAMBIO: Usamos playNext para mantener el flujo consistente
                playNext(context)
            } else {
                _isPlayingLiveData.postValue(false)
            }
        }
    }

    private fun prepareMediaPlayer(context: Context, url: String, autoStart: Boolean = true) {
        try {
            if (mediaPlayer == null) {
                mediaPlayer = MediaPlayer()
                setMediaPlayerListeners(context)
            } else {
                try {
                    mediaPlayer?.reset()
                    isPrepared = false
                } catch (e: IllegalStateException) {
                    mediaPlayer?.release()
                    mediaPlayer = MediaPlayer()
                    setMediaPlayerListeners(context)
                }
            }

            mediaPlayer?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )

            mediaPlayer?.setDataSource(url)
            mediaPlayer?.setOnPreparedListener {
                isPrepared = true
                if (autoStart) it.start()
                isPlaying = true

                val resetIntent = Intent(ACTION_SEEK_BAR_RESET)
                LocalBroadcastManager.getInstance(context).sendBroadcast(resetIntent)

                val readyIntent = Intent(ACTION_PLAYER_READY).apply {
                    putExtra(EXTRA_DURATION, it.duration)
                }
                LocalBroadcastManager.getInstance(context).sendBroadcast(readyIntent)

                sendPlaybackReadyBroadcast()
                startSeekBarUpdates()
                _isPlayingLiveData.postValue(autoStart)
                notifyPlaybackStateChanged()

                updateMediaMetadata()
                updatePlaybackState()
                updateNotification()
            }

            mediaPlayer?.prepareAsync()

        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
        }
    }

    fun playSong(context: Context, song: Song, index: Int = -1) {
        stopPlayer()
        currentSong = song
        if (index in songs.indices) {
            currentIndex = index
        } else {
            val newIndex = songs.indexOfFirst { it.url == song.url }
            if (newIndex == -1) {
                songs.add(song)
                currentIndex = songs.size - 1
            } else {
                currentIndex = newIndex
            }
        }
        currentSong = songs[currentIndex]
        loadAndSetCurrentSongBitmap(context, currentSong!!)
        isCompletionListenerEnabled = true
        prepareMediaPlayer(context, currentSong!!.url ?: return)
        notifySongChanged()
        updatePlaybackState()
    }

    fun playSongFromQueue(context: Context) {
        val queue = activeQueue ?: run { Log.w("MusicService", "Intento de reproducción sin cola activa"); return }
        if (queue.songs.isEmpty()) { Log.w("MusicService", "Cola activa vacía"); return }
        val song = queue.songs.getOrNull(queue.currentIndex) ?: run { Log.w("MusicService", "Índice fuera de rango: ${queue.currentIndex}"); return }

        streamReported = false
        Log.d("MusicService", "Reproduciendo: ${song.title} [${queue.currentIndex}/${queue.songs.size}]")

        updateCurrentSongState(song)
        loadAndSetCurrentSongBitmap(context, song)
        isCompletionListenerEnabled = true
        prepareMediaPlayer(context, song.url ?: return)
        updatePlaybackState()
    }

    fun playNext(context: Context) {
        streamReported = false
        val queue = activeQueue ?: return
        if (queue.songs.isEmpty()) return
        queue.currentIndex = (queue.currentIndex + 1) % queue.songs.size
        playSongFromQueue(context)
    }

    // Canción anterior en la cola activa
    fun playPrevious(context: Context) {
        streamReported = false
        val queue = activeQueue ?: return
        if (queue.songs.isEmpty()) return
        queue.currentIndex = if (queue.currentIndex - 1 < 0) queue.songs.size - 1 else queue.currentIndex - 1
        playSongFromQueue(context)
    }

    fun pause() {
        mediaPlayer?.pause()
        isPlaying = false
        _isPlayingLiveData.postValue(false)
        currentSong?.let {
            _currentSongLiveData.postValue(it)
        }
        updatePlaybackState()
    }

    fun resume() {
        mediaPlayer?.start()
        isPlaying = true
        _isPlayingLiveData.postValue(true)
        startSeekBarUpdates()
        updatePlaybackState()
    }

    fun stopPlayer() {
        isCompletionListenerEnabled = false
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        _isPlayingLiveData.postValue(false)
    }

    fun seekTo(position: Int) {
        mediaPlayer?.seekTo(position)
    }

    fun isPlaying() = isPlaying

    fun setLooping(looping: Boolean) {
        mediaPlayer?.isLooping = looping
    }

    fun getDuration(): Int {
        return if (mediaPlayer != null && isPrepared) mediaPlayer!!.duration else 0
    }

    fun getCurrentPosition(): Int {
        return if (mediaPlayer != null && isPrepared) mediaPlayer!!.currentPosition else 0
    }

    fun getCurrentSongUrl(): String? {
        return currentSong?.url
    }

    private val updateSeekBarRunnable = object : Runnable {
        override fun run() {
            if (isPrepared && mediaPlayer?.isPlaying == true) {
                val position = mediaPlayer?.currentPosition ?: 0
                val duration = mediaPlayer?.duration ?: 0

                // 🔥 Reportar stream al pasar 30 segundos
                if (!streamReported && position > 30_000) {
                    currentSong?.id?.let { id ->
                        reportStream(id)
                        streamReported = true
                    }
                }

                if (position >= duration) {
                    return
                }

                val intent = Intent(ACTION_SEEK_BAR_UPDATE).apply {
                    putExtra(EXTRA_SEEK_POSITION, position)
                    putExtra(EXTRA_DURATION, duration)
                }
                LocalBroadcastManager.getInstance(this@MusicPlaybackService).sendBroadcast(intent)

                seekBarHandler.postDelayed(this, 200)
            }
        }
    }

    private fun startSeekBarUpdates() {
        seekBarHandler.post(updateSeekBarRunnable)
    }

    private fun stopSeekBarUpdates() {
        seekBarHandler.removeCallbacks(updateSeekBarRunnable)
    }

    private fun sendPlaybackReadyBroadcast() {
        val intent = Intent(ACTION_PLAYER_READY).apply {
            putExtra(EXTRA_CURRENT_SONG, currentSong)
            putExtra(EXTRA_DURATION, mediaPlayer?.duration ?: 0)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun loadAndSetCurrentSongBitmap(context: Context, song: Song) {
        val placeholderRes = R.drawable.album_cover
        val url = song.coverUrl
        val songId = song.id ?: return

        Utils.getCachedSongBitmap(songId, context)?.let { cached ->
            SharedViewModelHolder.sharedViewModel.setCurrentSongBitmap(cached)

            // Actualizar metadata y notificación con la imagen del caché
            val metadataBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artistName)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, mediaPlayer?.duration?.toLong() ?: 0L)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cached)
            mediaSession.setMetadata(metadataBuilder.build())

            updateNotification()
            return
        }

        if (!url.isNullOrBlank()) {
            Glide.with(applicationContext)
                .asBitmap()
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .placeholder(placeholderRes)
                .error(placeholderRes)
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        serviceScope.launch {
                            try {
                                Utils.saveBitmapToCache(context, resource, songId)
                            } catch (e: Exception) {
                                Log.e("MusicService", "Error guardando portada", e)
                            }

                            withContext(Dispatchers.Main) {
                                SharedViewModelHolder.sharedViewModel.setCurrentSongBitmap(resource)
                                updateNotification()
                            }
                        }
                    }

                    override fun onLoadCleared(placeholder: Drawable?) { }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        super.onLoadFailed(errorDrawable)
                        Log.w("MusicService", "No se pudo descargar portada para ${song.title}")
                        updateNotification()
                    }
                })
        } else {
            SharedViewModelHolder.sharedViewModel.setCurrentSongBitmap(null)
            updateNotification()
        }
    }

    // En MusicPlaybackService.kt

    private fun updateCurrentSongState(song: Song) {
        currentSong = song
        SharedViewModelHolder.sharedViewModel.setCurrentSong(song)
        _currentSongLiveData.postValue(song)
        notifySongChangedBroadcast(song)
    }

    private fun notifySongChanged() {
        _currentSongLiveData.postValue(currentSong)
        _isPlayingLiveData.postValue(true)
        currentSong?.let { notifySongChangedBroadcast(it) }
    }

    private fun notifyPlaybackStateChanged() {
        val intent = Intent(ACTION_PLAYBACK_STATE_CHANGED).apply {
            putExtra(EXTRA_IS_PLAYING, isPlaying)
            putExtra(EXTRA_CURRENT_SONG, currentSong)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun notifySongChangedBroadcast(song: Song) {
        val intent = Intent(ACTION_SONG_CHANGED).apply {
            putExtra(EXTRA_CURRENT_SONG, song)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun notifySeekBarUpdate() {
        val position = mediaPlayer?.currentPosition ?: 0
        val duration = mediaPlayer?.duration ?: 0

        val intent = Intent(ACTION_SEEK_BAR_UPDATE).apply {
            putExtra(EXTRA_SEEK_POSITION, position)
            putExtra(EXTRA_DURATION, duration)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun reportStream(songId: String) {
        serviceScope.launch {
            try {
                val api = ApiClient.getService(applicationContext)
                api.incrementStream(songId)
                Log.d("StreamAPI", "✅ Stream reportado para $songId")
            } catch (e: Exception) {
                Log.e("StreamAPI", "❌ Error al reportar stream", e)
            }
        }
    }

    private fun startForegroundNotification(song: Song, isPlaying: Boolean) {
        this.currentSong = song
        this.isPlaying = isPlaying
        val notification = createNotification(song, isPlaying)
        Log.d("MusicService", "Starting foreground with notification")
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification() {
        currentSong?.let {
            val notification = createNotification(it, isPlaying())
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(song: Song, isPlaying: Boolean): Notification {
        val largeIcon = Utils.getCachedSongBitmap(song.id ?: "", this)

        val prevPI = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
        val playPausePI = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this,
            if (isPlaying) PlaybackStateCompat.ACTION_PAUSE else PlaybackStateCompat.ACTION_PLAY
        )
        val nextPI = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
        val stopPI = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP)

        val prevAction = NotificationCompat.Action(R.drawable.skip_previous_filled, "Anterior", prevPI)
        val playPauseAction = NotificationCompat.Action(
            if (isPlaying) R.drawable.pause else R.drawable.play_arrow_filled,
            if (isPlaying) "Pausar" else "Reproducir",
            playPausePI
        )
        val nextAction = NotificationCompat.Action(R.drawable.skip_next_filled, "Siguiente", nextPI)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.s_resonant_white)
            .setContentTitle(song.title ?: "Canción")
            .setContentText(song.artistName ?: "Artista")
            .setLargeIcon(largeIcon)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(stopPI)
            )
            .setDeleteIntent(stopPI)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updatePlaybackState() {
        val actions =
            PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO

        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val pos = (mediaPlayer?.currentPosition ?: 0).toLong()

        val pbState = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, pos, 1.0f)
            .build()

        mediaSession.setPlaybackState(pbState)
    }

    private fun updateMediaMetadata() {
        if (currentSong == null || mediaPlayer == null || !isPrepared) {
            mediaSession.setMetadata(null)
            return
        }

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentSong?.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentSong?.artistName)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, mediaPlayer?.duration?.toLong() ?: 0L)

        Utils.getCachedSongBitmap(currentSong!!.id ?: "", this)?.let {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
        }

        mediaSession.setMetadata(metadataBuilder.build())
    }

    fun updateSongs(newSongs: List<Song>) {
        songs.clear()
        songs.addAll(newSongs)
        currentIndex = 0
    }

    override fun onCreate() {
        super.onCreate()

        mediaSession = MediaSessionCompat(this, "MusicPlaybackService").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    resume()
                    updatePlaybackState()
                    updateNotification()
                }
                override fun onPause() {
                    pause()
                    updatePlaybackState()
                    updateNotification()
                }
                override fun onSkipToNext() {
                    playNext(this@MusicPlaybackService)
                    updatePlaybackState()
                    updateNotification()
                }
                override fun onSkipToPrevious() {
                    playPrevious(this@MusicPlaybackService)
                    updatePlaybackState()
                    updateNotification()
                }
                override fun onStop() {
                    stopPlayer()
                    updatePlaybackState()
                }
                override fun onSeekTo(pos: Long) {
                    seekTo(pos.toInt())
                    updatePlaybackState()
                    updateNotification()
                }
            })
            isActive = true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reproducción de música",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopPlayer()
        stopSelf()
    }

    override fun onDestroy() {
        stopSeekBarUpdates()
        mediaPlayer?.release()
        mediaPlayer = null
        serviceScope.cancel() // 🔥 Cancela todas las corrutinas en curso
        super.onDestroy()
    }
}