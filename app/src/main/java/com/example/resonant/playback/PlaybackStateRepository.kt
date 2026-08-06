package com.example.resonant.playback

import android.graphics.Bitmap
import androidx.annotation.MainThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.resonant.playback.PlaybackQueue
import com.example.resonant.data.models.Song
import java.util.concurrent.atomic.AtomicLong

data class QueueItemSnapshot(
    val entryId: String,
    val song: Song,
    val queueIndex: Int,
    val isCurrent: Boolean
)

data class QueueSnapshot(
    val revision: Long,
    val sourceId: String,
    val sourceType: QueueSource,
    val currentIndex: Int,
    val items: List<QueueItemSnapshot>,
    val shuffleEnabled: Boolean
) {
    val canReorderUpcoming: Boolean
        get() = !shuffleEnabled && sourceType != QueueSource.PLAYMIX
}

object PlaybackStateRepository {
    const val REPEAT_MODE_OFF = 0
    const val REPEAT_MODE_ONE = 1
    const val REPEAT_MODE_ALL = 2
    private val _queueSourceLiveData = MutableLiveData<QueueSource>()
    val queueSourceLiveData: LiveData<QueueSource> = _queueSourceLiveData
    private val queueRevision = AtomicLong(0L)
    private val _queueSnapshotLiveData = MutableLiveData<QueueSnapshot?>()
    val queueSnapshotLiveData: LiveData<QueueSnapshot?> = _queueSnapshotLiveData

    var activeQueue: PlaybackQueue? = null
        set(value) {
            field = value
            value?.let {
                it.ensureEntryIds()
                _queueSourceLiveData.postValue(it.sourceType)
            }
            publishQueue()
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

    /**
     * Current Resonant Radio session (autoplay seed) or `null` when the
     * queue is playing user-selected content only.  Observed by the UI
     * to show "Resonant Radio · Basado en X" in the mini player.
     */
    private val _radioSession = MutableLiveData<RadioSession?>(null)
    val radioSessionLiveData: LiveData<RadioSession?> = _radioSession

    fun setRadioSession(session: RadioSession?) {
        if (_radioSession.value != session) {
            _radioSession.postValue(session)
        }
    }

    fun updatePlaybackPosition(position: Long, duration: Int) {
        // Solo actualizamos si hay un cambio real para ser eficientes
        if (_playbackPosition.value?.position != position || _playbackPosition.value?.duration?.toInt() != duration) {
            _playbackPosition.postValue(PlaybackPosition(position, duration))
        }
    }

    fun setCurrentSong(song: Song?) {
        if (currentSong != song) {
            val songChanged = currentSong?.id != song?.id
            currentSong = song
            _currentSongLiveData.postValue(song)
            if (song != null && songChanged) {
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
            publishQueue(shuffleOverride = enabled)
        }
    }

    fun publishQueue(shuffleOverride: Boolean? = null) {
        val queue = activeQueue
        if (queue == null || queue.songs.isEmpty()) {
            _queueSnapshotLiveData.postValue(null)
            return
        }
        queue.ensureEntryIds()
        val revision = queueRevision.incrementAndGet()
        val snapshot = QueueSnapshot(
            revision = revision,
            sourceId = queue.sourceId,
            sourceType = queue.sourceType,
            currentIndex = queue.currentIndex.coerceIn(queue.songs.indices),
            items = queue.songs.mapIndexed { index, song ->
                QueueItemSnapshot(
                    entryId = queue.entryIds[index],
                    song = song,
                    queueIndex = index,
                    isCurrent = index == queue.currentIndex
                )
            },
            shuffleEnabled = shuffleOverride ?: (_isShuffleEnabled.value ?: false)
        )
        _queueSnapshotLiveData.postValue(snapshot)
    }

    fun currentQueueRevision(): Long = queueRevision.get()

    /**
     * Publishes a restored session as one coherent UI snapshot. The song is
     * emitted last so observers never render it with stale artwork or progress.
     */
    @MainThread
    fun restore(restored: RestoredPlaybackState) {
        val song = restored.queue.songs.getOrNull(restored.queue.currentIndex) ?: return
        activeQueue = restored.queue
        currentSong = song
        isPlaying = false
        streamReported = false

        _queueSourceLiveData.value = restored.queue.sourceType
        _currentSongBitmap.value = null
        _playbackPosition.value = PlaybackPosition(
            position = restored.positionMs,
            duration = restored.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        )
        _repeatMode.value = restored.repeatMode
        _isShuffleEnabled.value = restored.shuffleEnabled
        _isPlayingLiveData.value = false
        _currentSongLiveData.value = song
        publishQueue(shuffleOverride = restored.shuffleEnabled)
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

    @MainThread
    fun reset() {
        activeQueue = null
        currentSong = null
        isPlaying = false
        streamReported = false
        _queueSourceLiveData.value = QueueSource.UNKNOWN
        _currentSongBitmap.value = null
        _playbackPosition.value = PlaybackPosition(0L, 0)
        _isPlayingLiveData.value = false
        _currentSongLiveData.value = null
        _queueSnapshotLiveData.value = null
    }
}
