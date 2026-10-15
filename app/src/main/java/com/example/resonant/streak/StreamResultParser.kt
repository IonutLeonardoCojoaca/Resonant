package com.example.resonant.streak

import com.example.resonant.data.network.StreamResultDTO
import com.google.gson.Gson

/**
 * Reads the optional `streakEvent` from the body of `POST api/analytics/stream`. An empty body
 * (flag off), `{}`, broken JSON or an unknown `type` mean "no event".
 */
object StreamResultParser {
    private const val TYPE_QUALIFIED_TODAY = "qualified_today"
    private const val TYPE_MILESTONE = "milestone"
    private val gson = Gson()

    fun parse(rawBody: String?): StreakEvent? {
        if (rawBody.isNullOrBlank()) return null
        val event = runCatching { gson.fromJson(rawBody, StreamResultDTO::class.java) }
            .getOrNull()
            ?.streakEvent
            ?: return null
        if (event.days <= 0) return null
        return when (event.type) {
            TYPE_QUALIFIED_TODAY -> StreakEvent.QualifiedToday(event.days)
            TYPE_MILESTONE -> StreakEvent.Milestone(event.days)
            else -> null
        }
    }
}
