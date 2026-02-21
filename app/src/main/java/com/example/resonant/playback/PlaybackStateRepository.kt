package com.example.resonant.playback

import android.graphics.Bitmap
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.resonant.playback.PlaybackQueue
import com.example.resonant.data.models.Song

object PlaybackStateRepository {
    const val REPEAT_MODE_OFF = 0
    const val REPEAT_MODE_ONE = 1
    const val REPEAT_MODE_ALL = 2
    private val _queueSourceLiveData = MutableLiveData<QueueSource>()
    val queueSourceLiveData: LiveData<QueueSource> = _queueSourceLiveData

    var activeQueue: PlaybackQueue? = null
        set(value) {
            field = value
            value?.let { _queueSourceLiveData.postValue(it.sourceType) }
        }

    var currentSong: Song? = null
        private set

    @Volatile
    var isPlaying: Boolean = false
        private set

    var streamReported: Boolean = false

    private val _currentSongLiveData = MutableLiveData<Song?>()
    val currentSongLiveData: LiveData<Song?> get() = _currentSongLiveData

    private val _isPlayingLiveData = MutableLiveData<Boolean>()
    val isPlayingLiveData: LiveData<Boolean> get() = _isPlayingLiveData

    private val _playbackPosition = MutableLiveData<PlaybackPosition>()
    val playbackPositionLiveData: LiveData<PlaybackPosition> = _playbackPosition

    private val _currentSongBitmap = MutableLiveData<Bitmap?>()
    val currentSongBitmapLiveData: LiveData<Bitmap?> = _currentSongBitmap

    private val _repeatMode = MutableLiveData(REPEAT_MODE_OFF)
    val repeatModeLiveData: LiveData<Int> = _repeatMode

    private val _isShuffleEnabled = MutableLiveData(false)
    val isShuffleEnabledLiveData: LiveData<Boolean> = _isShuffleEnabled

    fun updatePlaybackPosition(position: Long, duration: Int) {
        // Solo actualizamos si hay un cambio real para ser eficientes
        if (_playbackPosition.value?.position != position || _playbackPosition.value?.duration?.toInt() != duration) {
            _playbackPosition.postValue(PlaybackPosition(position, duration))
        }
    }

    fun setCurrentSong(song: Song?) {
        if (currentSong?.id != song?.id) {
            currentSong = song
            _currentSongLiveData.postValue(song)
            if (song != null) {
                streamReported = false
            }
        }
    }

    fun setCurrentSongBitmap(bitmap: Bitmap?) {
        _currentSongBitmap.postValue(bitmap)
    }

    fun setIsPlaying(playing: Boolean) {
        if (isPlaying != playing) {
            isPlaying = playing
            _isPlayingLiveData.postValue(playing)
        }
    }

    fun setRepeatMode(mode: Int) {
        if (_repeatMode.value != mode) {
            _repeatMode.postValue(mode)
        }
    }

    fun setIsShuffleEnabled(enabled: Boolean) {
        if (_isShuffleEnabled.value != enabled) {
            _isShuffleEnabled.postValue(enabled)
        }
    }

    fun getNextSong(currentIndex: Int): Song? {
        val queue = activeQueue ?: return null
        if (queue.songs.isEmpty()) return null

        val nextIndex = currentIndex + 1
        return queue.songs.getOrNull(nextIndex)
    }

    fun getPreviousSong(currentIndex: Int): Song? {
        val queue = activeQueue ?: return null
        if (queue.songs.isEmpty()) return null

        if (currentIndex <= 0) return null

        val previousIndex = currentIndex - 1
        return queue.songs.getOrNull(previousIndex)
    }

    fun reset() {
        activeQueue = null
        setCurrentSong(null)
        setIsPlaying(false)
        streamReported = false
    }
}