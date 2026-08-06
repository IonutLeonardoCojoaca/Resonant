package com.example.resonant.playback

import android.content.Context
import com.example.resonant.data.models.Song
import com.google.gson.Gson

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
    fun encode(
        ownerUserId: String,
        queue: PlaybackQueue,
        currentSongId: String?,
        positionMs: Long,
        durationMs: Long,
        repeatMode: Int,
        shuffleEnabled: Boolean
    ): String {
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

        return gson.toJson(
            PersistedPlaybackState(
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
        )
    }

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
    }
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val codec = PlaybackStateCodec(gson, nowEpochMs, MAX_STATE_AGE_MS)

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
        val editor = preferences.edit().putString(KEY_STATE, json)
        if (synchronous) editor.commit() else editor.apply()
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
        preferences.edit().remove(KEY_STATE).commit()
    }

    companion object {
        private const val PREFERENCES_NAME = "playback_resumption"
        private const val USER_PREFERENCES_NAME = "user_data"
        private const val KEY_STATE = "state"
        private const val KEY_USER_ID = "USER_ID"
        private const val MAX_STATE_AGE_MS = 30L * 24L * 60L * 60L * 1000L
    }
}
