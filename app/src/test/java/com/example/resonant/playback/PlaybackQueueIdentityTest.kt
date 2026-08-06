package com.example.resonant.playback

import com.example.resonant.data.models.Song
import org.junit.Assert.assertEquals
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
}

