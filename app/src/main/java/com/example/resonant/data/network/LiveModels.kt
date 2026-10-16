package com.example.resonant.data.network

import com.example.resonant.data.models.Song

/**
 * Resonant Live contract (`api/live/...`), camelCase as served by the backend. Everything the server
 * may send as null is nullable; Gson ignores unknown fields, so additive server changes never break parsing.
 */
data class LiveChannelsResponseDTO(
    val serverTimeUtcMs: Long = 0L,
    val channels: List<LiveChannelDTO>? = null
)

data class LiveChannelDTO(
    val channelId: String = "",
    val slug: String = "",
    /** channel (24/7) | special */
    val kind: String = "",
    val name: String = "",
    val description: String? = null,
    /** on_air | upcoming | off_air */
    val status: String = "",
    val nowPlaying: LiveNowPlayingDTO? = null,
    val currentBlock: LiveBlockSummaryDTO? = null,
    val nextStartsAtUtcMs: Long? = null,
    /** Declared schedule of a special; null for 24/7 channels and on older servers. */
    val schedule: LiveScheduleDTO? = null,
    /** Next edition according to the schedule (up to 400 days ahead), even if its block does not exist yet. */
    val nextOccurrenceUtcMs: Long? = null
)

/** Normalised schedule of a special. Dates are local `YYYY-MM-DD` of [timeZone]. */
data class LiveScheduleDTO(
    val timeZone: String = "",
    /** ISO days (1 = Monday … 7 = Sunday). */
    val weekdays: List<Int>? = null,
    /** Null for a special made only of one-off [dates]. Parsed for later use; not shown yet. */
    val recurrence: LiveRecurrenceDTO? = null,
    val dates: List<String>? = null,
    val exceptDates: List<String>? = null,
    /** Local start "HH:mm". */
    val startLocal: String = "",
    val durationMinutes: Int = 0
)

/** daily | weekly | monthly, every [interval] units. */
data class LiveRecurrenceDTO(
    val frequency: String = "",
    val interval: Int = 1,
    val anchorDate: String? = null,
    val monthDay: Int? = null,
    val monthWeekday: LiveMonthWeekdayDTO? = null
)

/** [week] 1…5 or -1 (last); [weekday] ISO 1…7. */
data class LiveMonthWeekdayDTO(
    val week: Int = 0,
    val weekday: Int = 0
)

data class LiveNowPlayingDTO(
    val songId: String = "",
    val title: String = "",
    val artistName: String? = null,
    val imageUrl: String? = null
)

data class LiveBlockSummaryDTO(
    val blockId: String = "",
    val title: String = "",
    val startsAtUtcMs: Long = 0L,
    val endsAtUtcMs: Long = 0L
)

/** `GET api/live/channels/{slug}/now`: off air is a 200 with `block: null`, never an error. */
data class LiveNowResponseDTO(
    val serverTimeUtcMs: Long = 0L,
    val status: String = "",
    val channel: LiveChannelInfoDTO? = null,
    val block: LiveBlockDTO? = null,
    val nextBlock: LiveBlockDTO? = null
)

data class LiveChannelInfoDTO(
    val channelId: String = "",
    val slug: String = "",
    val kind: String = "",
    val name: String = "",
    val description: String? = null,
    val schedule: LiveScheduleDTO? = null,
    val nextOccurrenceUtcMs: Long? = null
)

data class LiveBlockDTO(
    val blockId: String = "",
    val title: String = "",
    val startsAtUtcMs: Long = 0L,
    val endsAtUtcMs: Long = 0L,
    val items: List<LiveBlockItemDTO>? = null
)

data class LiveBlockItemDTO(
    val position: Int = 0,
    val startOffsetMs: Long = 0L,
    val durationMs: Long = 0L,
    /** Aria DJ intro; null until that feature exists. */
    val djIntro: String? = null,
    /** Listing format without audio URL; null when the song no longer exists (its slot stays). */
    val song: Song? = null
)
