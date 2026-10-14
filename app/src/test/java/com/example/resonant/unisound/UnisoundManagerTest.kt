package com.example.resonant.unisound

import com.example.resonant.data.network.UnisoundSessionSnapshotDTO
import com.example.resonant.data.network.UnisoundSessionSummaryDTO
import com.example.resonant.unisound.UnisoundFixtures.GUEST
import com.example.resonant.unisound.UnisoundFixtures.snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class UnisoundManagerTest {
    private val service = FakeUnisoundService()
    private val gateway = FakeUnisoundHubGateway()
    private var featureEnabled = true
    private var keyCounter = 0
    private var roomsEntered = 0

    private fun TestScope.manager(): Pair<UnisoundManager, MutableList<UnisoundManagerEvent>> {
        val manager = UnisoundManager(
            service = service,
            gateway = gateway,
            myUserId = { GUEST },
            isFeatureEnabled = { featureEnabled },
            scope = backgroundScope,
            deviceNowMs = { testScheduler.currentTime },
            newKey = { "key-${++keyCounter}" },
            onEnterRoom = { roomsEntered++ }
        )
        val events = mutableListOf<UnisoundManagerEvent>()
        backgroundScope.launch { manager.events.collect { events += it } }
        runCurrent()
        return manager to events
    }

    private suspend fun TestScope.joinedManager(): Pair<UnisoundManager, MutableList<UnisoundManagerEvent>> {
        service.joinResponse = Response.success(snapshot())
        val (manager, events) = manager()
        manager.joinRoom("K7M2QX")
        runCurrent()
        return manager to events
    }

    @Test
    fun `create connects estimates clock and applies the snapshot`() = runTest {
        service.createResponse = Response.success(201, snapshot(revision = 3L))
        gateway.joinSnapshot = snapshot(revision = 4L)
        val (manager, _) = manager()

        val result = manager.createRoom()
        runCurrent()

        assertTrue(result is UnisoundResult.Success)
        assertEquals(1, gateway.connectCalls)
        assertEquals(UnisoundManager.CLOCK_SAMPLES, gateway.syncCalls)
        assertEquals(listOf(3L), gateway.joinRevisions)
        assertEquals(UnisoundConnection.CONNECTED, manager.state.value?.connection)
        assertEquals(4L, manager.state.value?.revision)
    }

    @Test
    fun `already in a room opens the current one`() = runTest {
        service.createResponse = errorResponse(409, UnisoundErrors.ALREADY_IN_SESSION)
        service.currentResponse = Response.success(snapshot())
        val (manager, _) = manager()

        val result = manager.createRoom()

        assertTrue(result is UnisoundResult.Success)
        assertEquals(UnisoundFixtures.SESSION, manager.currentSessionIdOrNull())
    }

    @Test
    fun `join error maps to a spanish message and stays out`() = runTest {
        service.joinResponse = errorResponse(404, UnisoundErrors.CODE_INVALID)
        val (manager, _) = manager()

        val result = manager.joinRoom("k7m2qx") as UnisoundResult.Failure

        assertEquals(listOf("K7M2QX"), service.joinedCodes)
        assertEquals("Código no válido", result.message)
        assertNull(manager.state.value)
    }

    @Test
    fun `invalid code format sends no request`() = runTest {
        val (manager, _) = manager()

        manager.joinRoom("K7M2Q")

        assertTrue(service.joinedCodes.isEmpty())
    }

    @Test
    fun `closed connection reconnects with the applied revision`() = runTest {
        val (manager, _) = joinedManager()
        gateway.listener?.onEvent(UnisoundEvent.PlaybackChanged(UnisoundFixtures.playback(11L)))
        runCurrent()

        gateway.isConnected = false
        gateway.listener?.onClosed(IOException("network"))
        runCurrent()

        assertEquals(2, gateway.connectCalls)
        assertEquals(11L, gateway.joinRevisions.last())
    }

    @Test
    fun `entering a room leaves live once and reconnecting does not`() = runTest {
        joinedManager()
        assertEquals(1, roomsEntered)

        gateway.isConnected = false
        gateway.listener?.onClosed(IOException("network"))
        runCurrent()

        assertEquals(1, roomsEntered)
    }

    @Test
    fun `failed reconnects back off`() = runTest {
        val (manager, _) = joinedManager()
        gateway.connectError = IOException("still offline")
        gateway.listener?.onClosed(IOException("network"))
        runCurrent()
        assertEquals(2, gateway.connectCalls)

        advanceTimeBy(1_999L)
        assertEquals(2, gateway.connectCalls)
        advanceTimeBy(2L)
        assertEquals(3, gateway.connectCalls)
        assertEquals(UnisoundConnection.RECONNECTING, manager.state.value?.connection)
    }

    @Test
    fun `stale revision applies nothing and is not retried`() = runTest {
        val (manager, events) = joinedManager()
        gateway.onPause = { _, _ -> throw RuntimeException("HubException: UNISOUND_STALE_REVISION") }

        manager.control(UnisoundControl.PAUSE, 61_000L, UnisoundControlOrigin.APP)
        runCurrent()

        assertEquals(listOf("Pause"), gateway.commands)
        assertTrue(events.none { it is UnisoundManagerEvent.Message })
    }

    @Test
    fun `transient failure is retried once with the same key`() = runTest {
        val (manager, _) = joinedManager()
        var attempts = 0
        gateway.onPlay = { revision ->
            if (attempts++ == 0) throw RuntimeException("UNISOUND_UNAVAILABLE")
            UnisoundFixtures.playback(revision + 1)
        }
        gateway.listener?.onEvent(UnisoundEvent.Snapshot(snapshot(revision = 12L, status = UnisoundRoomState.STATUS_PAUSED)))
        runCurrent()

        manager.control(UnisoundControl.PLAY, null, UnisoundControlOrigin.SYSTEM)
        runCurrent()

        assertEquals(2, gateway.commandKeys.size)
        assertEquals(gateway.commandKeys[0], gateway.commandKeys[1])
        assertEquals(13L, manager.state.value?.revision)
        assertTrue(manager.state.value!!.isPlaying)
    }

    @Test
    fun `second transient failure shows the retry message`() = runTest {
        val (manager, events) = joinedManager()
        gateway.onPause = { _, _ -> throw RuntimeException("UNISOUND_UNAVAILABLE") }

        manager.control(UnisoundControl.PAUSE, 1_000L, UnisoundControlOrigin.APP)
        runCurrent()

        assertEquals(2, gateway.commands.size)
        assertTrue(events.contains(UnisoundManagerEvent.Message(UnisoundErrors.MESSAGE_RETRY)))
    }

    @Test
    fun `feature switched off closes the room cleanly`() = runTest {
        val (manager, events) = joinedManager()
        gateway.onPause = { _, _ -> throw RuntimeException("UNISOUND_NOT_AVAILABLE") }

        manager.control(UnisoundControl.PAUSE, 1_000L, UnisoundControlOrigin.APP)
        runCurrent()

        assertNull(manager.state.value)
        assertTrue(events.contains(UnisoundManagerEvent.Closed(UnisoundCloseReason.UNAVAILABLE)))
        assertTrue(events.contains(UnisoundManagerEvent.Message(UnisoundErrors.MESSAGE_NOT_AVAILABLE)))
    }

    @Test
    fun `denied control never reaches the hub`() = runTest {
        service.joinResponse = Response.success(snapshot())
        gateway.joinSnapshot = snapshot(
            settings = com.example.resonant.data.network.UnisoundSettingsDTO(guestsCanControl = false)
        )
        val (manager, events) = manager()
        manager.joinRoom("K7M2QX")
        runCurrent()

        manager.control(UnisoundControl.NEXT, null, UnisoundControlOrigin.SYSTEM)
        runCurrent()

        assertTrue(gateway.commands.isEmpty())
        assertTrue(events.contains(UnisoundManagerEvent.Message(UnisoundErrors.MESSAGE_CONTROL_DENIED)))
    }

    @Test
    fun `leave with network failure leaves locally and retries once`() = runTest {
        val (manager, events) = joinedManager()
        service.leaveResponses = ArrayDeque(listOf(null, Response.success(204, Unit)))

        manager.leaveRoom()
        runCurrent()

        assertNull(manager.state.value)
        assertTrue(events.contains(UnisoundManagerEvent.Closed(UnisoundCloseReason.LEFT)))
        assertEquals(1, service.leaveCalls)
        advanceTimeBy(UnisoundManager.LEAVE_RETRY_DELAY_MS + 1)
        assertEquals(2, service.leaveCalls)
    }

    @Test
    fun `session ended event closes with the summary`() = runTest {
        val (manager, events) = joinedManager()
        val summary = UnisoundSessionSummaryDTO(sessionId = UnisoundFixtures.SESSION, reason = "ended", songsPlayed = 3)

        gateway.listener?.onEvent(UnisoundEvent.Ended(summary))
        runCurrent()

        assertNull(manager.state.value)
        assertTrue(events.contains(UnisoundManagerEvent.Closed(UnisoundCloseReason.ENDED, summary)))
    }

    @Test
    fun `restore keeps audio on local hold`() = runTest {
        service.currentResponse = Response.success(snapshot())
        val (manager, _) = manager()

        assertTrue(manager.restore())

        assertTrue(manager.state.value!!.localHold)
    }

    @Test
    fun `restore without a room does nothing`() = runTest {
        service.currentResponse = Response.success<UnisoundSessionSnapshotDTO>(204, null)
        val (manager, _) = manager()

        assertFalse(manager.restore())
        assertNull(manager.state.value)
    }

    @Test
    fun `restore with the feature off makes no request`() = runTest {
        featureEnabled = false
        val (manager, _) = manager()

        assertFalse(manager.restore())
        assertEquals(0, service.currentCalls)
    }

    @Test
    fun `local hold is reengaged without a hub order`() = runTest {
        service.currentResponse = Response.success(snapshot())
        val (manager, _) = manager()
        manager.restore()
        runCurrent()

        manager.control(UnisoundControl.PLAY, null, UnisoundControlOrigin.APP)
        runCurrent()

        assertFalse(manager.state.value!!.localHold)
        assertTrue(gateway.commands.isEmpty())
    }

    @Test
    fun `add song sends a song source`() = runTest {
        val (manager, _) = joinedManager()

        val result = manager.addSong("song-1")

        assertTrue(result is UnisoundResult.Success)
        assertEquals(listOf("AddItems:song"), gateway.commands)
    }
}
