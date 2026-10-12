package com.example.resonant.playback

import com.example.resonant.data.models.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueIdentityTest {
    @Test
    fun `duplicate songs receive distinct queue entry identities`() {
        val repeated = Song(id = "same-song", title = "Repeated")
        val queue = PlaybackQueue(
            sourceId = "manual",
            sourceType = QueueSource.QUEUE,
            songs = listOf(repeated, repeated.copy()),
            currentIndex = 0
        )

        assertEquals(2, queue.entryIds.size)
        assertNotEquals(queue.entryIds[0], queue.entryIds[1])
    }

    @Test
    fun `invalid persisted identities are rebuilt atomically`() {
        val queue = PlaybackQueue(
            sourceId = "album",
            sourceType = QueueSource.ALBUM,
            songs = listOf(
                Song(id = "one"),
                Song(id = "two"),
                Song(id = "three")
            ),
            currentIndex = 1,
            entryIds = listOf("stale")
        )

        queue.ensureEntryIds()

        assertEquals(queue.songs.size, queue.entryIds.size)
        assertTrue(queue.entryIds.all(String::isNotBlank))
        assertEquals(queue.entryIds.size, queue.entryIds.toSet().size)
    }

    @Test
    fun `flow marks follow their entries when moved and vanish when removed`() {
        val queue = PlaybackQueue(
            sourceId = "album",
            sourceType = QueueSource.ALBUM,
            songs = listOf(Song(id = "one"), Song(id = "two")),
            currentIndex = 0
        )
        queue.appendFlowSongs(listOf(Song(id = "f1"), Song(id = "f2")), mapOf("f1" to "Parecido a X"))

        val movedEntry = queue.entryIds[3]
        queue.songs = listOf(queue.songs[0], queue.songs[3], queue.songs[1], queue.songs[2])
        queue.entryIds = listOf(queue.entryIds[0], movedEntry, queue.entryIds[1], queue.entryIds[2])

        assertTrue(queue.isFlowEntry(1))
        assertFalse(queue.isFlowEntry(2))
        assertEquals(setOf("f1", "f2"), queue.flowSongIds())
        assertEquals("Parecido a X", queue.flowReasons["f1"])

        queue.songs = queue.songs.filterIndexed { index, _ -> index != 1 }
        queue.entryIds = queue.entryIds.filterIndexed { index, _ -> index != 1 }
        queue.pruneFlowEntries()

        assertEquals(setOf("f1"), queue.flowSongIds())
        assertEquals(1, queue.flowEntryIds.size)
    }

    @Test
    fun `flow marks survive a rebuild of entry identities`() {
        val queue = PlaybackQueue(
            sourceId = "album",
            sourceType = QueueSource.ALBUM,
            songs = listOf(Song(id = "one")),
            currentIndex = 0
        )
        queue.appendFlowSongs(listOf(Song(id = "f1")))
        val flowSongs = queue.flowSongIds()

        queue.resetEntriesForSongs()
        queue.remarkFlowSongs(flowSongs)

        assertFalse(queue.isFlowEntry(0))
        assertTrue(queue.isFlowEntry(1))
    }

    @Test
    fun `seed source is kept when the queue becomes manual`() {
        val queue = PlaybackQueue(
            sourceId = "album-1",
            sourceType = QueueSource.ALBUM,
            songs = listOf(Song(id = "one")),
            currentIndex = 0
        )

        queue.sourceType = QueueSource.QUEUE
        queue.sourceId = "manual_queue"

        assertEquals(QueueSource.ALBUM, queue.flowSeedSourceType)
        assertEquals("album-1", queue.flowSeedSourceId)
    }
}
