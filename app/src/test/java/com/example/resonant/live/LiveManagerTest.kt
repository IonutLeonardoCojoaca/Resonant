package com.example.resonant.live

import com.example.resonant.live.LiveFixtures.BLOCK_START
import com.example.resonant.live.LiveFixtures.SLUG
import com.example.resonant.live.LiveFixtures.nextOf
import com.example.resonant.live.LiveFixtures.now
import com.example.resonant.live.LiveFixtures.standardBlock
import com.example.resonant.data.network.LiveChannelsResponseDTO
import com.example.resonant.unisound.errorResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class LiveManagerTest {
    private val service = FakeLiveService()
    private var featureEnabled = true
    private var forcedRefreshes = 0

    /** The standard block ends 620 s after it starts; its lookahead opens at 320 s. */
    private val lookaheadMs = 320_000L

    private fun TestScope.manager(): Pair<LiveManager, MutableList<LiveManagerEvent>> {
        val platform = FakePlatformService { BLOCK_START + testScheduler.currentTime }
        return manager(platform)
    }

    private fun TestScope.manager(platform: FakePlatformService): Pair<LiveManager, MutableList<LiveManagerEvent>> {
        val manager = LiveManager(
            service = service,
            platform = platform,
            isFeatureEnabled = { force -> if (force) forcedRefreshes++; featureEnabled },
            scope = backgroundScope,
            deviceNowMs = { testScheduler.currentTime }
        )
        val events = mutableListOf<LiveManagerEvent>()
        backgroundScope.launch { manager.events.collect { events += it } }
        runCurrent()
        return manager to events
    }

    private suspend fun TestScope.tunedManager(): Pair<LiveManager, MutableList<LiveManagerEvent>> {
        service.nowResponse = Response.success(now(standardBlock()))
        val pair = manager()
        pair.first.start(SLUG)
        runCurrent()
        return pair
    }

    @Test
    fun `start publishes the state before the clock samples and applies them later`() = runTest {
        val platform = FakePlatformService { BLOCK_START + testScheduler.currentTime }
        service.nowResponse = Response.success(now(standardBlock()))
        val (manager, _) = manager(platform)

        val result = manager.start(SLUG)

        assertTrue(result is LiveNowResult.Success)
        assertEquals(LiveListenStatus.LISTENING, manager.state.value?.status)
        assertEquals(0, platform.timeCalls)
        assertEquals(BLOCK_START, manager.clock.now())

        runCurrent()

        assertEquals(LiveManager.CLOCK_SAMPLES, platform.timeCalls)
        assertTrue(manager.clock.hasEstimate)
    }

    @Test
    fun `failing time samples keep the seed from now`() = runTest {
        val platform = FakePlatformService { 0L }.apply { fail = true }
        service.nowResponse = Response.success(now(standardBlock()))
        val (manager, _) = manager(platform)

        manager.start(SLUG)
        runCurrent()

        assertFalse(manager.clock.hasEstimate)
        assertEquals(BLOCK_START, manager.clock.offsetMs)
        assertNotNull(manager.state.value)
    }

    @Test
    fun `off air channel is not tuned in`() = runTest {
        service.nowResponse = Response.success(now(null, standardBlock()))
        val (manager, _) = manager()

        val result = manager.start(SLUG)

        assertTrue(result is LiveNowResult.Success)
        assertNull(manager.state.value)
    }

    @Test
    fun `flag off never calls live`() = runTest {
        featureEnabled = false
        val (manager, _) = manager()

        val result = manager.start(SLUG) as LiveNowResult.Failure

        assertEquals(LiveFailure.DISABLED, result.failure)
        assertEquals(0, service.nowCalls)
        assertNull(manager.loadChannels())
        assertEquals(0, service.channelsCalls)
    }

    @Test
    fun `feature disabled while listening leaves with a message and refreshes capabilities`() = runTest {
        val (manager, events) = tunedManager()
        service.nowResponse = errorResponse(404, LiveErrors.FEATURE_DISABLED)

        advanceTimeBy(lookaheadMs + 1)
        runCurrent()

        assertNull(manager.state.value)
        assertEquals(1, forcedRefreshes)
        assertEquals(LiveManagerEvent.Closed(LiveErrors.MESSAGE_DISABLED), events.last())
    }

    @Test
    fun `channel not found while listening leaves`() = runTest {
        val (manager, events) = tunedManager()
        service.nowResponse = errorResponse(404, LiveErrors.CHANNEL_NOT_FOUND)

        advanceTimeBy(lookaheadMs + 1)
        runCurrent()

        assertNull(manager.state.value)
        assertEquals(LiveManagerEvent.Closed(LiveErrors.MESSAGE_NOT_FOUND), events.last())
    }

    @Test
    fun `a minute without network keeps the broadcast and recovers afterwards`() = runTest {
        val (manager, events) = tunedManager()
        val first = standardBlock()
        service.nowResponse = null

        advanceTimeBy(lookaheadMs + 60_000L)
        runCurrent()

        assertNotNull(manager.state.value)
        assertEquals(3, manager.state.value?.timeline?.slots?.size)
        assertTrue(events.isEmpty())

        service.nowResponse = Response.success(now(first, nextOf(first)))
        advanceTimeBy(60_001L)
        runCurrent()

        assertEquals(5, manager.state.value?.timeline?.slots?.size)
    }

    @Test
    fun `rate limit keeps listening without a message`() = runTest {
        val (manager, events) = tunedManager()
        service.nowResponse = errorResponse(429, null)

        advanceTimeBy(lookaheadMs + 1)
        runCurrent()

        assertNotNull(manager.state.value)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `rejoin after the block ended asks now and resumes in the new block`() = runTest {
        val (manager, _) = tunedManager()
        val first = standardBlock()
        manager.hold()
        runCurrent()
        service.nowResponse = Response.success(now(nextOf(first)))
        advanceTimeBy(700_000L)
        val callsBefore = service.nowCalls

        manager.rejoin()
        runCurrent()

        assertEquals(callsBefore + 1, service.nowCalls)
        assertEquals(LiveListenStatus.LISTENING, manager.state.value?.status)
        assertEquals(listOf("b2"), manager.state.value?.timeline?.blocks?.map(LiveBlockInfo::blockId))
    }

    @Test
    fun `schedule over without a new block leaves`() = runTest {
        val (manager, events) = tunedManager()
        service.nowResponse = Response.success(now(null, null))

        advanceTimeBy(650_000L)
        runCurrent()

        assertNull(manager.state.value)
        assertEquals(LiveManagerEvent.Closed(LiveErrors.MESSAGE_ENDED), events.last())
    }

    @Test
    fun `two song changes in five seconds make one request`() = runTest {
        val (manager, _) = tunedManager()
        val callsAfterStart = service.nowCalls
        advanceTimeBy(25_000L)

        manager.onSongChanged()
        runCurrent()
        advanceTimeBy(5_000L)
        manager.onSongChanged()
        runCurrent()

        assertEquals(callsAfterStart + 1, service.nowCalls)
    }

    @Test
    fun `failed slot becomes a gap`() = runTest {
        val (manager, _) = tunedManager()

        manager.markFailed("b1:0")

        assertTrue(manager.state.value!!.timeline.at(BLOCK_START + 1_000L) is LiveCursor.Gap)
    }

    @Test
    fun `leave closes once and stops refreshing`() = runTest {
        val (manager, events) = tunedManager()
        val calls = service.nowCalls

        manager.leave()
        manager.leave()
        advanceTimeBy(lookaheadMs * 2)
        runCurrent()

        assertNull(manager.state.value)
        assertEquals(listOf<LiveManagerEvent>(LiveManagerEvent.Closed(null)), events)
        assertEquals(calls, service.nowCalls)
    }

    @Test
    fun `channel listing is cached for 15 s`() = runTest {
        service.channelsResponse = Response.success(LiveChannelsResponseDTO(serverTimeUtcMs = 1L, channels = emptyList()))
        val (manager, _) = manager()

        manager.loadChannels()
        advanceTimeBy(LiveManager.CHANNELS_CACHE_MS - 1)
        manager.loadChannels()
        assertEquals(1, service.channelsCalls)

        advanceTimeBy(2)
        manager.loadChannels()
        assertEquals(2, service.channelsCalls)
    }
}
