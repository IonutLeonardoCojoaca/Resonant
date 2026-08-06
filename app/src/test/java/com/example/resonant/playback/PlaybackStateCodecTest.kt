package com.example.resonant.playback

import com.example.resonant.data.models.Song
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackStateCodecTest {

    private val gson = Gson()
    private var nowMs = 10_000L
    private val codec = PlaybackStateCodec(
        gson = gson,
        nowEpochMs = { nowMs },
        maxStateAgeMs = 5_000L
    )

    @Test
    fun `current media id synchronizes persisted queue index`() {
        val queue = PlaybackQueue(
            sourceId = "playlist-1",
            sourceType = QueueSource.PLAYLIST,
            songs = listOf(
                song("song-a", "A"),
                song("song-b", "B", url = "https://signed.example/b.mp3")
            ),
            currentIndex = 0
        )

        val restored = codec.decode(
            json = codec.encode(
                ownerUserId = "user-1",
                queue = queue,
                currentSongId = "song-b",
                positionMs = 12_345L,
                durationMs = 180_000L,
                repeatMode = PlaybackStateRepository.REPEAT_MODE_ALL,
                shuffleEnabled = true
            ),
            currentUserId = "user-1"
        )!!

        assertEquals(1, restored.queue.currentIndex)
        assertEquals("song-b", restored.queue.songs[restored.queue.currentIndex].id)
        assertEquals(12_345L, restored.positionMs)
        assertEquals(180_000L, restored.durationMs)
        assertNull(restored.queue.songs[1].url)
    }

    @Test
    fun `legacy snapshot derives duration from song seconds`() {
        val legacyJson = gson.toJson(
            PersistedPlaybackState(
                ownerUserId = null,
                sourceId = "home",
                sourceType = QueueSource.HOME.name,
                songs = listOf(song("song-a", "A", durationSeconds = "221")),
                currentIndex = 0,
                positionMs = 14_703L,
                durationMs = 0L,
                savedAtEpochMs = nowMs
            )
        )

        val restored = codec.decode(legacyJson, currentUserId = "user-1")!!

        assertEquals(221_000L, restored.durationMs)
        assertEquals(14_703L, restored.positionMs)
    }

    @Test
    fun `snapshot from another account is rejected`() {
        val json = codec.encode(
            ownerUserId = "user-1",
            queue = PlaybackQueue(
                sourceId = "home",
                sourceType = QueueSource.HOME,
                songs = listOf(song("song-a", "A")),
                currentIndex = 0
            ),
            currentSongId = "song-a",
            positionMs = 0L,
            durationMs = 180_000L,
            repeatMode = PlaybackStateRepository.REPEAT_MODE_OFF,
            shuffleEnabled = false
        )

        assertNull(codec.decode(json, currentUserId = "user-2"))
    }

    @Test
    fun `expired snapshot is rejected`() {
        val json = codec.encode(
            ownerUserId = "user-1",
            queue = PlaybackQueue(
                sourceId = "home",
                sourceType = QueueSource.HOME,
                songs = listOf(song("song-a", "A")),
                currentIndex = 0
            ),
            currentSongId = "song-a",
            positionMs = 0L,
            durationMs = 180_000L,
            repeatMode = PlaybackStateRepository.REPEAT_MODE_OFF,
            shuffleEnabled = false
        )
        nowMs += 5_001L

        assertNull(codec.decode(json, currentUserId = "user-1"))
    }

    private fun song(
        id: String,
        title: String,
        durationSeconds: String = "180",
        url: String? = null
    ) = Song(
        id = id,
        title = title,
        duration = durationSeconds,
        url = url,
        coverUrl = "https://images.example/$id.jpg",
        artistName = "Artist"
    )
}
