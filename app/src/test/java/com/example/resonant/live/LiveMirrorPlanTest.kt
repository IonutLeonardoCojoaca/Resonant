package com.example.resonant.live

import com.example.resonant.live.LiveFixtures.BLOCK_START
import com.example.resonant.live.LiveFixtures.nextOf
import com.example.resonant.live.LiveFixtures.now
import com.example.resonant.live.LiveFixtures.standardBlock
import com.example.resonant.unisound.UnisoundDriftAction
import com.example.resonant.unisound.UnisoundDriftPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveMirrorPlanTest {
    private val listening = LiveMirror("rock", LiveListenStatus.LISTENING, listOf("b1:0", "b1:1"))
    private val drift = UnisoundDriftPolicy()

    private val timeline = LiveTimeline.from(now(standardBlock()))
    private val keys = listOf("b1:0", "b1:1", "b1:2")

    private fun player(index: Int?, positionMs: Long, remainingMs: Long = 100_000L, playing: Boolean = true) =
        LivePlayerSnapshot(index, positionMs, remainingMs, isPlaying = playing, isBuffering = false)

    private fun tick(atMs: Long, snapshot: LivePlayerSnapshot, t: LiveTimeline = timeline, queued: List<String> = keys) =
        LiveMirrorPlan.tick(t.at(atMs), queued, snapshot, drift, nowMs = atMs)

    @Test
    fun `diff covers enter, exit, hold, rejoin and append`() {
        assertEquals(LiveMirrorChange.NONE, LiveMirrorPlan.diff(null, null))
        assertEquals(LiveMirrorChange.ENTER, LiveMirrorPlan.diff(null, listening))
        assertEquals(LiveMirrorChange.ENTER, LiveMirrorPlan.diff(listening, listening.copy(slug = "chill")))
        assertEquals(LiveMirrorChange.EXIT, LiveMirrorPlan.diff(listening, null))
        val held = listening.copy(status = LiveListenStatus.HELD)
        assertEquals(LiveMirrorChange.HOLD, LiveMirrorPlan.diff(listening, held))
        assertEquals(LiveMirrorChange.REJOIN, LiveMirrorPlan.diff(held, listening))
        assertEquals(
            LiveMirrorChange.APPEND,
            LiveMirrorPlan.diff(listening, listening.copy(songSlotKeys = listening.songSlotKeys + "b2:0"))
        )
        // Dropping finished slots is not a change for the player.
        assertEquals(LiveMirrorChange.NONE, LiveMirrorPlan.diff(listening, listening.copy(songSlotKeys = listOf("b1:1"))))
    }

    @Test
    fun `mirror keys skip slots without song`() {
        val mirror = LiveMirrorPlan.mirrorOf("rock", LiveListenStatus.LISTENING, LiveTimeline.from(now(standardBlock(missing = setOf(1)))))

        assertEquals(listOf("b1:0", "b1:2"), mirror?.songSlotKeys)
    }

    @Test
    fun `player on another song seeks to the scheduled one`() {
        assertEquals(LiveTickAction.SeekTo(1, 50_000L), tick(BLOCK_START + 250_000L, player(index = 0, positionMs = 10_000L)))
    }

    @Test
    fun `same song one second off uses the drift policy seek`() {
        val action = tick(BLOCK_START + 60_000L, player(index = 0, positionMs = 61_000L))

        assertEquals(LiveTickAction.Drift(UnisoundDriftAction.Seek(60_000L)), action)
    }

    @Test
    fun `same song in sync does nothing`() {
        assertEquals(LiveTickAction.None, tick(BLOCK_START + 60_000L, player(index = 0, positionMs = 60_050L)))
    }

    @Test
    fun `near a song boundary nothing is corrected`() {
        // The schedule moved on 1 s ago and the player is about to.
        assertEquals(
            LiveTickAction.None,
            tick(BLOCK_START + 201_000L, player(index = 0, positionMs = 199_500L, remainingMs = 500L))
        )
        // The player moved on 1 s early.
        assertEquals(LiveTickAction.None, tick(BLOCK_START + 199_000L, player(index = 1, positionMs = 300L)))
        // Last second of the same song.
        assertEquals(LiveTickAction.None, tick(BLOCK_START + 199_500L, player(index = 0, positionMs = 197_000L)))
    }

    @Test
    fun `audio longer than its slot is cut at the schedule`() {
        val action = tick(BLOCK_START + 205_000L, player(index = 0, positionMs = 204_000L, remainingMs = 30_000L))

        assertEquals(LiveTickAction.SeekTo(1, 5_000L), action)
    }

    @Test
    fun `gap pauses parked on the next playable song`() {
        val gapped = LiveTimeline.from(now(standardBlock(missing = setOf(1))))
        val queued = listOf("b1:0", "b1:2")

        assertEquals(
            LiveTickAction.HoldForGap(1),
            tick(BLOCK_START + 250_000L, player(index = 1, positionMs = 1_000L), gapped, queued)
        )
        assertEquals(
            LiveTickAction.None,
            tick(BLOCK_START + 260_000L, player(index = 1, positionMs = 0L, playing = false), gapped, queued)
        )
    }

    @Test
    fun `end of the gap starts the next song at zero`() {
        val gapped = LiveTimeline.from(now(standardBlock(missing = setOf(1))))
        val queued = listOf("b1:0", "b1:2")

        val action = tick(BLOCK_START + 380_000L, player(index = 1, positionMs = 0L, playing = false), gapped, queued)

        assertEquals(LiveTickAction.SeekTo(1, 0L), action)
    }

    @Test
    fun `slot missing from the player queue rebuilds`() {
        val first = standardBlock()
        val merged = timeline.merge(now(first, nextOf(first)), BLOCK_START)

        assertEquals(LiveTickAction.Rebuild, tick(first.endsAtUtcMs + 10_000L, player(index = 2, positionMs = 0L), merged))
    }

    @Test
    fun `end of the known schedule is reported`() {
        assertEquals(LiveTickAction.Ended, tick(standardBlock().endsAtUtcMs, player(index = 2, positionMs = 240_000L)))
    }
}
