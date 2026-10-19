package com.example.resonant.playback

import com.example.resonant.data.models.Song
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Persistencia de las mezclas de Playmix de la cola en el estado de reproducción (collection-queue-offline D3). */
class QueueMixPersistenceTest {

    private val gson = Gson()
    private val codec = PlaybackStateCodec(gson = gson, nowEpochMs = { 10_000L }, maxStateAgeMs = 5_000L)

    private fun transition(id: String) = PlaymixTransitionDTO(
        id = id, fromPlaymixSongId = "x", toPlaymixSongId = "y", exitPointMs = 1, entryPointMs = 0,
        crossfadeDurationMs = 1_000, fadeCurveType = "linear", eqSettings = null, compatibility = null
    )

    private fun song(id: String) = Song(id = id, title = id, duration = "180", url = null, coverUrl = null, artistName = "Artist")

    private fun encode(queue: PlaybackQueue) = codec.encode(
        ownerUserId = "user-1",
        queue = queue,
        currentSongId = queue.songs[queue.currentIndex].id,
        positionMs = 0L,
        durationMs = 180_000L,
        repeatMode = PlaybackStateRepository.REPEAT_MODE_OFF,
        shuffleEnabled = false
    )

    @Test
    fun `queue pairs survive a save and restore without their dto`() {
        val queue = PlaybackQueue(
            sourceId = "manual_queue",
            sourceType = QueueSource.QUEUE,
            songs = listOf(song("X"), song("A"), song("B"), song("Y")),
            currentIndex = 0,
            entryIds = listOf("e0", "e1", "e2", "e3")
        )
        queue.mixIndex.register("pm", listOf("e1", "e2"), listOf("A", "B"), mapOf("A:B" to transition("tAB")))

        val restored = codec.decode(encode(queue), currentUserId = "user-1")!!.queue

        assertEquals(listOf("e0", "e1", "e2", "e3"), restored.entryIds)
        assertEquals(setOf("pm"), restored.mixIndex.playmixIds())
        assertEquals("tAB", restored.mixIndex.designedMixFor("e1", "e2")?.transitionId)
        assertTrue(restored.mixIndex.needsTransitions("pm"))
        assertNull(restored.mixIndex.designedTransitionFor("e1", "e2"))
    }

    @Test
    fun `queue without playmix blocks stores no pairs and old states restore empty`() {
        val queue = PlaybackQueue(
            sourceId = "manual_queue",
            sourceType = QueueSource.QUEUE,
            songs = listOf(song("X"), song("Y")),
            currentIndex = 0
        )
        val json = encode(queue)
        assertTrue(!json.contains("queueMixPairs"))
        assertTrue(codec.decode(json, currentUserId = "user-1")!!.queue.mixIndex.isEmpty())
    }
}
