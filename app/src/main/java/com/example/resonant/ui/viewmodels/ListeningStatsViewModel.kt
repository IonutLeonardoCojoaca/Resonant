package com.example.resonant.ui.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.ListeningSummaryDTO
import com.example.resonant.data.network.ListeningTopItemDTO
import com.example.resonant.managers.ListeningStatsSource
import com.example.resonant.managers.StatsResult
import com.example.resonant.ui.customviews.TimelineBar
import com.example.resonant.ui.customviews.TimelineBuckets
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class StatsPeriod(val apiValue: String) { WEEK("week"), MONTH("month"), YEAR("year") }

/** Claves y etiquetas de periodo (semana ISO, mes, año). Puro. */
object StatsPeriodKeys {
    private val spanish = Locale("es", "ES")

    fun key(period: StatsPeriod, anchor: LocalDate): String = when (period) {
        StatsPeriod.WEEK -> String.format(
            Locale.ROOT, "%04d-W%02d",
            anchor.get(IsoFields.WEEK_BASED_YEAR), anchor.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        )
        StatsPeriod.MONTH -> String.format(Locale.ROOT, "%04d-%02d", anchor.year, anchor.monthValue)
        StatsPeriod.YEAR -> String.format(Locale.ROOT, "%04d", anchor.year)
    }

    fun label(period: StatsPeriod, anchor: LocalDate): String = when (period) {
        StatsPeriod.WEEK -> {
            val monday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            "Semana del ${monday.dayOfMonth} ${monday.month.getDisplayName(TextStyle.SHORT, spanish).trimEnd('.')}"
        }
        StatsPeriod.MONTH ->
            "${anchor.month.getDisplayName(TextStyle.FULL_STANDALONE, spanish).replaceFirstChar { it.uppercase() }} ${anchor.year}"
        StatsPeriod.YEAR -> anchor.year.toString()
    }

    fun previous(period: StatsPeriod, anchor: LocalDate): LocalDate = when (period) {
        StatsPeriod.WEEK -> anchor.minusWeeks(1)
        StatsPeriod.MONTH -> anchor.minusMonths(1)
        StatsPeriod.YEAR -> anchor.minusYears(1)
    }

    fun next(period: StatsPeriod, anchor: LocalDate): LocalDate = when (period) {
        StatsPeriod.WEEK -> anchor.plusWeeks(1)
        StatsPeriod.MONTH -> anchor.plusMonths(1)
        StatsPeriod.YEAR -> anchor.plusYears(1)
    }

    fun isCurrent(period: StatsPeriod, anchor: LocalDate, today: LocalDate): Boolean =
        key(period, anchor) == key(period, today)
}

data class ListeningStatsUiState(
    val period: StatsPeriod = StatsPeriod.MONTH,
    val periodLabel: String = "",
    val canGoNext: Boolean = false,
    val isLoading: Boolean = true,
    val isError: Boolean = false,
    val isDisabled: Boolean = false,
    val summary: ListeningSummaryDTO? = null,
    val topSongs: List<ListeningTopItemDTO> = emptyList(),
    val topArtists: List<ListeningTopItemDTO> = emptyList(),
    val topGenres: List<ListeningTopItemDTO> = emptyList(),
    val bars: List<TimelineBar> = emptyList(),
    val onThisDay: List<Song> = emptyList()
) {
    /** Diferencia de minutos frente al periodo anterior. */
    val minutesDelta: Int get() = (summary?.minutes ?: 0) - (summary?.previousMinutes ?: 0)
}

/** Pantalla "Tus estadísticas": periodo Semana/Mes/Año, navegación al anterior y datos de `api/me/stats/…`. */
class ListeningStatsViewModel(
    private val source: ListeningStatsSource,
    private val today: () -> LocalDate = { LocalDate.now() }
) : ViewModel() {

    private var anchor: LocalDate = today()
    private val _state = MutableLiveData(stateFor(StatsPeriod.MONTH))
    val state: LiveData<ListeningStatsUiState> get() = _state
    private var loadJob: Job? = null

    init {
        load()
    }

    fun selectPeriod(period: StatsPeriod) {
        if (period == current().period) return
        anchor = today()
        _state.value = stateFor(period)
        load()
    }

    fun previousPeriod() {
        val period = current().period
        anchor = StatsPeriodKeys.previous(period, anchor)
        _state.value = stateFor(period)
        load()
    }

    fun nextPeriod() {
        val period = current().period
        if (StatsPeriodKeys.isCurrent(period, anchor, today())) return
        anchor = StatsPeriodKeys.next(period, anchor)
        _state.value = stateFor(period)
        load()
    }

    fun retry() = load()

    private fun current(): ListeningStatsUiState = _state.value ?: stateFor(StatsPeriod.MONTH)

    private fun stateFor(period: StatsPeriod) = ListeningStatsUiState(
        period = period,
        periodLabel = StatsPeriodKeys.label(period, anchor),
        canGoNext = !StatsPeriodKeys.isCurrent(period, anchor, today())
    )

    private fun load() {
        val period = current().period
        val key = StatsPeriodKeys.key(period, anchor)
        _state.value = current().copy(isLoading = true, isError = false, isDisabled = false)
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            when (val summary = source.summary(period.apiValue, key)) {
                is StatsResult.Ok -> loadDetails(period, key, summary.data)
                StatsResult.Disabled -> _state.value = current().copy(isLoading = false, isDisabled = true)
                StatsResult.Failed -> _state.value = current().copy(isLoading = false, isError = true)
            }
        }
    }

    private suspend fun loadDetails(period: StatsPeriod, key: String, summary: ListeningSummaryDTO) = coroutineScope {
        val songs = async { source.top("songs", period.apiValue, key, TOP_LIMIT) }
        val artists = async { source.top("artists", period.apiValue, key, TOP_LIMIT) }
        val genres = async { source.top("genres", period.apiValue, key, TOP_LIMIT) }
        val timeline = async {
            if (summary.from.isBlank() || summary.to.isBlank()) null else source.timeline(summary.from, summary.to)
        }
        val memories = async { source.onThisDay() }

        val days = (timeline.await() as? StatsResult.Ok)?.data?.days.orEmpty()
        _state.value = current().copy(
            isLoading = false,
            summary = summary,
            topSongs = songs.await().items(),
            topArtists = artists.await().items(),
            topGenres = genres.await().items(),
            bars = if (period == StatsPeriod.YEAR) TimelineBuckets.byMonth(days)
            else TimelineBuckets.byDay(days, weekdayLabels = period == StatsPeriod.WEEK),
            onThisDay = (memories.await() as? StatsResult.Ok)?.data?.items.orEmpty().mapNotNull { it.song }
        )
    }

    private fun StatsResult<com.example.resonant.data.network.ListeningTopDTO>.items(): List<ListeningTopItemDTO> =
        (this as? StatsResult.Ok)?.data?.items.orEmpty()

    companion object {
        const val TOP_LIMIT = 5
    }
}

class ListeningStatsViewModelFactory(private val source: ListeningStatsSource) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ListeningStatsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ListeningStatsViewModel(source) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
