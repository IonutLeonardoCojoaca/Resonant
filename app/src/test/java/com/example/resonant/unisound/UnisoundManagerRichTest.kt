package com.example.resonant.unisound

import com.example.resonant.data.network.UnisoundReactionDTO
import com.example.resonant.data.network.UnisoundSavedPlaylistDTO
import com.example.resonant.unisound.UnisoundFixtures.GUEST
import com.example.resonant.unisound.UnisoundFixtures.snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class UnisoundManagerRichTest {
    private val service = FakeUnisoundService()
    private val gateway = FakeUnisoundHubGateway()
    private var keys = 0

    private suspend fun TestScope.joined(): Pair<UnisoundManager, MutableList<UnisoundManagerEvent>> {
        service.joinResponse = Response.success(snapshot())
        val manager = UnisoundManager(
            service = service,
            gateway = gateway,
            myUserId = { GUEST },
            isFeatureEnabled = { true },
            scope = backgroundScope,
            deviceNowMs = { testScheduler.currentTime },
            newKey = { "key-${++keys}" }
        )
        val events = mutableListOf<UnisoundManagerEvent>()
        backgroundScope.launch { manager.events.collect { events += it } }
        manager.joinRoom("K7M2QX")
        runCurrent()
        return manager to events
    }

    @Test
    fun `album is added as one source`() = runTest {
        val (manager, _) = joined()

        assertTrue(manager.addSource("album", "album-1") is UnisoundResult.Success)
        assertEquals(listOf("AddItems:album"), gateway.commands)
    }

    @Test
    fun `move sends the applied revision`() = runTest {
        val (manager, _) = joined()

        manager.moveItem("z", 0)

        assertEquals(listOf("MoveItem:z:0:10"), gateway.commands)
    }

    @Test
    fun `stale move shows nothing`() = runTest {
        val (manager, events) = joined()
        gateway.moveError = RuntimeException("UNISOUND_STALE_REVISION")

        manager.moveItem("z", 0)
        runCurrent()

        assertTrue(events.none { it is UnisoundManagerEvent.Message })
    }

    @Test
    fun `reaction over the limit is silent`() = runTest {
        val (manager, events) = joined()
        gateway.reactError = RuntimeException("UNISOUND_RATE_LIMITED")

        manager.react("🔥")
        runCurrent()

        assertEquals(listOf("🔥"), gateway.reactions)
        assertTrue(events.isEmpty())
        assertTrue(manager.hiddenEmojis.value.isEmpty())
    }

    @Test
    fun `rejected emoji is hidden for the rest of the room`() = runTest {
        val (manager, _) = joined()
        gateway.reactError = RuntimeException("UNISOUND_INVALID_REQUEST")

        manager.react("😢")

        assertEquals(setOf("😢"), manager.hiddenEmojis.value)
    }

    @Test
    fun `received reactions are published and not stored in the room`() = runTest {
        val (manager, _) = joined()
        val received = mutableListOf<UnisoundReactionDTO>()
        backgroundScope.launch { manager.reactions.collect { received += it } }
        runCurrent()
        val revision = manager.state.value?.revision

        gateway.listener?.onEvent(UnisoundEvent.Reaction(UnisoundReactionDTO(userId = "u", name = "Ana", emoji = "🔥")))
        runCurrent()

        assertEquals("Ana", received.single().name)
        assertEquals(revision, manager.state.value?.revision)
    }

    @Test
    fun `saving tells created from updated`() = runTest {
        val (manager, _) = joined()
        val playlist = UnisoundSavedPlaylistDTO(playlistId = "p", name = "Viernes", songCount = 10, addedCount = 10)

        service.savePlaylistResponse = Response.success(201, playlist)
        val created = manager.savePlaylist("s", "Viernes") as UnisoundResult.Success
        service.savePlaylistResponse = Response.success(200, playlist.copy(addedCount = 3))
        val updated = manager.savePlaylist("s", null) as UnisoundResult.Success

        assertTrue(created.value.created)
        assertFalse(updated.value.created)
        assertEquals(3, updated.value.playlist.addedCount)
    }

    @Test
    fun `nothing played maps to its message`() = runTest {
        val (manager, _) = joined()
        service.savePlaylistResponse = errorResponse(409, UnisoundErrors.NOTHING_PLAYED)

        val result = manager.savePlaylist("s", null) as UnisoundResult.Failure

        assertEquals("No ha sonado ninguna canción", result.message)
    }
}
