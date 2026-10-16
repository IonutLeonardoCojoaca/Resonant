package com.example.resonant.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamOutboxPolicyTest {

    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    private fun pending(id: String, playedAtMs: Long, userId: String = "u1") = PendingStream(
        clientEventId = id,
        userId = userId,
        songId = "song-$id",
        listenDurationInSeconds = 120,
        wasSkipped = false,
        playSource = "PLAYLIST",
        playedAtUtc = "2026-10-15T18:42:10Z",
        playedAtMs = playedAtMs
    )

    @Test
    fun `network errors timeouts and 5xx are retried`() {
        assertEquals(StreamSendOutcome.RETRY, StreamOutboxPolicy.classify(null))
        assertEquals(StreamSendOutcome.RETRY, StreamOutboxPolicy.classify(500))
        assertEquals(StreamSendOutcome.RETRY, StreamOutboxPolicy.classify(503))
        assertEquals(StreamSendOutcome.RETRY, StreamOutboxPolicy.classify(408))
        assertEquals(StreamSendOutcome.RETRY, StreamOutboxPolicy.classify(429))
    }

    @Test
    fun `2xx is sent and other 4xx are dropped`() {
        assertEquals(StreamSendOutcome.SENT, StreamOutboxPolicy.classify(200))
        assertEquals(StreamSendOutcome.SENT, StreamOutboxPolicy.classify(204))
        assertEquals(StreamSendOutcome.DROP, StreamOutboxPolicy.classify(400))
        assertEquals(StreamSendOutcome.DROP, StreamOutboxPolicy.classify(404))
        assertEquals(StreamSendOutcome.DROP, StreamOutboxPolicy.classify(401))
    }

    @Test
    fun `items older than seven days expire`() {
        val items = listOf(pending("old", now - 8 * day), pending("recent", now - 2 * day))

        val pruned = StreamOutboxPolicy.prune(items, now)

        assertEquals(listOf("recent"), pruned.map { it.clientEventId })
    }

    @Test
    fun `above the cap the oldest are dropped first`() {
        var items = emptyList<PendingStream>()
        for (i in 0 until StreamOutboxPolicy.MAX_ITEMS + 5) {
            items = StreamOutboxPolicy.enqueue(items, pending("e$i", now - 1_000_000L + i), now)
        }

        assertEquals(StreamOutboxPolicy.MAX_ITEMS, items.size)
        assertEquals("e5", items.first().clientEventId)
        assertEquals("e204", items.last().clientEventId)
    }

    @Test
    fun `items are sent oldest first and the same event is not duplicated`() {
        var items = StreamOutboxPolicy.enqueue(emptyList(), pending("b", now - 1_000L), now)
        items = StreamOutboxPolicy.enqueue(items, pending("a", now - 5_000L), now)
        items = StreamOutboxPolicy.enqueue(items, pending("b", now - 1_000L), now)

        assertEquals(listOf("a", "b"), items.map { it.clientEventId })
    }

    @Test
    fun `due only returns the current user's items`() {
        val items = listOf(pending("mine", now - 1_000L, "u1"), pending("other", now - 500L, "u2"))

        assertEquals(listOf("mine"), StreamOutboxPolicy.due(items, "u1", now).map { it.clientEventId })
    }

    @Test
    fun `pending keeps the same client event id and time in its body`() {
        val dto = pending("7b1d", now).toDto()

        assertEquals("7b1d", dto.clientEventId)
        assertEquals("2026-10-15T18:42:10Z", dto.playedAtUtc)
        assertTrue(dto.listenDurationInSeconds == 120)
    }
}
