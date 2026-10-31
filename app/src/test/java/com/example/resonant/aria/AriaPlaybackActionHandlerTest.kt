package com.example.resonant.aria

import com.example.resonant.ui.viewmodels.AriaAction
import com.example.resonant.ui.viewmodels.AriaCollection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AriaPlaybackActionHandlerTest {
    private val validSongId = "95edf595-543b-4559-9778-0ac823824747"

    @Test
    fun `play_song plays clientSongId directly without a title lookup`() = runTest {
        val fixture = PlaybackFixture()

        fixture.handler.execute(
            action(
                command = "play_song",
                actionId = "play-action",
                songId = "server-field-must-not-win",
                clientSongId = validSongId,
                title = "Mayores"
            )
        )

        assertEquals(validSongId, fixture.gateway.playedSongId)
        assertEquals("Mayores", fixture.gateway.playedTitle)
        assertTrue(fixture.gateway.queuedSongIds.isEmpty())
        assertEquals("Reproduciendo Mayores", fixture.feedback.single().first)
        assertTrue(fixture.feedback.single().second)
    }

    @Test
    fun `queue_song sends exactly the received clientSongId without interrupting playback`() =
        runTest {
            val exactId = "95EDF595-543B-4559-9778-0AC823824747"
            val fixture = PlaybackFixture()
            fixture.gateway.currentSongId = "currently-playing"

            fixture.handler.execute(
                action(
                    command = "queue_song",
                    actionId = "queue-action",
                    songId = "ignored-song-id",
                    clientSongId = exactId
                )
            )

            assertEquals("currently-playing", fixture.gateway.currentSongId)
            assertNull(fixture.gateway.playedSongId)
            assertEquals(listOf(exactId), fixture.gateway.queuedSongIds)
        }

    @Test
    fun `repeated action_id does not execute twice`() = runTest {
        val fixture = PlaybackFixture()
        val duplicate = action(
            command = "play_song",
            actionId = "same-action-id",
            clientSongId = validSongId
        )

        fixture.handler.execute(duplicate.copy(logId = "first-sse-log"))
        fixture.handler.execute(duplicate.copy(logId = "reconnected-sse-log"))

        assertEquals(1, fixture.gateway.playCalls)
        assertEquals(listOf("success", "duplicate"), fixture.telemetry.events.map { it.outcome })
        assertEquals(1, fixture.feedback.size)
    }

    @Test
    fun `failed play_song emits no success message`() = runTest {
        val fixture = PlaybackFixture()
        fixture.gateway.playFailure = IllegalStateException("MediaSession rejected request")

        fixture.handler.execute(
            action(
                command = "play_song",
                actionId = "failed-action",
                clientSongId = validSongId,
                title = "Mayores"
            )
        )

        assertEquals(1, fixture.gateway.playCalls)
        assertEquals(listOf("No pude reproducir Mayores" to false), fixture.feedback)
        assertFalse(fixture.feedback.any { (message, success) ->
            success || message.startsWith("Reproduciendo")
        })
        assertEquals("error", fixture.telemetry.events.single().outcome)
    }

    @Test
    fun `missing clientSongId does not fall back to songId or title`() = runTest {
        val fixture = PlaybackFixture()

        fixture.handler.execute(
            action(
                command = "play_song",
                actionId = "missing-client-id",
                songId = validSongId,
                clientSongId = null,
                title = "Mayores"
            )
        )

        assertEquals(0, fixture.gateway.playCalls)
        assertTrue(fixture.gateway.queuedSongIds.isEmpty())
        assertEquals(AriaPlaybackActionHandler.REASON_MISSING_SONG_ID, fixture.lastReason())
        assertFalse(fixture.feedback.single().second)
    }

    @Test
    fun `non pending action is never executed`() = runTest {
        val fixture = PlaybackFixture()

        fixture.handler.execute(
            action(
                command = "play_song",
                actionId = "completed-action",
                clientSongId = validSongId
            ).copy(executionStatus = "completed")
        )

        assertEquals(0, fixture.gateway.playCalls)
        assertTrue(fixture.telemetry.events.isEmpty())
        assertTrue(fixture.feedback.isEmpty())
    }

    @Test
    fun `seleccionar_cancion needs_input never controls playback`() = runTest {
        val fixture = PlaybackFixture()
        val needsInput = AriaAction(
            type = "seleccionar_cancion",
            actionId = "clarification-action",
            executionStatus = "needs_input",
            clientSide = true,
            playbackCommand = "play_song",
            clientSongId = validSongId
        )

        fixture.handler.execute(needsInput)

        assertEquals(0, fixture.gateway.playCalls)
        assertTrue(fixture.gateway.controls.isEmpty())
        assertTrue(fixture.gateway.queuedSongIds.isEmpty())
        assertTrue(fixture.telemetry.events.isEmpty())
        assertTrue(fixture.feedback.isEmpty())
    }

    @Test
    fun `play pause next and previous confirm only after transport succeeds`() = runTest {
        val fixture = PlaybackFixture()

        listOf("play", "pause", "next", "previous").forEachIndexed { index, command ->
            fixture.handler.execute(
                action(command = command, actionId = "control-$index", clientSongId = null)
            )
        }

        assertEquals(
            listOf(
                AriaTransportControl.PLAY,
                AriaTransportControl.PAUSE,
                AriaTransportControl.NEXT,
                AriaTransportControl.PREVIOUS
            ),
            fixture.gateway.controls
        )
        assertEquals(
            listOf(
                "Reproducción reanudada",
                "Pausa aplicada",
                "Reproduciendo la siguiente canción",
                "Reproduciendo la canción anterior"
            ),
            fixture.feedback.map { it.first }
        )
        assertTrue(fixture.feedback.all { it.second })
    }

    @Test
    fun `favorite is confirmed only when manager returns true`() = runTest {
        val idempotency = FakeIdempotency()
        val feedback = mutableListOf<Pair<String, Boolean>>()
        val requestedIds = mutableListOf<String>()
        val handler = AriaFavoriteActionHandler(
            favoriteGateway = object : AriaFavoriteGateway {
                override suspend fun addFavoriteSong(songId: String): Boolean {
                    requestedIds += songId
                    return true
                }
                override suspend fun removeFavoriteSong(songId: String): Boolean = true
            },
            idempotency = idempotency,
            onFeedback = { message, success -> feedback += message to success }
        )

        handler.execute(favoriteAction("favorite-ok", validSongId, "Mayores"))

        assertEquals(listOf(validSongId), requestedIds)
        assertEquals(listOf("Mayores añadida a favoritos" to true), feedback)
    }

    @Test
    fun `favorite failure is not presented as success`() = runTest {
        val feedback = mutableListOf<Pair<String, Boolean>>()
        val requestedIds = mutableListOf<String>()
        val handler = AriaFavoriteActionHandler(
            favoriteGateway = object : AriaFavoriteGateway {
                override suspend fun addFavoriteSong(songId: String): Boolean {
                    requestedIds += songId
                    return false
                }
                override suspend fun removeFavoriteSong(songId: String): Boolean = false
            },
            idempotency = FakeIdempotency(),
            onFeedback = { message, success -> feedback += message to success }
        )

        handler.execute(favoriteAction("favorite-failed", validSongId, "Mayores"))

        assertEquals(listOf(validSongId), requestedIds)
        assertEquals(listOf("No pude añadir la canción a favoritos" to false), feedback)
        assertFalse(feedback.any { it.second })
    }

    @Test
    fun `favorite action_id is also deduplicated`() = runTest {
        val requestedIds = mutableListOf<String>()
        val feedback = mutableListOf<Pair<String, Boolean>>()
        val handler = AriaFavoriteActionHandler(
            favoriteGateway = object : AriaFavoriteGateway {
                override suspend fun addFavoriteSong(songId: String): Boolean {
                    requestedIds += songId
                    return true
                }
                override suspend fun removeFavoriteSong(songId: String): Boolean = true
            },
            idempotency = FakeIdempotency(),
            onFeedback = { message, success -> feedback += message to success }
        )
        val action = favoriteAction("same-favorite-id", validSongId, "Mayores")

        handler.execute(action.copy(logId = "first-connection"))
        handler.execute(action.copy(logId = "sse-reconnection"))

        assertEquals(listOf(validSongId), requestedIds)
        assertEquals(1, feedback.size)
    }

    @Test
    fun `play_collection plays Aria's selection in order`() = runTest {
        val fixture = PlaybackFixture()
        val second = "15edf595-543b-4559-9778-0ac823824748"

        fixture.handler.execute(
            collectionAction(AriaCollection("songs", title = "Becky G, selección de Aria", songIds = listOf(validSongId, second)))
        )

        assertEquals(listOf(validSongId, second), fixture.gateway.playedCollection?.songIds)
        assertEquals(0, fixture.gateway.playedStartIndex)
        assertEquals("Reproduciendo Becky G, selección de Aria", fixture.feedback.single().first)
    }

    @Test
    fun `play_collection plays a playlist by id with shuffle`() = runTest {
        val fixture = PlaybackFixture()

        fixture.handler.execute(
            collectionAction(AriaCollection("playlist", id = validSongId, title = "Gym"), shuffle = true)
        )

        assertEquals("playlist", fixture.gateway.playedCollection?.kind)
        assertEquals(validSongId, fixture.gateway.playedCollection?.id)
        assertEquals(true, fixture.gateway.playedShuffle)
    }

    @Test
    fun `play_collection without playable songs reports it and keeps the queue`() = runTest {
        val fixture = PlaybackFixture()
        fixture.gateway.collectionFailure = NoPlayableSongsException()

        fixture.handler.execute(collectionAction(AriaCollection("favorites", title = "Tus favoritas")))

        assertNull(fixture.gateway.playedCollection)
        assertFalse(fixture.feedback.single().second)
        assertEquals(AriaPlaybackActionHandler.REASON_NO_PLAYABLE_SONGS, fixture.lastReason())
    }

    @Test
    fun `play_collection rejects a playlist without a valid id`() = runTest {
        val fixture = PlaybackFixture()

        fixture.handler.execute(collectionAction(AriaCollection("playlist", id = "not-a-uuid", title = "Gym")))

        assertNull(fixture.gateway.playedCollection)
        assertEquals(AriaPlaybackActionHandler.REASON_INVALID_COLLECTION, fixture.lastReason())
    }

    @Test
    fun `play_collection runs once per action_id`() = runTest {
        val fixture = PlaybackFixture()
        val action = collectionAction(AriaCollection("favorites"))

        fixture.handler.execute(action)
        fixture.gateway.playedCollection = null
        fixture.handler.execute(action)

        assertNull(fixture.gateway.playedCollection)
    }

    @Test
    fun `queue_songs next inserts without interrupting playback`() = runTest {
        val fixture = PlaybackFixture()
        fixture.gateway.currentSongId = "currently-playing"

        fixture.handler.execute(
            action(command = "queue_songs", actionId = "queue-next").copy(
                queueSongIds = listOf(validSongId), queuePosition = "next"
            )
        )

        assertEquals("currently-playing", fixture.gateway.currentSongId)
        assertEquals(listOf(validSongId), fixture.gateway.queuedNext)
        assertEquals("Sonará a continuación", fixture.feedback.single().first)
    }

    private fun collectionAction(collection: AriaCollection, shuffle: Boolean = false) =
        action(command = "play_collection", actionId = "collection-${collection.kind}-${collection.id}").copy(
            collection = collection,
            collectionShuffle = shuffle
        )

    private fun action(
        command: String,
        actionId: String,
        songId: String? = null,
        clientSongId: String? = validSongId,
        title: String? = null
    ) = AriaAction(
        type = "controlar_reproduccion",
        actionId = actionId,
        executionStatus = ARIA_PENDING_CLIENT_STATUS,
        clientSide = true,
        playbackCommand = command,
        songId = songId,
        clientSongId = clientSongId,
        clientSongTitle = title,
        selectionMode = "community_affinity"
    )

    private fun favoriteAction(actionId: String, clientSongId: String?, title: String?) =
        AriaAction(
            type = "guardar_actual",
            actionId = actionId,
            executionStatus = ARIA_PENDING_CLIENT_STATUS,
            clientSide = true,
            songId = "must-not-be-used",
            clientSongId = clientSongId,
            clientSongTitle = title
        )

    private class PlaybackFixture {
        val gateway = FakePlaybackGateway()
        val telemetry = FakeTelemetry()
        val feedback = mutableListOf<Pair<String, Boolean>>()
        val handler = AriaPlaybackActionHandler(
            playbackGateway = gateway,
            idempotency = FakeIdempotency(),
            telemetry = telemetry,
            onFeedback = { message, success -> feedback += message to success }
        )

        fun lastReason(): String? = telemetry.events.lastOrNull()?.reason
    }

    private class FakePlaybackGateway : AriaPlaybackGateway {
        val controls = mutableListOf<AriaTransportControl>()
        val queuedSongIds = mutableListOf<String>()
        var currentSongId: String? = null
        var playedSongId: String? = null
        var playedTitle: String? = null
        var playCalls = 0
        var playFailure: Exception? = null

        override suspend fun control(command: AriaTransportControl) {
            controls += command
        }

        override suspend fun playSong(songId: String, title: String?, artist: String?) {
            playCalls += 1
            playFailure?.let { throw it }
            playedSongId = songId
            playedTitle = title
            currentSongId = songId
        }

        override suspend fun queueSong(songId: String) {
            queuedSongIds += songId
        }

        override suspend fun playArtistEssentials(artistId: String, artistName: String?) {
        }

        override suspend fun playArtistRadio(artistId: String, artistName: String?) {
        }

        var playedCollection: AriaCollection? = null
        var playedStartIndex: Int? = null
        var playedShuffle: Boolean? = null
        var collectionFailure: Exception? = null
        val queuedNext = mutableListOf<String>()

        override suspend fun playCollection(collection: AriaCollection, startIndex: Int, shuffle: Boolean) {
            collectionFailure?.let { throw it }
            playedCollection = collection
            playedStartIndex = startIndex
            playedShuffle = shuffle
        }

        override suspend fun queueSongs(songIds: List<String>, playNext: Boolean) {
            if (playNext) queuedNext += songIds else queuedSongIds += songIds
        }
    }

    private class FakeIdempotency : AriaActionIdempotency {
        private val claimed = mutableSetOf<String>()

        override fun tryClaim(actionId: String?): Boolean =
            actionId.isNullOrBlank() || claimed.add(actionId)
    }

    private class FakeTelemetry : AriaActionTelemetry {
        val events = mutableListOf<AriaActionTelemetryEvent>()

        override fun record(event: AriaActionTelemetryEvent) {
            events += event
        }
    }
}
