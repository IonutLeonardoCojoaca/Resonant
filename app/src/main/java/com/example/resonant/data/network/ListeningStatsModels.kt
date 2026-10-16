package com.example.resonant.data.network

import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.Song

/** `GET api/me/stats/summary` (camelCase del backend). */
data class ListeningSummaryDTO(
    val period: String = "",
    val key: String = "",
    val from: String = "",
    val to: String = "",
    val timeZone: String = "",
    val minutes: Int = 0,
    val previousMinutes: Int = 0,
    val plays: Int = 0,
    val distinctSongs: Int = 0,
    val distinctArtists: Int = 0,
    val activeDays: Int = 0,
    val topSong: TopSongSummaryDTO? = null,
    val topArtist: TopArtistSummaryDTO? = null,
    val topGenre: TopGenreSummaryDTO? = null,
    val genres: List<GenreShareDTO>? = null,
    val insufficientData: Boolean = false
)

data class TopSongSummaryDTO(val song: Song? = null, val plays: Int = 0, val minutes: Int = 0)

data class TopArtistSummaryDTO(val artist: Artist? = null, val plays: Int = 0, val minutes: Int = 0)

data class TopGenreSummaryDTO(val genreId: String = "", val name: String = "", val plays: Int = 0, val minutes: Int = 0)

data class GenreShareDTO(
    val genreId: String = "",
    val name: String = "",
    val share: Double = 0.0,
    val previousShare: Double = 0.0
)

/** `GET api/me/stats/top`: según `type`, cada elemento trae `song`, `artist` o `genreId` + `name`. */
data class ListeningTopDTO(
    val type: String = "",
    val period: String = "",
    val key: String = "",
    val items: List<ListeningTopItemDTO>? = null
)

data class ListeningTopItemDTO(
    val rank: Int = 0,
    val plays: Int = 0,
    val minutes: Int = 0,
    val song: Song? = null,
    val artist: Artist? = null,
    val genreId: String? = null,
    val name: String? = null
)

/** `GET api/me/stats/timeline`: un elemento por día local, incluidos los días a 0. */
data class ListeningTimelineDTO(
    val from: String = "",
    val to: String = "",
    val timeZone: String = "",
    val days: List<ListeningDayDTO>? = null
)

data class ListeningDayDTO(val date: String = "", val minutes: Int = 0)

/** `GET api/me/stats/on-this-day`. */
data class OnThisDayDTO(
    val referenceDate: String = "",
    val items: List<OnThisDayItemDTO>? = null
)

data class OnThisDayItemDTO(val plays: Int = 0, val song: Song? = null)

/** `GET api/me/history`. */
data class ListeningHistoryPageDTO(
    val items: List<ListeningHistoryItemDTO>? = null,
    val nextCursor: String? = null
)

data class ListeningHistoryItemDTO(
    val id: String = "",
    val playedAtUtc: String = "",
    val listenedSeconds: Int = 0,
    val wasSkipped: Boolean = false,
    val source: String = "",
    val song: Song? = null
)
