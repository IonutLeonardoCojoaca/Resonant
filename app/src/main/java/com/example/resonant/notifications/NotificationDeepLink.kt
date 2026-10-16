package com.example.resonant.notifications

/** Where a notification's `deepLink` leads. [None] means "stay in the inbox". */
sealed class NotificationTarget {
    data class Song(val songId: String) : NotificationTarget()
    data class Playlist(val playlistId: String) : NotificationTarget()
    data class Artist(val artistId: String) : NotificationTarget()
    data class Album(val albumId: String) : NotificationTarget()
    data class Live(val slug: String) : NotificationTarget()
    data class UnisoundSummary(val sessionId: String) : NotificationTarget()
    /** Monthly recap, `yyyy-MM`. */
    data class Recap(val periodKey: String) : NotificationTarget()
    data class Update(val version: String) : NotificationTarget()
    object Streak : NotificationTarget()
    object None : NotificationTarget()
}

/**
 * Translates the closed list of internal links accepted by the backend (`NotificationDeepLinks`)
 * into a [NotificationTarget]. Anything else (null, absolute URLs, unknown routes, malformed ids)
 * is [NotificationTarget.None], so an older app never breaks on a destination it does not know.
 */
object NotificationDeepLink {
    private val GUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val SLUG = Regex("^[a-z0-9-]{1,48}$")
    private val PERIOD = Regex("^[0-9]{4}-(0[1-9]|1[0-2])$")
    private val VERSION = Regex("^[0-9A-Za-z._-]{1,50}$")

    fun parse(link: String?): NotificationTarget {
        if (link.isNullOrBlank() || !link.startsWith("/") || link.startsWith("//")) return NotificationTarget.None
        val segments = link.substring(1).split("/")
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return NotificationTarget.None
        return when (segments.size) {
            1 -> if (segments[0] == "streak") NotificationTarget.Streak else NotificationTarget.None
            2 -> parseTwo(segments[0], segments[1])
            3 -> if (segments[0] == "unisound" && segments[2] == "summary" && GUID.matches(segments[1])) {
                NotificationTarget.UnisoundSummary(segments[1])
            } else {
                NotificationTarget.None
            }
            else -> NotificationTarget.None
        }
    }

    private fun parseTwo(kind: String, value: String): NotificationTarget = when (kind) {
        "song" -> if (GUID.matches(value)) NotificationTarget.Song(value) else NotificationTarget.None
        "playlist" -> if (GUID.matches(value)) NotificationTarget.Playlist(value) else NotificationTarget.None
        "artist" -> if (GUID.matches(value)) NotificationTarget.Artist(value) else NotificationTarget.None
        "album" -> if (GUID.matches(value)) NotificationTarget.Album(value) else NotificationTarget.None
        "live" -> if (SLUG.matches(value)) NotificationTarget.Live(value) else NotificationTarget.None
        "recap" -> if (PERIOD.matches(value)) NotificationTarget.Recap(value) else NotificationTarget.None
        "update" -> if (VERSION.matches(value)) NotificationTarget.Update(value) else NotificationTarget.None
        else -> NotificationTarget.None
    }
}
