package com.example.resonant.ui.customviews

import com.example.resonant.data.network.ListeningDayDTO
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Barra del gráfico de minutos. */
data class TimelineBar(val label: String, val minutes: Int)

/**
 * Convierte los días de `api/me/stats/timeline` en barras: una por día (Semana y Mes) o una por mes (Año).
 * Puro: no depende de Android.
 */
object TimelineBuckets {

    private val WEEKDAY_INITIALS = listOf("L", "M", "X", "J", "V", "S", "D")
    private val MONTH_INITIALS = listOf("E", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")

    fun byDay(days: List<ListeningDayDTO>, weekdayLabels: Boolean): List<TimelineBar> =
        days.mapNotNull { day ->
            val date = parse(day.date) ?: return@mapNotNull null
            val label = if (weekdayLabels) WEEKDAY_INITIALS[date.dayOfWeek.value - 1] else date.dayOfMonth.toString()
            TimelineBar(label, day.minutes.coerceAtLeast(0))
        }

    /** Suma los minutos por mes del año; siempre 12 barras (enero-diciembre). */
    fun byMonth(days: List<ListeningDayDTO>): List<TimelineBar> {
        val totals = IntArray(12)
        days.forEach { day ->
            val date = parse(day.date) ?: return@forEach
            totals[date.monthValue - 1] += day.minutes.coerceAtLeast(0)
        }
        return totals.mapIndexed { index, minutes -> TimelineBar(MONTH_INITIALS[index], minutes) }
    }

    private fun parse(value: String): LocalDate? =
        try {
            LocalDate.parse(value)
        } catch (e: DateTimeParseException) {
            null
        }
}
