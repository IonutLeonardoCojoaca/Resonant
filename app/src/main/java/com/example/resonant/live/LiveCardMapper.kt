package com.example.resonant.live

import com.example.resonant.data.network.LiveChannelDTO
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A channel card of Explore's "En el momento" row. */
data class LiveCardUi(
    val slug: String,
    val name: String,
    /** "EN DIRECTO" while on air, null otherwise. */
    val badge: String?,
    val subtitle: String,
    val imageUrl: String?
)

/**
 * Turns `GET /api/live/channels` into cards, in server order: on air, upcoming and the specials
 * whose next edition is within [SPECIAL_TEASER_DAYS] days.
 */
object LiveCardMapper {
    const val STATUS_ON_AIR = "on_air"
    const val STATUS_UPCOMING = "upcoming"
    const val STATUS_OFF_AIR = "off_air"
    const val KIND_SPECIAL = "special"
    const val BADGE_LIVE = "EN DIRECTO"

    /** Off-air specials are teased this many days ahead (monthly ones only show the week before). */
    const val SPECIAL_TEASER_DAYS = 7L
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    /** Fixed Spanish abbreviations: the JDK's vary between versions ("vie" / "vie."). */
    val DAYS = mapOf(
        DayOfWeek.MONDAY to "lun", DayOfWeek.TUESDAY to "mar", DayOfWeek.WEDNESDAY to "mié",
        DayOfWeek.THURSDAY to "jue", DayOfWeek.FRIDAY to "vie", DayOfWeek.SATURDAY to "sáb",
        DayOfWeek.SUNDAY to "dom"
    )

    fun map(channels: List<LiveChannelDTO>, nowMs: Long, zone: ZoneId): List<LiveCardUi> =
        channels.mapNotNull { card(it, nowMs, zone) }

    private fun card(channel: LiveChannelDTO, nowMs: Long, zone: ZoneId): LiveCardUi? = when (channel.status) {
        STATUS_ON_AIR -> LiveCardUi(
            slug = channel.slug,
            name = channel.name,
            badge = BADGE_LIVE,
            subtitle = nowPlayingText(channel),
            imageUrl = channel.nowPlaying?.imageUrl
        )
        STATUS_UPCOMING -> channel.nextStartsAtUtcMs?.let { startsAt ->
            LiveCardUi(
                slug = channel.slug,
                name = channel.name,
                badge = null,
                subtitle = "Próximamente · ${startText(startsAt, nowMs, zone)}",
                imageUrl = null
            )
        }
        else -> specialTeaser(channel, nowMs, zone)
    }

    /** "Especial · lun 21:00" for an off-air special whose next edition is in (now, now + 7 d]. */
    private fun specialTeaser(channel: LiveChannelDTO, nowMs: Long, zone: ZoneId): LiveCardUi? {
        if (channel.kind != KIND_SPECIAL || channel.status != STATUS_OFF_AIR) return null
        val next = channel.nextOccurrenceUtcMs ?: return null
        if (next <= nowMs || next > nowMs + SPECIAL_TEASER_DAYS * DAY_MS) return null
        return LiveCardUi(
            slug = channel.slug,
            name = channel.name,
            badge = null,
            subtitle = "Especial · ${startText(next, nowMs, zone)}",
            imageUrl = null
        )
    }

    private fun nowPlayingText(channel: LiveChannelDTO): String {
        val playing = channel.nowPlaying ?: return channel.description.orEmpty()
        val artist = playing.artistName?.takeIf(String::isNotBlank)
        return if (artist == null) playing.title else "${playing.title} · $artist"
    }

    /** "22:00" when it starts today in [zone], "vie 22:00" otherwise. */
    fun startText(startsAtMs: Long, nowMs: Long, zone: ZoneId): String {
        val start = Instant.ofEpochMilli(startsAtMs).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val time = start.format(TIME)
        return if (start.toLocalDate() == today) time else "${DAYS.getValue(start.dayOfWeek)} $time"
    }
}
