package com.example.resonant.unisound

import com.example.resonant.data.network.UnisoundSavedPlaylistDTO
import com.example.resonant.data.network.UnisoundSessionSummaryDTO
import com.example.resonant.unisound.UnisoundFixtures.GUEST
import com.example.resonant.unisound.UnisoundFixtures.item
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
import java.io.IOException

class UnisoundItemLabelsTest {
    @Test
    fun `user items show who added them`() {
        assertEquals("Añadido por Ana", UnisoundItemLabels.labelFor(item("a", addedBy = "ana-id").copy(addedByName = "Ana"), 2, GUEST))
        assertEquals("Añadido por ti", UnisoundItemLabels.labelFor(item("a", addedBy = GUEST), 2, GUEST))
    }

    @Test
    fun `aria items name who asked`() {
        val aria = item("a").copy(addedBy = "aria", addedByName = "Ana")

        assertEquals("Aria · para Ana", UnisoundItemLabels.labelFor(aria, 2, GUEST))
        assertTrue(UnisoundItemLabels.isAutomatic(aria))
    }

    @Test
    fun `room flow items depend on the number of members`() {
        val flow = item("a").copy(addedBy = "room_flow", addedByUserId = null, addedByName = null)

        assertEquals("Elegido para los dos", UnisoundItemLabels.labelFor(flow, 2, GUEST))
        assertEquals("Elegido para la sala", UnisoundItemLabels.labelFor(flow, 3, GUEST))
        assertTrue(UnisoundItemLabels.isAutomatic(flow))
    }

    @Test
    fun `unknown origin has no label`() {
        val unknown = item("a").copy(addedBy = "something_new")

        assertEquals("", UnisoundItemLabels.labelFor(unknown, 2, GUEST))
        assertFalse(UnisoundItemLabels.isAutomatic(unknown))
    }
}

class UnisoundQueueMoveTest {
    @Test
    fun `moving to the top sends index zero`() {
        assertEquals(0, UnisoundQueueMove.targetIndex(fromIndex = 2, toIndex = 0, queueSize = 3))
    }

    @Test
    fun `dropping in place sends nothing`() {
        assertNull(UnisoundQueueMove.targetIndex(1, 1, 3))
    }

    @Test
    fun `out of range targets are clamped and invalid sources ignored`() {
        assertEquals(2, UnisoundQueueMove.targetIndex(0, 9, 3))
        assertNull(UnisoundQueueMove.targetIndex(0, -1, 3))
        assertNull(UnisoundQueueMove.targetIndex(5, 0, 3))
    }
}

class UnisoundReactionThrottleTest {
    @Test
    fun `five taps pass and the sixth pauses the bar`() {
        val throttle = UnisoundReactionThrottle()
        repeat(5) { assertTrue(throttle.tryTap(it * 100L)) }

        assertFalse(throttle.tryTap(600L))
        assertTrue(throttle.isBlocked(1_000L))
        assertFalse(throttle.tryTap(2_000L))
    }

    @Test
    fun `old taps leave the window`() {
        val throttle = UnisoundReactionThrottle()
        repeat(5) { throttle.tryTap(it * 100L) }

        assertTrue(throttle.tryTap(10_500L))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class UnisoundSummaryPollerTest {
    private fun summary(status: String?, line: String? = null) =
        UnisoundSessionSummaryDTO(sessionId = "s", ariaLineStatus = status, ariaLine = line)

    @Test
    fun `pending becomes ready at five seconds and stops`() = runTest {
        var calls = 0
        val updates = mutableListOf<UnisoundSessionSummaryDTO>()
        val poller = UnisoundSummaryPoller(fetch = { calls++; summary("ready", "Hola") })

        launchPoll(poller, summary("pending"), updates)
        advanceTimeBy(5_001L)
        runCurrent()

        assertEquals(1, calls)
        assertEquals("Hola", updates.single().ariaLine)
        advanceTimeBy(20_000L)
        assertEquals(1, calls)
    }

    @Test
    fun `still pending stops after fifteen seconds without update`() = runTest {
        var calls = 0
        val updates = mutableListOf<UnisoundSessionSummaryDTO>()
        val poller = UnisoundSummaryPoller(fetch = { calls++; summary("pending") })

        launchPoll(poller, summary("pending"), updates)
        advanceTimeBy(60_000L)

        assertEquals(2, calls)
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `failures are silent`() = runTest {
        val updates = mutableListOf<UnisoundSessionSummaryDTO>()
        val poller = UnisoundSummaryPoller(fetch = { throw IOException("offline") })

        launchPoll(poller, summary("pending"), updates)
        advanceTimeBy(60_000L)

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `ready summary is not polled`() = runTest {
        var calls = 0
        UnisoundSummaryPoller(fetch = { calls++; null }).poll(summary("ready", "x")) { }

        assertEquals(0, calls)
    }

    private fun TestScope.launchPoll(
        poller: UnisoundSummaryPoller,
        initial: UnisoundSessionSummaryDTO,
        updates: MutableList<UnisoundSessionSummaryDTO>
    ) {
        backgroundScope.launch { poller.poll(initial) { updates += it } }
    }
}

class UnisoundSaveMessagesTest {
    @Test
    fun `created and updated are told apart`() {
        val playlist = UnisoundSavedPlaylistDTO(playlistId = "p", name = "Viernes", songCount = 13, addedCount = 3)

        assertEquals("Playlist creada", UnisoundSaveMessages.forResult(UnisoundResult.Success(UnisoundSavedPlaylist(playlist, created = true))))
        assertEquals(
            "Playlist actualizada · 3 nuevas",
            UnisoundSaveMessages.forResult(UnisoundResult.Success(UnisoundSavedPlaylist(playlist, created = false)))
        )
        assertEquals("x", UnisoundSaveMessages.forResult(UnisoundResult.Failure("C", "x")))
    }
}
