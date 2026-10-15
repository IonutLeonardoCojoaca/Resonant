package com.example.resonant.data.network

import com.google.gson.JsonElement

/**
 * Listening streak and song of the day contract (`api/me/streak*`, `api/me/song-of-the-day` and the
 * body of `POST api/analytics/stream`), camelCase as served by the backend.
 */
data class StreakStatusDTO(
    val currentDays: Int = 0,
    val longestDays: Int = 0,
    val todaySeconds: Int = 0,
    val goalSeconds: Int = 0,
    val todayQualified: Boolean = false,
    /** Free weekly freezes for the current ISO week (0 or 1). */
    val freezesAvailable: Int = 0,
    val freezeCoveringYesterday: Boolean = false,
    /** Null past the last milestone (365). */
    val nextMilestone: Int? = null,
    /** Local day of the user, `yyyy-MM-dd`. */
    val date: String = "",
    val timeZone: String = ""
)

data class StreakSettingsRequest(val timeZone: String)

/** `type` is `qualified_today` or `milestone`. */
data class StreakEventDTO(
    val type: String? = null,
    val days: Int = 0
)

/** `POST api/analytics/stream` body: `{}` or with [streakEvent]; empty when the flag is off. */
data class StreamResultDTO(
    val streakEvent: StreakEventDTO? = null
)

/** `200` of `GET api/me/song-of-the-day`. [song] stays raw so it decodes like a Home song. */
data class SongOfDayDTO(
    val song: JsonElement? = null,
    val reason: String? = null,
    /** `aria` or `resonant_ai`. */
    val origin: String? = null,
    val date: String? = null
)

/** `202` of `GET api/me/song-of-the-day` while Aria prepares today's picks. */
data class SongOfDayPendingDTO(
    val status: String? = null,
    val retryAfterSeconds: Int? = null
)
