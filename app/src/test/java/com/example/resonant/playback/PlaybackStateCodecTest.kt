package com.example.resonant.playback

import com.example.resonant.data.models.Song
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `unisound queues are never persistable`() {
        assertEquals(false, PlaybackStateCodec.isPersistable(QueueSource.UNISOUND))
        assertEquals(true, PlaybackStateCodec.isPersistable(QueueSource.PLAYLIST))
    }

    @Test
    fun `persisted unisound snapshot is not restored`() {
        val json = gson.toJson(
            PersistedPlaybackState(
                ownerUserId = "user-1",
                sourceId = "c1f3a2de-6a0b-4b8e-9d7e-2f0c4a9b1e55",
                sourceType = QueueSource.UNISOUND.name,
                songs = listOf(song("song-a", "A")),
                currentIndex = 0,
                savedAtEpochMs = nowMs
            )
        )

        assertNull(codec.decode(json, currentUserId = "user-1"))
    }

    @Test
    fun `flow marks, seed and station survive a round trip`() {
        val queue = PlaybackQueue(
            sourceId = "album-1",
            sourceType = QueueSource.ALBUM,
            songs = listOf(song("song-a", "A")),
            currentIndex = 0
        )
        queue.appendFlowSongs(listOf(song("flow-1", "F1"), song("flow-2", "F2")), mapOf("flow-1" to "Parecido a A"))
        queue.sourceType = QueueSource.QUEUE
        queue.sourceId = "manual_queue"
        val session = RadioSession("radio-1", "artist", "Artista", "artist-1", "Basado en Artista")

        val restored = codec.decode(
            json = codec.encode(
                ownerUserId = "user-1",
                queue = queue,
                currentSongId = "flow-1",
                positionMs = 0L,
                durationMs = 180_000L,
                repeatMode = PlaybackStateRepository.REPEAT_MODE_OFF,
                shuffleEnabled = false,
                flowSession = FlowSessionSnapshot(session, listOf("song-a", "flow-1"))
            ),
            currentUserId = "user-1"
        )!!

        assertEquals(listOf(false, true, true), restored.queue.songs.indices.map(restored.queue::isFlowEntry))
        assertEquals("Parecido a A", restored.queue.flowReasons["flow-1"])
        assertEquals(QueueSource.ALBUM, restored.queue.flowSeedSourceType)
        assertEquals("album-1", restored.queue.flowSeedSourceId)
        assertEquals(session, restored.flowSession?.radioSession)
        assertEquals(listOf("song-a", "flow-1"), restored.flowSession?.recentSongIds)
    }

    @Test
    fun `legacy snapshot without flow fields restores as before`() {
        val legacyJson = """
            {"schemaVersion":2,"ownerUserId":"user-1","sourceId":"home","sourceType":"HOME",
             "songs":[{"id":"song-a","title":"A","duration":"180"},{"id":"song-b","title":"B","duration":"180"}],
             "currentIndex":1,"positionMs":1000,"durationMs":180000,"repeatMode":0,
             "shuffleEnabled":false,"savedAtEpochMs":$nowMs}
        """.trimIndent()

        val restored = codec.decode(legacyJson, currentUserId = "user-1")!!

        assertEquals(1, restored.queue.currentIndex)
        assertEquals(2, restored.queue.entryIds.toSet().size)
        assertTrue(restored.queue.flowEntryIds.isEmpty())
        assertEquals(QueueSource.HOME, restored.queue.flowSeedSourceType)
        assertNull(restored.flowSession)
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
