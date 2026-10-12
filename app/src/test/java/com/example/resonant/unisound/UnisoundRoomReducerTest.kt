package com.example.resonant.unisound

import com.example.resonant.data.network.UnisoundSessionSummaryDTO
import com.example.resonant.data.network.UnisoundSettingsDTO
import com.example.resonant.unisound.UnisoundFixtures.GUEST
import com.example.resonant.unisound.UnisoundFixtures.item
import com.example.resonant.unisound.UnisoundFixtures.playback
import com.example.resonant.unisound.UnisoundFixtures.room
import com.example.resonant.unisound.UnisoundFixtures.snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UnisoundRoomReducerTest {
    private fun reduce(state: UnisoundRoomState, event: UnisoundEvent) =
        UnisoundRoomReducer.reduce(state, event)

    @Test
    fun `duplicate or older playback is ignored`() {
        val state = room()

        listOf(9L, 10L).forEach { revision ->
            val result = reduce(state, UnisoundEvent.PlaybackChanged(playback(revision, currentItemId = "y")))
            assertSame(state, result.state)
            assertTrue(result.effects.isEmpty())
        }
    }

    @Test
    fun `next revision applies song change`() {
        val result = reduce(room(), UnisoundEvent.PlaybackChanged(playback(11L, currentItemId = "y")))

        assertEquals("y", result.state?.current?.itemId)
        assertEquals(listOf("z"), result.state?.queue?.map { it.itemId })
        assertEquals(listOf(UnisoundEffect.PlaybackUpdated), result.effects)
    }

    @Test
    fun `queue change with the same revision is applied`() {
        val afterSkip = reduce(room(), UnisoundEvent.PlaybackChanged(playback(11L, currentItemId = "y"))).state!!

        val result = reduce(afterSkip, UnisoundEvent.QueueChanged(11L, listOf(item("z"), item("w"))))

        assertEquals(listOf("z", "w"), result.state?.queue?.map { it.itemId })
        assertTrue(result.effects.none { it is UnisoundEffect.ResyncNeeded })
    }

    @Test
    fun `revision gap requests resync from the applied revision`() {
        val result = reduce(room(), UnisoundEvent.PlaybackChanged(playback(13L, currentItemId = "y")))

        assertTrue(result.effects.contains(UnisoundEffect.ResyncNeeded(10L)))
        assertEquals(13L, result.state?.revision)
    }

    @Test
    fun `unknown current item requests resync`() {
        val result = reduce(room(), UnisoundEvent.PlaybackChanged(playback(11L, currentItemId = "never-seen")))

        assertTrue(result.effects.contains(UnisoundEffect.ResyncNeeded(10L)))
    }

    @Test
    fun `older queue change is ignored`() {
        val state = room()

        assertSame(state, reduce(state, UnisoundEvent.QueueChanged(9L, emptyList())).state)
    }

    @Test
    fun `snapshot replaces everything and keeps local hold`() {
        val state = room(localHold = true)
        val fresh = snapshot(revision = 20L, current = item("q"), queue = emptyList())

        val result = reduce(state, UnisoundEvent.Snapshot(fresh))

        assertEquals(20L, result.state?.revision)
        assertEquals("q", result.state?.current?.itemId)
        assertTrue(result.state!!.localHold)
        assertTrue(result.state!!.knownItems.containsKey("y"))
    }

    @Test
    fun `policy change recomputes guest permissions`() {
        val result = reduce(
            room(),
            UnisoundEvent.PolicyChanged(UnisoundSettingsDTO(guestsCanAdd = true, guestsCanControl = false, joinOpen = true))
        )

        assertFalse(result.state!!.canControl)
        assertTrue(result.state!!.canAdd)
    }

    @Test
    fun `host transfer to me emits became host`() {
        val result = reduce(room(myUserId = GUEST), UnisoundEvent.MembersChanged(UnisoundFixtures.members(host = GUEST)))

        assertTrue(result.state!!.isHost)
        assertTrue(result.state!!.canControl)
        assertEquals(listOf(UnisoundEffect.BecameHost), result.effects)
    }

    @Test
    fun `kicked and ended are distinguished`() {
        val kicked = reduce(room(), UnisoundEvent.Ended(UnisoundSessionSummaryDTO(reason = "kicked")))
        val ended = reduce(room(), UnisoundEvent.Ended(UnisoundSessionSummaryDTO(reason = "ended")))

        assertNull(kicked.state)
        assertTrue(kicked.effects.single() is UnisoundEffect.Kicked)
        assertTrue(ended.effects.single() is UnisoundEffect.Ended)
    }

    @Test
    fun `expected position follows the anchor and clamps to duration`() {
        val state = room().copy(playback = playback(10L, positionMs = 48_210L, anchorMs = 1_000L))

        assertEquals(50_210L, state.expectedPositionMs(3_000L))
        assertEquals(200_000L, state.expectedPositionMs(10_000_000L))
        assertEquals(
            48_210L,
            state.copy(playback = state.playback.copy(status = UnisoundRoomState.STATUS_PAUSED)).expectedPositionMs(3_000L)
        )
    }

    @Test
    fun `guest may remove only own items without control`() {
        val state = room().copy(settings = UnisoundSettingsDTO(guestsCanControl = false))

        assertTrue(state.canRemove(item("a", addedBy = GUEST)))
        assertFalse(state.canRemove(item("b", addedBy = UnisoundFixtures.HOST)))
    }
}
