package com.example.resonant.data.network

/**
 * Notification inbox contract (`api/notifications*`), camelCase as served by the backend.
 * Dates are Unix milliseconds (UTC). Gson can leave non-null fields null on a malformed item,
 * so callers read them defensively.
 */
data class NotificationItemDTO(
    val id: String = "",
    /** One of [NotificationCategories.ALL]; an unknown value is shown in the general channel. */
    val category: String = "",
    val title: String = "",
    val body: String? = null,
    /** Internal path such as `/live/{slug}`; see `NotificationDeepLink`. */
    val deepLink: String? = null,
    /** Stable public URL (never a signed one), or null. */
    val imageUrl: String? = null,
    /** True when the item deserves a system notification outside the app. */
    val alert: Boolean = false,
    val createdAtUtcMs: Long = 0L,
    val readAtUtcMs: Long? = null,
    val expiresAtUtcMs: Long? = null
)

/** `GET api/notifications?cursor=&limit=`: newest first; [nextCursor] is null on the last page. */
data class NotificationPageDTO(
    val items: List<NotificationItemDTO>? = null,
    val nextCursor: String? = null
)

/** `GET api/notifications/unread-count`. */
data class NotificationUnreadCountDTO(
    val unread: Int = 0
)

/** `POST api/notifications/read-all` body; without [beforeUtcMs] (sent as `{}`) marks everything. */
data class NotificationReadAllRequest(
    val beforeUtcMs: Long? = null
)

/** `200` of `POST api/notifications/read-all`. */
data class NotificationReadAllResultDTO(
    val updated: Int = 0
)

/**
 * Body and response of `GET`/`PUT api/notifications/settings`. A `PUT` sends only the changed
 * categories (`{ "categories": { "live": false } }`) and the server answers with all of them.
 */
data class NotificationSettingsDTO(
    val categories: Map<String, Boolean>? = null
)

/** Categories known by the backend (`NotificationCategories` in ResonantBackend). */
object NotificationCategories {
    const val ARIA = "aria"
    const val UNISOUND = "unisound"
    const val LIVE = "live"
    const val STREAK = "streak"
    const val SONG_OF_DAY = "song_of_day"
    const val APP_UPDATE = "app_update"
    const val RECAP = "recap"
    const val RELEASE = "release"

    val ALL = listOf(ARIA, UNISOUND, LIVE, STREAK, SONG_OF_DAY, APP_UPDATE, RECAP, RELEASE)

    /** Categories with a producer today: the ones with a switch in Settings. */
    val WITH_SWITCH = listOf(ARIA, UNISOUND, LIVE, STREAK, SONG_OF_DAY, APP_UPDATE)
}
