package com.example.resonant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.localbroadcastmanager.content.LocalBroadcastManager
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
        const val ACTION_SEEK_TO = "com.tuapp.ACTION_SEEK_TO"
        const val EXTRA_SEEK_POSITION = "com.resonant.EXTRA_SEEK_POSITION"
        const val ACTION_REQUEST_STATE = "com.example.app.ACTION_REQUEST_STATE"

        const val ACTION_PLAY = "com.resonant.ACTION_PLAY"
        const val ACTION_PAUSE = "com.resonant.ACTION_PAUSE"
        const val ACTION_RESUME = "com.resonant.ACTION_RESUME"
        const val ACTION_PREVIOUS = "com.resonant.ACTION_PREVIOUS"
        const val ACTION_NEXT = "com.resonant.ACTION_NEXT"
        const val UPDATE_SONGS = "com.resonant.UPDATE_SONGS"
        const val SONG_LIST = "com.resonant.SONG_LIST"

        const val ACTION_PLAYER_READY = "ACTION_PLAYER_READY"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var isCompletionListenerEnabled = true
    private var isPrepared = false
    private var isPlaying = false

    var songs: MutableList<Song> = mutableListOf()
    private var currentIndex = 0
    private var currentSong: Song? = null
    private val seekBarHandler = Handler(Looper.getMainLooper())

    private val _currentSongLiveData = MutableLiveData<Song?>()
    val currentSongLiveData: LiveData<Song?> get() = _currentSongLiveData
    private val _isPlayingLiveData = MutableLiveData<Boolean>()
    val isPlayingLiveData: LiveData<Boolean> get() = _isPlayingLiveData

    private val binder = MusicServiceBinder()

    private lateinit var mediaSession: MediaSessionCompat

    inner class MusicServiceBinder : Binder() {
        fun getService(): MusicPlaybackService = this@MusicPlaybackService
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val song = intent.getParcelableExtra<Song>(EXTRA_CURRENT_SONG)
                val index = intent.getIntExtra(EXTRA_CURRENT_INDEX, -1)
                val bitmapPath = intent.getStringExtra(EXTRA_CURRENT_IMAGE_PATH)
                val bitmap = bitmapPath?.let { BitmapFactory.decodeFile(it) }
                val songList = intent.getParcelableArrayListExtra<Song>(SONG_LIST)

                if (!songList.isNullOrEmpty()) {
                    updateSongs(songList)
                }

                if (song != null) {
                    playSong(this, song, index)
                    SharedViewModelHolder.sharedViewModel.setCurrentSong(song) // <- Aquí
                }

                if (bitmap != null) {
                    SharedViewModelHolder.sharedViewModel.setCurrentSongBitmap(bitmap)
                }
                startForegroundNotification(song!!, true)
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
                currentSong?.let { notifySongChangedBroadcast(it) }
                notifyPlaybackStateChanged()
                updateNotification()
            }
            ACTION_NEXT -> {
                playNext(this)
                currentSong?.let { notifySongChangedBroadcast(it) }
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
            stopSeekBarUpdates() // 👈
            if (isCompletionListenerEnabled && songs.isNotEmpty()) {
                if (currentIndex < songs.size - 1) {
                    playNext(context)
                } else {
                    _isPlayingLiveData.postValue(false)
                }
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
            }

            mediaPlayer?.setOnCompletionListener {
                if (isCompletionListenerEnabled && songs.isNotEmpty()) {
                    if (currentIndex < songs.size - 1) {
                        playNext(context)
                    } else {
                        _isPlayingLiveData.postValue(false)
                    }
                }
            }
            val resetIntent = Intent(ACTION_SEEK_BAR_RESET)
            LocalBroadcastManager.getInstance(context).sendBroadcast(resetIntent)
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
    }

    fun playNext(context: Context) {
        if (songs.isEmpty()) return
        currentIndex = (currentIndex + 1) % songs.size
        currentSong = songs[currentIndex]
        loadAndSetCurrentSongBitmap(context, currentSong!!)
        notifySongChanged()
        prepareMediaPlayer(context, currentSong!!.url ?: return)
    }

    fun playPrevious(context: Context) {
        if (songs.isEmpty()) return
        currentIndex = if (currentIndex - 1 < 0) songs.size - 1 else currentIndex - 1
        currentSong = songs[currentIndex]
        loadAndSetCurrentSongBitmap(context, currentSong!!)
        notifySongChanged()
        prepareMediaPlayer(context, currentSong!!.url ?: return)
    }

    fun pause() {
        mediaPlayer?.pause()
        isPlaying = false
        _isPlayingLiveData.postValue(false)
        currentSong?.let {
            _currentSongLiveData.postValue(it)
        }
    }

    fun resume() {
        mediaPlayer?.start()
        isPlaying = true
        _isPlayingLiveData.postValue(true)
        startSeekBarUpdates()
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
        val fileName = "cover_${song.id}.png"
        val file = File(context.cacheDir, fileName)
        if (file.exists()) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            SharedViewModelHolder.sharedViewModel.setCurrentSongBitmap(bitmap)
        } else {
            SharedViewModelHolder.sharedViewModel.setCurrentSongBitmap(null)
        }
    }

    private fun notifySongChanged() {
        _currentSongLiveData.postValue(currentSong)
        _isPlayingLiveData.postValue(true)
        currentSong?.let { notifySongChangedBroadcast(it) } // <--- aquí lo usas
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

    private fun startForegroundNotification(song: Song, isPlaying: Boolean) {
        val notification = createCustomNotification(song, isPlaying)
        startForeground(1, notification)
    }

    private fun updateNotification() {
        currentSong?.let {
            val notification = createCustomNotification(it, isPlaying())
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, notification)
        }
    }

    private fun createCustomNotification(song: Song, isPlaying: Boolean): Notification {
        val largeIcon = Utils.getCachedSongBitmap(song.id ?: "", this)

        val remoteViews = RemoteViews(packageName, R.layout.notification_custom)

        // Set text y bitmap
        remoteViews.setTextViewText(R.id.songTitle, song.title ?: "Canción")
        remoteViews.setTextViewText(R.id.artistName, song.artistName ?: "Artista")
        if (largeIcon != null) {
            remoteViews.setImageViewBitmap(R.id.songImage, largeIcon)
        } else {
            remoteViews.setImageViewResource(R.id.songImage, R.drawable.album_cover)
        }

        // Cambiar icono play/pause según estado
        val playPauseIcon = if (isPlaying) R.drawable.pause else R.drawable.play_arrow_filled
        remoteViews.setImageViewResource(R.id.playPauseButton, playPauseIcon)

        // Intent para las acciones (prev, play/pause, next)
        val intentPrev = Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_PREVIOUS }
        val pendingPrev = PendingIntent.getService(this, 2, intentPrev, PendingIntent.FLAG_IMMUTABLE)
        remoteViews.setOnClickPendingIntent(R.id.previousSongButton, pendingPrev)

        val actionPlayPause = if (isPlaying) ACTION_PAUSE else ACTION_RESUME
        val intentPlayPause = Intent(this, MusicPlaybackService::class.java).apply { action = actionPlayPause }
        val pendingPlayPause = PendingIntent.getService(this, 1, intentPlayPause, PendingIntent.FLAG_IMMUTABLE)
        remoteViews.setOnClickPendingIntent(R.id.playPauseButton, pendingPlayPause)

        val intentNext = Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_NEXT }
        val pendingNext = PendingIntent.getService(this, 3, intentNext, PendingIntent.FLAG_IMMUTABLE)
        remoteViews.setOnClickPendingIntent(R.id.nextSongButton, pendingNext)

        // Construir la notificación
        return NotificationCompat.Builder(this, "music_channel")
            .setSmallIcon(R.drawable.s_resonant_white)
            .setCustomContentView(remoteViews)  // CORRECTO para notificación personalizada
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()

    }


    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "MusicPlaybackService").apply {
            isActive = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "music_channel",
                "Reproducción de música",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

    }

    fun updateSongs(newSongs: List<Song>) {
        songs.clear()
        songs.addAll(newSongs)
        currentIndex = 0
    }

    override fun onDestroy() {
        stopSeekBarUpdates()
        mediaPlayer?.release()
        super.onDestroy()
    }


}
