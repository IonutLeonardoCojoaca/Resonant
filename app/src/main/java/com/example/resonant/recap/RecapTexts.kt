package com.example.resonant.recap

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Textos del resumen mensual en español. Puro, sin recursos de Android, para poder testearlo. */
object RecapTexts {
    val SLOTS = setOf("dawn", "morning", "afternoon", "night")

    private val MONTHS = listOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio",
        "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"
    )

    private val SLOT_NAMES = mapOf(
        "dawn" to "la madrugada",
        "morning" to "la mañana",
        "afternoon" to "la tarde",
        "night" to "la noche"
    )

    /** Mes 1-12 en minúsculas; vacío si está fuera de rango. */
    fun monthName(month: Int): String = MONTHS.getOrNull(month - 1).orEmpty()

    /** (año, mes) de `yyyy-MM`, o null si no es válido. */
    fun parsePeriod(periodKey: String): Pair<Int, Int>? {
        val match = PERIOD.matchEntire(periodKey) ?: return null
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        return if (month in 1..12) year to month else null
    }

    /** "septiembre" para `2026-09`. */
    fun monthOf(periodKey: String): String = parsePeriod(periodKey)?.let { monthName(it.second) }.orEmpty()

    /** "Septiembre 2026" para el historial. */
    fun periodTitle(periodKey: String): String {
        val (year, month) = parsePeriod(periodKey) ?: return periodKey
        return "${capitalize(monthName(month))} $year"
    }

    /** "Tu septiembre ya está aquí". */
    fun bannerTitle(periodKey: String): String = "Tu ${monthOf(periodKey)} ya está aquí"

    /** "+38 % que agosto", "-12 % que agosto"; null sin mes anterior. */
    fun change(changePercent: Int?, periodKey: String): String? {
        changePercent ?: return null
        val (_, month) = parsePeriod(periodKey) ?: return null
        val previous = if (month == 1) monthName(12) else monthName(month - 1)
        val sign = if (changePercent > 0) "+" else if (changePercent < 0) "-" else ""
        return "$sign${abs(changePercent)} % que $previous"
    }

    /** "la noche". */
    fun slotName(slot: String): String = SLOT_NAMES[slot].orEmpty()

    /** "Tu momento: la noche". */
    fun moment(slot: String): String = "Tu momento: ${slotName(slot)}"

    /** 0.41 → "41 %". */
    fun percent(share: Double): String = "${(share * 100).roundToInt()} %"

    fun minutes(count: Int): String = plural(count, "minuto", "minutos")

    fun plays(count: Int): String = plural(count, "reproducción", "reproducciones")

    fun days(count: Int): String = plural(count, "día", "días")

    fun sessions(count: Int): String = plural(count, "sesión", "sesiones")

    fun people(count: Int): String = plural(count, "persona", "personas")

    fun songs(count: Int): String = plural(count, "canción", "canciones")

    /** "9 días seguidos" / "1 día". */
    fun streak(days: Int): String = if (days == 1) "1 día" else "$days días seguidos"

    /** "24 de 30 días con música". */
    fun daysListened(listened: Int, inMonth: Int): String = "$listened de $inMonth días con música"

    /** "3 sesiones con 2 personas". */
    fun unisound(sessions: Int, people: Int): String =
        if (people > 0) "${sessions(sessions)} con ${people(people)}" else sessions(sessions)

    private fun plural(count: Int, one: String, many: String): String =
        "${formatNumber(count)} ${if (count == 1) one else many}"

    private fun formatNumber(value: Int): String = String.format(Locale("es", "ES"), "%,d", value)

    private fun capitalize(value: String): String =
        value.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("es", "ES")) else it.toString() }

    private val PERIOD = Regex("""^(\d{4})-(\d{2})$""")
}
