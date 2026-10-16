package com.example.resonant.aria

/**
 * V2 part of `client_context` for `POST /api/aria/ask` (change `add-aria-context-v2`).
 * Pure: the caller takes the snapshot (screen, playback, Live, Unisound) and turns the maps into
 * JSON, so this is testable on the JVM without Android.
 */
object AriaClientContextV2 {
    const val SCHEMA_VERSION = 2
    const val PLATFORM = "android"

    private const val ID_MAX_LENGTH = 40
    private const val VERSION_MAX_LENGTH = 40
    private const val TIME_ZONE_MAX_LENGTH = 64

    /** Values of `client_context.screen` the backend accepts (V1 + V2). */
    val SCREEN_VALUES = setOf(
        "home", "search", "library", "player", "stats", "playlist_detail",
        "artist_detail", "album_detail", "song_detail", "aria_chat", "settings",
        "explore", "live_channel", "unisound_room"
    )

    /** Values of `client_context.visible_entity.type` (V1 + `live_channel`, whose id is the slug). */
    val ENTITY_TYPES = setOf("playlist", "artist", "album", "song", "genre", "live_channel")

    /** Families of actions this version can run or show. Fixed per version: the backend intersects. */
    val V2A_CAPABILITIES = listOf(
        "unisound", "live", "flow", "explore", "streak", "aria_picks", "navigation"
    )
    const val CAPABILITY_LISTENING_STATS = "listening_stats"
    const val CAPABILITY_MONTHLY_RECAP = "monthly_recap"

    /**
     * Whether this build has the "Tus estadísticas" screen (`add-listening-stats`) and the monthly
     * recap screen (`add-monthly-recap`). Only then are their capabilities declared and
     * `open_screen` with `stats`/`recap` accepted. Both screens ship in this version
     * (`ListeningStatsFragment`, `RecapStoriesFragment`); the backend still intersects with its flags.
     */
    const val HAS_LISTENING_STATS_SCREEN = true
    const val HAS_MONTHLY_RECAP_SCREEN = true

    /** `playback.source_type` values of the contract; anything else is sent as `UNKNOWN`. */
    val PLAYBACK_SOURCE_TYPES = setOf(
        "ALBUM", "PLAYLIST", "SEARCH", "HOME", "FAVORITE_SONGS", "TOP_SONGS_ARTIST",
        "COLLAB_FINDER", "PLAYMIX", "DOWNLOADED_SONGS", "QUEUE", "RELATED_SONGS", "UNISOUND",
        "EXPLORE", "ARIA_PICK", "LIVE", "SONG_OF_DAY", "STATS", "FLOW", "UNKNOWN"
    )

    fun capabilities(
        hasListeningStats: Boolean = HAS_LISTENING_STATS_SCREEN,
        hasMonthlyRecap: Boolean = HAS_MONTHLY_RECAP_SCREEN
    ): List<String> = buildList {
        addAll(V2A_CAPABILITIES)
        if (hasListeningStats) add(CAPABILITY_LISTENING_STATS)
        if (hasMonthlyRecap) add(CAPABILITY_MONTHLY_RECAP)
    }

    /** What is playing now; null when nothing is (then neither `playback` nor `now_playing` go). */
    data class Playback(
        /** Name of the queue source (`QueueSource.name`), or `FLOW` for a Flow entry. */
        val sourceType: String?,
        val sourceId: String? = null,
        val reasonCode: String? = null
    )

    data class Input(
        val appVersion: String?,
        val timeZone: String?,
        val playback: Playback? = null,
        val unisoundSessionId: String? = null,
        /** Slug of the Live broadcast this device is tuned to. */
        val liveChannelSlug: String? = null,
        val capabilities: List<String> = capabilities()
    )

    /**
     * Top-level V2 fields: `schema_version`, `client`, `time_zone` and, when something plays,
     * `playback`. Values are String, Int, List or nested Map.
     */
    fun fields(input: Input): Map<String, Any> {
        val fields = LinkedHashMap<String, Any>()
        fields["schema_version"] = SCHEMA_VERSION
        val client = LinkedHashMap<String, Any>()
        client["platform"] = PLATFORM
        input.appVersion.clean(VERSION_MAX_LENGTH)?.let { client["version"] = it }
        client["capabilities"] = input.capabilities
        fields["client"] = client
        input.timeZone.clean(TIME_ZONE_MAX_LENGTH)?.let { fields["time_zone"] = it }
        playbackFields(input.playback, input.liveChannelSlug)?.let { fields["playback"] = it }
        return fields
    }

    /**
     * `client_context.session`. A Unisound room wins: inside one the device plays the room, so
     * `live_channel_slug` is never sent together with `unisound_session_id`.
     */
    fun sessionFields(unisoundSessionId: String?, liveChannelSlug: String?): Map<String, String>? {
        AriaUnisoundContext.sessionFields(unisoundSessionId)?.let { return it }
        val slug = liveChannelSlug.clean(ID_MAX_LENGTH) ?: return null
        return mapOf("live_channel_slug" to slug)
    }

    fun playbackFields(playback: Playback?, liveChannelSlug: String? = null): Map<String, String>? {
        playback ?: return null
        val type = playback.sourceType?.trim()?.uppercase()
            ?.takeIf { it in PLAYBACK_SOURCE_TYPES } ?: "UNKNOWN"
        val result = LinkedHashMap<String, String>()
        result["source_type"] = type
        val sourceId = if (type == "LIVE") liveChannelSlug.clean(ID_MAX_LENGTH) ?: playback.sourceId
        else playback.sourceId
        sourceId.clean(ID_MAX_LENGTH)?.let { result["source_id"] = it }
        playback.reasonCode.clean(ID_MAX_LENGTH)?.let { result["reason_code"] = it }
        return result
    }

    private fun String?.clean(maxLength: Int): String? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.take(maxLength)
}
