package com.example.resonant.unisound

import com.example.resonant.unisound.UnisoundFixtures.item
import com.example.resonant.unisound.UnisoundFixtures.room
import com.example.resonant.unisound.UnisoundFixtures.snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnisoundMirrorPlanTest {
    private fun mirror(current: String?, vararg queue: String, session: String = "s") =
        UnisoundMirror(session, current, queue.toList())

    @Test
    fun `entering a room rebuilds`() {
        assertEquals(UnisoundMirrorChange.REBUILD, UnisoundMirrorPlan.diff(null, mirror("x", "y")))
    }

    @Test
    fun `new current song rebuilds`() {
        assertEquals(UnisoundMirrorChange.REBUILD, UnisoundMirrorPlan.diff(mirror("x", "y"), mirror("y")))
    }

    @Test
    fun `another room rebuilds`() {
        assertEquals(
            UnisoundMirrorChange.REBUILD,
            UnisoundMirrorPlan.diff(mirror("x", session = "a"), mirror("x", session = "b"))
        )
    }

    @Test
    fun `pending change only swaps the queue`() {
        assertEquals(UnisoundMirrorChange.QUEUE, UnisoundMirrorPlan.diff(mirror("x", "y"), mirror("x", "y", "z")))
    }

    @Test
    fun `same songs only update the state`() {
        assertEquals(UnisoundMirrorChange.STATE, UnisoundMirrorPlan.diff(mirror("x", "y"), mirror("x", "y")))
    }

    @Test
    fun `leaving exits and nothing to nothing is none`() {
        assertEquals(UnisoundMirrorChange.EXIT, UnisoundMirrorPlan.diff(mirror("x"), null))
        assertEquals(UnisoundMirrorChange.NONE, UnisoundMirrorPlan.diff(null, null))
    }

    @Test
    fun `songs list starts with the current song and fills missing durations`() {
        val state = room(snapshot = snapshot(current = item("x").copy(song = null, durationMs = 215_000L)))

        val songs = UnisoundMirrorPlan.songsOf(state)

        assertEquals(listOf("song-x", "song-y", "song-z"), songs.map { it.id })
        assertEquals("215", songs.first().duration)
        assertEquals(listOf("x", "y", "z"), UnisoundMirrorPlan.entryIdsOf(state))
    }

    @Test
    fun `player already on the next song near the end is a boundary`() {
        val state = room()

        assertTrue(
            UnisoundMirrorPlan.isCrossingSongBoundary(
                state, playerMediaId = "song-y", playerNextMediaId = "song-z",
                playerRemainingMs = 199_000L, expectedPositionMs = 199_500L
            )
        )
    }

    @Test
    fun `player about to enter the room song is a boundary`() {
        val state = room()

        assertTrue(
            UnisoundMirrorPlan.isCrossingSongBoundary(
                state, playerMediaId = "song-prev", playerNextMediaId = "song-x",
                playerRemainingMs = 400L, expectedPositionMs = 300L
            )
        )
    }

    @Test
    fun `a different song mid way is not a boundary`() {
        val state = room()

        assertFalse(
            UnisoundMirrorPlan.isCrossingSongBoundary(
                state, playerMediaId = "song-y", playerNextMediaId = "song-z",
                playerRemainingMs = 150_000L, expectedPositionMs = 60_000L
            )
        )
    }
}
