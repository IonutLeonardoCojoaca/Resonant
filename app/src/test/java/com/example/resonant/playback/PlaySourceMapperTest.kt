package com.example.resonant.playback

import com.example.resonant.data.models.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaySourceMapperTest {

    @Test
    fun `legacy queue sources map to canonical values`() {
        assertEquals("HOME_OTHER", PlaySourceMapper.canonical(QueueSource.HOME))
        assertEquals("FAVORITES", PlaySourceMapper.canonical(QueueSource.FAVORITE_SONGS))
        assertEquals("ARTIST", PlaySourceMapper.canonical(QueueSource.TOP_SONGS_ARTIST))
        assertEquals("RELATED", PlaySourceMapper.canonical(QueueSource.RELATED_SONGS))
        assertEquals("OTHER", PlaySourceMapper.canonical(QueueSource.COLLAB_FINDER))
        assertEquals("OTHER", PlaySourceMapper.canonical(QueueSource.DOWNLOADED_SONGS))
    }

    @Test
    fun `other sources keep their name`() {
        listOf(
            QueueSource.ALBUM, QueueSource.PLAYLIST, QueueSource.SEARCH, QueueSource.PLAYMIX,
            QueueSource.QUEUE, QueueSource.UNISOUND, QueueSource.EXPLORE, QueueSource.ARIA_PICK,
            QueueSource.LIVE, QueueSource.SONG_OF_DAY, QueueSource.STATS, QueueSource.UNKNOWN
        ).forEach { assertEquals(it.name, PlaySourceMapper.canonical(it)) }
        assertEquals("UNKNOWN", PlaySourceMapper.canonical(null))
    }

    @Test
    fun `attribution follows the entry when the queue is reordered`() {
        val queue = PlaybackQueue("", QueueSource.HOME, listOf(Song(id = "a"), Song(id = "b")), 0)
        queue.attributeAll(StreamAttribution("req-1", PlaySourceMapper.HOME_FOR_YOU))
        queue.songs = queue.songs.reversed()
        queue.entryIds = queue.entryIds.reversed()

        assertEquals("req-1", queue.attributionForSong("b", 0)?.requestId)
        assertEquals(PlaySourceMapper.HOME_FOR_YOU, queue.attributionAt(1)?.playSource)
    }

    @Test
    fun `attribution survives rebuilt entries and radio batches get their own`() {
        val queue = PlaybackQueue("", QueueSource.ALBUM, listOf(Song(id = "a")), 0)
        assertNull(queue.attributionForSong("a", 0))

        queue.appendSongs(listOf(Song(id = "r1"), Song(id = "r2")))
        queue.attributeLast(2, StreamAttribution("batch-1", PlaySourceMapper.RADIO))
        queue.songs = queue.songs.shuffled()
        queue.resetEntriesForSongs()

        assertNull(queue.attributionForSong("a", 0))
        assertEquals("batch-1", queue.attributionForSong("r2", 0)?.requestId)
        assertEquals(PlaySourceMapper.RADIO, queue.attributionForSong("r1", 0)?.playSource)
    }
}
