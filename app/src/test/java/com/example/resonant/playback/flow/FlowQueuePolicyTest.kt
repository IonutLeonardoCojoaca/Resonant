package com.example.resonant.playback.flow

import com.example.resonant.data.models.ArtistSimpleDTO
import com.example.resonant.data.models.Song
import com.example.resonant.playback.PlaybackStateRepository
import com.example.resonant.playback.QueueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowQueuePolicyTest {

    private val off = PlaybackStateRepository.REPEAT_MODE_OFF

    @Test
    fun `non personal queues and repeat all are not eligible`() {
        assertFalse(FlowQueuePolicy.isEligible(QueueSource.UNISOUND, off, enabled = true))
        assertFalse(FlowQueuePolicy.isEligible(QueueSource.PLAYMIX, off, enabled = true))
        assertFalse(FlowQueuePolicy.isEligible(QueueSource.DOWNLOADED_SONGS, off, enabled = true))
        assertFalse(
            FlowQueuePolicy.isEligible(QueueSource.ALBUM, PlaybackStateRepository.REPEAT_MODE_ALL, enabled = true)
        )
        assertFalse(FlowQueuePolicy.isEligible(QueueSource.ALBUM, off, enabled = false))
        assertTrue(FlowQueuePolicy.isEligible(QueueSource.ALBUM, off, enabled = true))
        assertTrue(
            FlowQueuePolicy.isEligible(QueueSource.QUEUE, PlaybackStateRepository.REPEAT_MODE_ONE, enabled = true)
        )
    }

    @Test
    fun `prefetch starts with three songs left and only once`() {
        assertFalse(FlowQueuePolicy.shouldPrefetch(currentIndex = 0, queueSize = 5, inFlight = false))
        assertTrue(FlowQueuePolicy.shouldPrefetch(currentIndex = 1, queueSize = 5, inFlight = false))
        assertTrue(FlowQueuePolicy.shouldPrefetch(currentIndex = 4, queueSize = 5, inFlight = false))
        assertFalse(FlowQueuePolicy.shouldPrefetch(currentIndex = 4, queueSize = 5, inFlight = true))
        assertFalse(FlowQueuePolicy.shouldPrefetch(currentIndex = 0, queueSize = 0, inFlight = false))
    }

    @Test
    fun `late responses are discarded when the generation changed or flow is no longer eligible`() {
        assertTrue(FlowQueuePolicy.shouldApply(requestGeneration = 3, currentGeneration = 3, eligibleNow = true))
        assertFalse(FlowQueuePolicy.shouldApply(requestGeneration = 3, currentGeneration = 4, eligibleNow = true))
        assertFalse(FlowQueuePolicy.shouldApply(requestGeneration = 3, currentGeneration = 3, eligibleNow = false))
    }

    @Test
    fun `add goes before the first upcoming flow entry`() {
        val entries = listOf("a", "b", "c", "f1", "f2")
        val flow = setOf("f1", "f2")

        assertEquals(3, FlowQueuePolicy.insertionIndexForAdd(entries, flow, currentIndex = 0))
        assertEquals(3, FlowQueuePolicy.insertionIndexForAdd(entries, flow, currentIndex = 2))
    }

    @Test
    fun `add without flow or while playing flow goes to the end`() {
        val entries = listOf("a", "b", "f1", "f2")

        assertEquals(4, FlowQueuePolicy.insertionIndexForAdd(entries, emptySet(), currentIndex = 0))
        assertEquals(4, FlowQueuePolicy.insertionIndexForAdd(entries, setOf("f1", "f2"), currentIndex = 3))
    }

    @Test
    fun `batch drops songs already queued, duplicates and blank ids`() {
        val batch = listOf(song("x"), song("a"), song("y"), song("x"), song(""))

        val appended = FlowQueuePolicy.songsToAppend(listOf("a", "b"), batch)

        assertEquals(listOf("x", "y"), appended.map { it.id })
    }

    @Test
    fun `shuffle keeps the flow block at the end in order`() {
        val items = listOf("u1", "f1", "u2", "f2", "u3")

        val result = FlowQueuePolicy.reorderPreservingFlow(items, { it.startsWith("f") }) { it.reversed() }

        assertEquals(listOf("u3", "u2", "u1", "f1", "f2"), result)
    }

    @Test
    fun `queue without flow is unchanged by the flow-preserving reorder`() {
        val items = listOf("u1", "u2", "u3")

        assertEquals(items, FlowQueuePolicy.reorderPreservingFlow(items, { false }) { it })
    }

    @Test
    fun `session songs go from the start of the queue to the current one without duplicates`() {
        val songs = listOf(song("a"), song("b"), song("a"), song("c"), song("d"))

        assertEquals(listOf("a", "b", "c"), FlowQueuePolicy.sessionSongIds(songs, currentIndex = 3))
    }

    @Test
    fun `feedback only affects upcoming flow entries of the target`() {
        val songs = listOf(
            song("a", "art-1"), song("b", "art-2"), song("f1", "art-2"), song("f2", "art-3"), song("f3", "art-2")
        )
        val flow = setOf(2, 3, 4)

        val artist = FlowQueuePolicy.upcomingFlowMatches(songs, flow::contains, 0, FlowQueuePolicy.TARGET_ARTIST, "art-2")
        val single = FlowQueuePolicy.upcomingFlowMatches(songs, flow::contains, 0, FlowQueuePolicy.TARGET_SONG, "f2")
        val behindCurrent = FlowQueuePolicy.upcomingFlowMatches(songs, flow::contains, 3, FlowQueuePolicy.TARGET_ARTIST, "art-2")

        assertEquals(listOf(2, 4), artist)
        assertEquals(listOf(3), single)
        assertEquals(listOf(4), behindCurrent)
    }

    @Test
    fun `undone entries go back to their place while still ahead`() {
        assertEquals(3, FlowQueuePolicy.restoreIndex(originalIndex = 3, currentIndex = 1, queueSize = 5))
        assertEquals(3, FlowQueuePolicy.restoreIndex(originalIndex = 2, currentIndex = 2, queueSize = 5))
        assertEquals(4, FlowQueuePolicy.restoreIndex(originalIndex = 9, currentIndex = 1, queueSize = 4))
    }

    private fun song(id: String) = Song(id = id, title = id)

    private fun song(id: String, artistId: String) =
        Song(id = id, title = id, artists = listOf(ArtistSimpleDTO(id = artistId, name = artistId)))
}
