package com.example.resonant.streak

import com.example.resonant.data.network.StreakStatusDTO

/** User-facing streak texts, kept out of the views so they can be tested without a Context. */
object StreakTexts {

    fun chip(status: StreakStatusDTO): String = "🔥 ${status.currentDays}"

    fun chipDescription(status: StreakStatusDTO): String {
        val today = if (status.todayQualified) "hoy completado" else "hoy pendiente"
        return "Racha de ${days(status.currentDays)}; $today"
    }

    fun celebration(days: Int): String = "🔥 ¡$days días seguidos!"

    /** Minutes are rounded down: "4 de 10 min hoy". */
    fun todayProgress(status: StreakStatusDTO): String =
        "${status.todaySeconds / 60} de ${status.goalSeconds / 60} min hoy"

    fun longest(status: StreakStatusDTO): String = "Racha más larga: ${days(status.longestDays)}"

    fun freeze(status: StreakStatusDTO): String = when {
        status.freezeCoveringYesterday -> "Tu protector cubre ayer: escucha hoy para no perder la racha"
        status.freezesAvailable >= 1 -> "Protector disponible esta semana"
        else -> "Protector usado esta semana"
    }

    /** Null past the last milestone. */
    fun nextMilestone(status: StreakStatusDTO): String? =
        status.nextMilestone?.let { "Siguiente hito: ${days(it)}" }

    private fun days(count: Int): String = if (count == 1) "1 día" else "$count días"
}
