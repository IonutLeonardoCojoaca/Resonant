package com.example.resonant.playback

import android.content.Context
import com.example.resonant.data.models.Song
import com.google.gson.Gson
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class RestoredPlaybackState(
    val queue: PlaybackQueue,
    val positionMs: Long,
    val durationMs: Long,
    val repeatMode: Int,
    val shuffleEnabled: Boolean
)

internal data class PersistedPlaybackState(
    val schemaVersion: Int = 0,
    val ownerUserId: String? = null,
    val sourceId: String? = null,
    val sourceType: String? = null,
    val songs: List<Song>? = null,
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeatMode: Int = PlaybackStateRepository.REPEAT_MODE_OFF,
    val shuffleEnabled: Boolean = false,
    val savedAtEpochMs: Long = 0L
)

internal class PlaybackStateCodec(
    private val gson: Gson,
    private val nowEpochMs: () -> Long,
    private val maxStateAgeMs: Long
) {
    /**
     * Builds the immutable snapshot synchronously. Must be called from the
     * thread that owns [queue] (Main, in MusicPlaybackService) — everything
     * mutable it touches (queue.songs, each Song's `var` fields) is read
     * here and only here. [Song.withoutExpiringPlaybackUrl] already returns
     * a `copy()`, so the resulting [PersistedPlaybackState] shares no
     * mutable state with the live queue and is safe to hand to another
     * thread for the expensive JSON encoding.
     */
    fun buildSnapshot(
        ownerUserId: String,
        queue: PlaybackQueue,
        currentSongId: String?,
        positionMs: Long,
        durationMs: Long,
        repeatMode: Int,
        shuffleEnabled: Boolean
    ): PersistedPlaybackState {
        val songs = queue.songs.map { it.withoutExpiringPlaybackUrl() }
        val synchronizedIndex = currentSongId
            ?.let { id -> songs.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: queue.currentIndex.coerceIn(songs.indices)
        val safeDurationMs = durationMs.coerceAtLeast(0L)
        val safePositionMs = positionMs
            .coerceAtLeast(0L)
            .let { position ->
                if (safeDurationMs > 0L) position.coerceAtMost(safeDurationMs) else position
            }

        return PersistedPlaybackState(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            ownerUserId = ownerUserId,
            sourceId = queue.sourceId,
            sourceType = queue.sourceType.name,
            songs = songs,
            currentIndex = synchronizedIndex,
            positionMs = safePositionMs,
            durationMs = safeDurationMs,
            repeatMode = repeatMode,
            shuffleEnabled = shuffleEnabled,
            savedAtEpochMs = nowEpochMs()
        )
    }

    fun encode(
        ownerUserId: String,
        queue: PlaybackQueue,
        currentSongId: String?,
        positionMs: Long,
        durationMs: Long,
        repeatMode: Int,
        shuffleEnabled: Boolean
    ): String = gson.toJson(
        buildSnapshot(
            ownerUserId, queue, currentSongId, positionMs, durationMs, repeatMode, shuffleEnabled
        )
    )

    fun decode(json: String, currentUserId: String): RestoredPlaybackState? {
        val persisted = runCatching {
            gson.fromJson(json, PersistedPlaybackState::class.java)
        }.getOrNull() ?: return null
        val songs = persisted.songs.orEmpty()
        if (songs.isEmpty() || persisted.savedAtEpochMs <= 0L) return null

        val persistedOwner = persisted.ownerUserId?.takeIf { it.isNotBlank() }
        if (persistedOwner != null && persistedOwner != currentUserId) return null

        val ageMs = nowEpochMs() - persisted.savedAtEpochMs
        if (ageMs > maxStateAgeMs) return null

        val source = runCatching {
            QueueSource.valueOf(persisted.sourceType.orEmpty())
        }.getOrDefault(QueueSource.UNKNOWN)
        val index = persisted.currentIndex.coerceIn(songs.indices)
        val currentSong = songs[index]
        val durationMs = persisted.durationMs
            .takeIf { it > 0L }
            ?: currentSong.knownDurationMs()
        val positionMs = persisted.positionMs
            .coerceAtLeast(0L)
            .let { position ->
                if (durationMs > 0L) position.coerceAtMost(durationMs) else position
            }

        return RestoredPlaybackState(
            queue = PlaybackQueue(
                sourceId = persisted.sourceId.orEmpty(),
                sourceType = source,
                songs = songs,
                currentIndex = index
            ),
            positionMs = positionMs,
            durationMs = durationMs,
            repeatMode = persisted.repeatMode.coerceIn(
                PlaybackStateRepository.REPEAT_MODE_OFF,
                PlaybackStateRepository.REPEAT_MODE_ALL
            ),
            shuffleEnabled = persisted.shuffleEnabled
        )
    }

    private fun Song.knownDurationMs(): Long {
        val analysisDuration = audioAnalysis?.durationMs?.toLong() ?: 0L
        if (analysisDuration > 0L) return analysisDuration
        return duration
            ?.toDoubleOrNull()
            ?.takeIf { it > 0.0 }
            ?.times(1_000.0)
            ?.toLong()
            ?: 0L
    }

    private companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}

internal fun Song.withoutExpiringPlaybackUrl(): Song {
    val sanitizedUrl = url?.takeUnless {
        it.startsWith("https://", ignoreCase = true) ||
            it.startsWith("http://", ignoreCase = true)
    }
    return copy(url = sanitizedUrl)
}

class PlaybackStateStore(
    context: Context,
    private val gson: Gson = Gson(),
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val currentUserId: () -> String? = {
        context.applicationContext
            .getSharedPreferences(USER_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_USER_ID, null)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val codec = PlaybackStateCodec(gson, nowEpochMs, MAX_STATE_AGE_MS)

    // Guarantee writes apply in the order their snapshots were captured,
    // even though save()/saveAsync()/clear() can now race each other across
    // threads (Main for the synchronous onDestroy path, an IO-dispatched
    // coroutine for everything else). Each write is stamped with a
    // monotonically increasing sequence number at snapshot time (always on
    // Main, since every persistPlaybackState call site is Main-thread); the
    // actual disk write only applies if no higher-numbered write has landed
    // first, so a slow, stale write can never clobber a newer one.
    private val writeLock = Any()
    private val writeSequence = AtomicLong(0L)
    private var lastWrittenSequence = 0L

    fun save(
        queue: PlaybackQueue,
        currentSongId: String?,
        positionMs: Long,
        durationMs: Long,
        repeatMode: Int,
        shuffleEnabled: Boolean,
        synchronous: Boolean = false
    ) {
        if (queue.songs.isEmpty()) {
            clear()
            return
        }
        val ownerUserId = currentUserId()?.takeIf { it.isNotBlank() } ?: return

        val json = codec.encode(
            ownerUserId = ownerUserId,
            queue = queue,
            currentSongId = currentSongId,
            positionMs = positionMs,
            durationMs = durationMs,
            repeatMode = repeatMode,
            shuffleEnabled = shuffleEnabled
        )
        val sequence = writeSequence.incrementAndGet()
        synchronized(writeLock) {
            if (sequence < lastWrittenSequence) return
            val editor = preferences.edit().putString(KEY_STATE, json)
            if (synchronous) editor.commit() else editor.apply()
            lastWrittenSequence = sequence
        }
    }

    /**
     * Same contract as [save], but the expensive part — Gson-encoding the
     * snapshot and writing it to disk — runs on [scope] off the caller's
     * thread. The snapshot itself is still built synchronously, on the
     * caller's thread, before anything is dispatched: that's what makes it
     * safe to serialize later without racing a concurrent queue mutation.
     *
     * Always asynchronous ([apply], never [commit]) — callers that need a
     * durability guarantee before returning (onDestroy) must use [save]
     * with `synchronous = true` instead.
     */
    fun saveAsync(
        scope: CoroutineScope,
        queue: PlaybackQueue,
        currentSongId: String?,
        positionMs: Long,
        durationMs: Long,
        repeatMode: Int,
        shuffleEnabled: Boolean
    ) {
        if (queue.songs.isEmpty()) {
            clear()
            return
        }
        val ownerUserId = currentUserId()?.takeIf { it.isNotBlank() } ?: return

        val snapshot = codec.buildSnapshot(
            ownerUserId = ownerUserId,
            queue = queue,
            currentSongId = currentSongId,
            positionMs = positionMs,
            durationMs = durationMs,
            repeatMode = repeatMode,
            shuffleEnabled = shuffleEnabled
        )
        val sequence = writeSequence.incrementAndGet()
        scope.launch(ioDispatcher) {
            val json = gson.toJson(snapshot)
            synchronized(writeLock) {
                if (sequence < lastWrittenSequence) return@launch
                preferences.edit().putString(KEY_STATE, json).apply()
                lastWrittenSequence = sequence
            }
        }
    }

    fun restore(): RestoredPlaybackState? {
        val json = preferences.getString(KEY_STATE, null) ?: return null
        val ownerUserId = currentUserId()?.takeIf { it.isNotBlank() } ?: return null
        val restored = codec.decode(json, ownerUserId)
        if (restored == null) {
            clear()
        }
        return restored
    }

    fun clear() {
        val sequence = writeSequence.incrementAndGet()
        synchronized(writeLock) {
            if (sequence < lastWrittenSequence) return
            preferences.edit().remove(KEY_STATE).commit()
            lastWrittenSequence = sequence
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "playback_resumption"
        private const val USER_PREFERENCES_NAME = "user_data"
        private const val KEY_STATE = "state"
        private const val KEY_USER_ID = "USER_ID"
        private const val MAX_STATE_AGE_MS = 30L * 24L * 60L * 60L * 1000L
    }
}
