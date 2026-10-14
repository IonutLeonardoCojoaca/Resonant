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
    val nextStartsAtUtcMs: Long? = null
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
    val description: String? = null
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
