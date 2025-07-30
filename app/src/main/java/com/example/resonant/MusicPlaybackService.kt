package com.example.resonant

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Binder
import android.os.IBinder
import android.util.Log
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

        const val ACTION_PLAY = "com.resonant.ACTION_PLAY"
        const val ACTION_PAUSE = "com.resonant.ACTION_PAUSE"
        const val ACTION_RESUME = "com.resonant.ACTION_RESUME"
        const val ACTION_PREVIOUS = "com.resonant.ACTION_PREVIOUS"
        const val ACTION_NEXT = "com.resonant.ACTION_NEXT"
        const val UPDATE_SONGS = "com.resonant.UPDATE_SONGS"

        const val SONG_LIST = "com.resonant.SONG_LIST"
    }

    // MediaPlayer interno
    private var mediaPlayer: MediaPlayer? = null
    private var isCompletionListenerEnabled = true
    private var isPrepared = false
    private var isPlaying = false

    // Lista y estado
    var songs: MutableList<Song> = mutableListOf()
    private var currentIndex = 0
    private var currentSong: Song? = null

    // LiveData para exponer al UI
    private val _currentSongLiveData = MutableLiveData<Song?>()
    val currentSongLiveData: LiveData<Song?> get() = _currentSongLiveData

    private val _isPlayingLiveData = MutableLiveData<Boolean>()
    val isPlayingLiveData: LiveData<Boolean> get() = _isPlayingLiveData

    private val binder = MusicServiceBinder()

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
                if (song != null) playSong(this, song, index)
                if (bitmap != null) SharedViewModelHolder.sharedViewModel.setCurrentSongBitmap(bitmap)
            }
            ACTION_PAUSE -> {
                pause()
                notifyPlaybackStateChanged()
            }
            ACTION_RESUME -> {
                resume()
                notifyPlaybackStateChanged()
            }
            ACTION_PREVIOUS -> {
                playPrevious(this)
                currentSong?.let { notifySongChangedBroadcast(it) }
                notifyPlaybackStateChanged()
            }
            ACTION_NEXT -> {
                playNext(this)
                currentSong?.let { notifySongChangedBroadcast(it) }
                notifyPlaybackStateChanged()
            }
            UPDATE_SONGS -> {
                val newSongs = intent.getParcelableArrayListExtra(SONG_LIST, Song::class.java)
                if (!newSongs.isNullOrEmpty()) updateSongs(newSongs)
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
                _isPlayingLiveData.postValue(autoStart)
                notifyPlaybackStateChanged()
            }

            mediaPlayer?.setOnCompletionListener {
                if (isCompletionListenerEnabled && songs.isNotEmpty()) {
                    if (currentIndex < songs.size - 1) {
                        playNext(context)
                    } else {
                        // Fin de la lista, quizá parar
                        _isPlayingLiveData.postValue(false)
                    }
                }
            }

            mediaPlayer?.prepareAsync()

        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
        }
    }

    fun playSong(context: Context, song: Song, index: Int = -1) {
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

    fun isPlaying(): Boolean {
        return mediaPlayer?.isPlaying ?: false
    }

    fun setLooping(looping: Boolean) {
        mediaPlayer?.isLooping = looping
    }

    fun getCurrentPosition(): Int {
        return mediaPlayer?.currentPosition ?: 0
    }

    fun getDuration(): Int {
        return if (isPrepared) mediaPlayer?.duration ?: 0 else 0
    }

    fun getCurrentSongUrl(): String? {
        return currentSong?.url
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

    fun updateSongs(newSongs: List<Song>) {
        songs.clear()
        songs.addAll(newSongs)
        currentIndex = 0
    }

}
