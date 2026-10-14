package com.example.resonant.playback

import com.example.resonant.data.network.LiveChannelInfoDTO
import com.example.resonant.live.LiveFixtures.BLOCK_START
import com.example.resonant.live.LiveFixtures.SLUG
import com.example.resonant.live.LiveFixtures.nextOf
import com.example.resonant.live.LiveFixtures.now
import com.example.resonant.live.LiveFixtures.standardBlock
import com.example.resonant.live.LiveListenStatus
import com.example.resonant.live.LiveMirrorChange
import com.example.resonant.live.LivePlayerSnapshot
import com.example.resonant.live.LiveSessionState
import com.example.resonant.live.LiveTickAction
import com.example.resonant.live.LiveTimeline
import com.example.resonant.unisound.ServerClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LivePlaybackAdapterTest {
    private val channel = LiveChannelInfoDTO("ch-1", SLUG, "channel", "Resonant Rock")
    private val state = MutableStateFlow<LiveSessionState?>(null)
    private var scheduleEnded = 0

    private class FakeHost : LivePlaybackAdapter.Host {
        val changes = mutableListOf<LiveMirrorChange>()
        val ticks = mutableListOf<LiveTickAction>()
        var snapshot: LivePlayerSnapshot? = null
        var queued: List<String> = emptyList()

        override fun applyLiveState(state: LiveSessionState?, change: LiveMirrorChange) {
            changes += change
        }

        override fun livePlayerSnapshot() = snapshot
        override fun liveQueuedKeys() = queued
        override fun applyLiveTick(action: LiveTickAction) {
            ticks += action
        }
    }

    private val host = FakeHost()

    private fun session(timeline: LiveTimeline, status: LiveListenStatus = LiveListenStatus.LISTENING) =
        LiveSessionState(SLUG, channel, timeline, status)

    /** Adapter whose server clock reads [BLOCK_START] + [offsetMs] + virtual time. */
    private fun TestScope.adapter(offsetMs: Long = 0L): LivePlaybackAdapter {
        val clock = ServerClock { testScheduler.currentTime }.apply {
            seedFromServerTime(BLOCK_START + offsetMs, receivedAtMs = 0L)
        }
        return LivePlaybackAdapter(
            state = state,
            clock = clock,
            host = host,
            onScheduleEnded = { scheduleEnded++ },
            reportDrift = {},
            dispatcher = StandardTestDispatcher(testScheduler),
            deviceNowMs = { testScheduler.currentTime }
        ).also {
            it.start(backgroundScope)
            runCurrent()
        }
    }

    @Test
    fun `enter loads, next block appends and leaving exits`() = runTest {
        adapter()
        val first = standardBlock()
        val timeline = LiveTimeline.from(now(first))

        state.value = session(timeline)
        runCurrent()
        state.value = session(timeline.merge(now(first, nextOf(first)), BLOCK_START))
        runCurrent()
        state.value = null
        runCurrent()

        assertEquals(listOf(LiveMirrorChange.ENTER, LiveMirrorChange.APPEND, LiveMirrorChange.EXIT), host.changes)
    }

    @Test
    fun `a gap pauses the player parked on the next song`() = runTest {
        adapter(offsetMs = 250_000L) // inside slot 1, which has no song
        host.queued = listOf("b1:0", "b1:2")
        host.snapshot = LivePlayerSnapshot(index = 1, positionMs = 0L, remainingMs = 240_000L, isPlaying = true, isBuffering = false)

        state.value = session(LiveTimeline.from(now(standardBlock(missing = setOf(1)))))
        runCurrent()
        advanceTimeBy(LivePlaybackAdapter.TICK_INTERVAL_MS + 1)

        assertEquals(LiveTickAction.HoldForGap(1), host.ticks.first())
    }

    @Test
    fun `hold stops the loop and rejoin loads again`() = runTest {
        adapter(offsetMs = 10_000L)
        host.queued = listOf("b1:0", "b1:1", "b1:2")
        host.snapshot = LivePlayerSnapshot(index = 2, positionMs = 0L, remainingMs = 1L, isPlaying = true, isBuffering = false)
        val timeline = LiveTimeline.from(now(standardBlock()))
        state.value = session(timeline)
        runCurrent()

        state.value = session(timeline, LiveListenStatus.HELD)
        runCurrent()
        advanceTimeBy(5_000L)
        assertTrue(host.ticks.isEmpty())

        state.value = session(timeline)
        runCurrent()

        assertEquals(listOf(LiveMirrorChange.ENTER, LiveMirrorChange.HOLD, LiveMirrorChange.REJOIN), host.changes)
    }

    @Test
    fun `player on the wrong song is sent to the scheduled one`() = runTest {
        adapter(offsetMs = 250_000L)
        host.queued = listOf("b1:0", "b1:1", "b1:2")
        host.snapshot = LivePlayerSnapshot(index = 0, positionMs = 10_000L, remainingMs = 190_000L, isPlaying = true, isBuffering = false)

        state.value = session(LiveTimeline.from(now(standardBlock())))
        runCurrent()
        advanceTimeBy(LivePlaybackAdapter.TICK_INTERVAL_MS + 1)

        assertEquals(LiveTickAction.SeekTo(1, 50_000L + LivePlaybackAdapter.TICK_INTERVAL_MS), host.ticks.first())
    }

    @Test
    fun `end of the schedule pauses and asks the manager`() = runTest {
        adapter(offsetMs = 620_000L)
        host.queued = listOf("b1:0", "b1:1", "b1:2")
        host.snapshot = LivePlayerSnapshot(index = 2, positionMs = 240_000L, remainingMs = 0L, isPlaying = false, isBuffering = false)

        state.value = session(LiveTimeline.from(now(standardBlock())))
        runCurrent()
        advanceTimeBy(LivePlaybackAdapter.TICK_INTERVAL_MS + 1)

        assertEquals(LiveTickAction.Ended, host.ticks.first())
        assertTrue(scheduleEnded >= 1)
    }

    @Test
    fun `a slot the player does not have rebuilds`() = runTest {
        adapter(offsetMs = 10_000L)
        host.queued = listOf("other:0")
        host.snapshot = LivePlayerSnapshot(index = 0, positionMs = 0L, remainingMs = 1L, isPlaying = true, isBuffering = false)

        state.value = session(LiveTimeline.from(now(standardBlock())))
        runCurrent()
        advanceTimeBy(LivePlaybackAdapter.TICK_INTERVAL_MS + 1)

        assertEquals(LiveMirrorChange.ENTER, host.changes.last())
        assertTrue(host.changes.count { it == LiveMirrorChange.ENTER } >= 2)
    }
}
