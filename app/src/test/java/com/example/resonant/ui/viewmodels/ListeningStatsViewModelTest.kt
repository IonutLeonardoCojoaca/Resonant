package com.example.resonant.ui.viewmodels

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.ListeningDayDTO
import com.example.resonant.data.network.ListeningSummaryDTO
import com.example.resonant.data.network.ListeningTimelineDTO
import com.example.resonant.data.network.ListeningTopDTO
import com.example.resonant.data.network.ListeningTopItemDTO
import com.example.resonant.data.network.OnThisDayDTO
import com.example.resonant.data.network.OnThisDayItemDTO
import com.example.resonant.managers.ListeningStatsSource
import com.example.resonant.managers.StatsResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class ListeningStatsViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val today = LocalDate.of(2026, 10, 15)
    private lateinit var source: FakeStatsSource

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        source = FakeStatsSource()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads the current month by default`() {
        val viewModel = ListeningStatsViewModel(source) { today }

        val state = viewModel.state.value!!
        assertEquals(StatsPeriod.MONTH, state.period)
        assertEquals("month" to "2026-10", source.summaryCalls.last())
        assertFalse(state.isLoading)
        assertEquals(1408, state.summary?.minutes)
        assertEquals(-251, state.minutesDelta)
        assertEquals(listOf("s1"), state.topSongs.mapNotNull { it.song?.id })
        assertEquals(listOf(74, 0, 41), state.bars.map { it.minutes })
        assertFalse(state.canGoNext)
    }

    @Test
    fun `switching to week asks for the current ISO week without resetting`() {
        val viewModel = ListeningStatsViewModel(source) { today }

        viewModel.selectPeriod(StatsPeriod.WEEK)

        assertEquals("week" to "2026-W42", source.summaryCalls.last())
        assertEquals(StatsPeriod.WEEK, viewModel.state.value!!.period)
    }

    @Test
    fun `previous period navigation and back`() {
        val viewModel = ListeningStatsViewModel(source) { today }

        viewModel.previousPeriod()
        assertEquals("month" to "2026-09", source.summaryCalls.last())
        assertTrue(viewModel.state.value!!.canGoNext)

        viewModel.nextPeriod()
        assertEquals("month" to "2026-10", source.summaryCalls.last())
        viewModel.nextPeriod()
        assertEquals(3, source.summaryCalls.size)
    }

    @Test
    fun `insufficient data keeps the numbers`() {
        source.summary = source.summary.copy(insufficientData = true, activeDays = 2)

        val state = ListeningStatsViewModel(source) { today }.state.value!!

        assertTrue(state.summary!!.insufficientData)
        assertEquals(1408, state.summary!!.minutes)
    }

    @Test
    fun `network error shows retry and retry recovers`() {
        source.failSummary = true
        val viewModel = ListeningStatsViewModel(source) { today }
        assertTrue(viewModel.state.value!!.isError)

        source.failSummary = false
        viewModel.retry()

        assertFalse(viewModel.state.value!!.isError)
        assertEquals(1408, viewModel.state.value!!.summary?.minutes)
    }

    @Test
    fun `disabled feature is reported`() {
        source.disabled = true

        val state = ListeningStatsViewModel(source) { today }.state.value!!

        assertTrue(state.isDisabled)
        assertFalse(state.isError)
    }

    @Test
    fun `empty on this day hides memories`() {
        source.memories = emptyList()

        val state = ListeningStatsViewModel(source) { today }.state.value!!

        assertTrue(state.onThisDay.isEmpty())
    }

    @Test
    fun `year aggregates the timeline by month`() {
        val viewModel = ListeningStatsViewModel(source) { today }

        viewModel.selectPeriod(StatsPeriod.YEAR)

        assertEquals(12, viewModel.state.value!!.bars.size)
    }

    private class FakeStatsSource : ListeningStatsSource {
        var summary = ListeningSummaryDTO(
            period = "month", key = "2026-10", from = "2026-10-01", to = "2026-10-03",
            minutes = 1408, previousMinutes = 1659, activeDays = 14
        )
        var failSummary = false
        var disabled = false
        var memories = listOf(OnThisDayItemDTO(6, Song(id = "old")))
        val summaryCalls = mutableListOf<Pair<String, String?>>()

        override suspend fun summary(period: String, key: String?): StatsResult<ListeningSummaryDTO> {
            summaryCalls += period to key
            return when {
                disabled -> StatsResult.Disabled
                failSummary -> StatsResult.Failed
                else -> StatsResult.Ok(summary)
            }
        }

        override suspend fun top(type: String, period: String, key: String?, limit: Int): StatsResult<ListeningTopDTO> =
            StatsResult.Ok(
                ListeningTopDTO(
                    type, period, key.orEmpty(),
                    if (type == "songs") listOf(ListeningTopItemDTO(rank = 1, plays = 23, minutes = 81, song = Song(id = "s1")))
                    else emptyList()
                )
            )

        override suspend fun timeline(from: String, to: String): StatsResult<ListeningTimelineDTO> =
            StatsResult.Ok(
                ListeningTimelineDTO(
                    from, to, "Europe/Madrid",
                    listOf(ListeningDayDTO("2026-10-01", 74), ListeningDayDTO("2026-10-02", 0), ListeningDayDTO("2026-10-03", 41))
                )
            )

        override suspend fun onThisDay(): StatsResult<OnThisDayDTO> =
            StatsResult.Ok(OnThisDayDTO("2025-10-15", memories))
    }
}
