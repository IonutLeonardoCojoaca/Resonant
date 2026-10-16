package com.example.resonant.live

import com.example.resonant.data.network.LiveChannelDTO
import com.example.resonant.data.network.LiveNowPlayingDTO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class LiveCardMapperTest {
    private val madrid = ZoneId.of("Europe/Madrid")

    private fun at(text: String) = ZonedDateTime.of(LocalDateTime.parse(text), madrid).toInstant().toEpochMilli()

    private fun onAir(slug: String, artist: String? = "The Killers") = LiveChannelDTO(
        channelId = slug, slug = slug, kind = "channel", name = "Resonant Rock", status = "on_air",
        nowPlaying = LiveNowPlayingDTO("s1", "Mr. Brightside", artist, "https://x/cover.jpg")
    )

    private fun upcoming(slug: String, startsAt: Long) = LiveChannelDTO(
        channelId = slug, slug = slug, kind = "special", name = "Viernes noche: 2000s",
        status = "upcoming", nextStartsAtUtcMs = startsAt
    )

    @Test
    fun `on air card shows badge, song and artist`() {
        val card = LiveCardMapper.map(listOf(onAir("rock")), at("2026-10-16T12:00"), madrid).single()

        assertEquals("EN DIRECTO", card.badge)
        assertEquals("Mr. Brightside · The Killers", card.subtitle)
        assertEquals("https://x/cover.jpg", card.imageUrl)
    }

    @Test
    fun `on air card without artist shows only the title`() {
        val card = LiveCardMapper.map(listOf(onAir("rock", artist = null)), 0L, madrid).single()

        assertEquals("Mr. Brightside", card.subtitle)
    }

    @Test
    fun `upcoming today shows only the local time`() {
        val card = LiveCardMapper.map(
            listOf(upcoming("viernes-2000s", at("2026-10-16T22:00"))), at("2026-10-16T12:00"), madrid
        ).single()

        assertNull(card.badge)
        assertEquals("Próximamente · 22:00", card.subtitle)
    }

    @Test
    fun `upcoming another day shows the weekday`() {
        val card = LiveCardMapper.map(
            listOf(upcoming("viernes-2000s", at("2026-10-16T22:00"))), at("2026-10-14T12:00"), madrid
        ).single()

        assertEquals("Próximamente · vie 22:00", card.subtitle)
    }

    @Test
    fun `off air and upcoming without start are hidden, order is kept`() {
        val channels = listOf(
            upcoming("b", at("2026-10-16T22:00")),
            LiveChannelDTO(slug = "off", status = "off_air"),
            upcoming("no-start", 0L).copy(nextStartsAtUtcMs = null),
            onAir("a")
        )

        val cards = LiveCardMapper.map(channels, at("2026-10-16T12:00"), madrid)

        assertEquals(listOf("b", "a"), cards.map(LiveCardUi::slug))
    }

    @Test
    fun `daylight saving change keeps the local wall time`() {
        // 25 Oct 2026: Madrid goes from UTC+2 to UTC+1.
        val start = at("2026-10-25T18:00")

        assertEquals("18:00", LiveCardMapper.startText(start, at("2026-10-25T09:00"), madrid))
        assertEquals("dom 18:00", LiveCardMapper.startText(start, at("2026-10-24T09:00"), madrid))
    }

    private fun offAirSpecial(slug: String, next: Long?, kind: String = "special") = LiveChannelDTO(
        channelId = slug, slug = slug, kind = kind, name = "Lo nuevo de la semana",
        status = "off_air", nextOccurrenceUtcMs = next
    )

    @Test
    fun `off air special within a week shows its next edition`() {
        val card = LiveCardMapper.map(
            listOf(offAirSpecial("lo-nuevo", at("2026-10-19T21:00"))), at("2026-10-16T12:00"), madrid
        ).single()

        assertNull(card.badge)
        assertNull(card.imageUrl)
        assertEquals("Especial · lun 21:00", card.subtitle)
    }

    @Test
    fun `off air special later today shows only the time`() {
        val card = LiveCardMapper.map(
            listOf(offAirSpecial("lo-nuevo", at("2026-10-19T21:00"))), at("2026-10-19T08:00"), madrid
        ).single()

        assertEquals("Especial · 21:00", card.subtitle)
    }

    @Test
    fun `special eight days away, monthly in twenty days or without next edition is hidden`() {
        val now = at("2026-10-16T12:00")
        val cards = LiveCardMapper.map(
            listOf(
                offAirSpecial("lejano", at("2026-10-24T12:01")),
                offAirSpecial("mensual", at("2026-11-05T21:00")),
                offAirSpecial("sin-edicion", null)
            ),
            now, madrid
        )

        assertEquals(emptyList<LiveCardUi>(), cards)
    }

    @Test
    fun `monthly special appears once it is seven days away`() {
        val cards = LiveCardMapper.map(
            listOf(offAirSpecial("noche-reggae", at("2026-11-07T21:00"))), at("2026-10-31T21:00"), madrid
        )

        assertEquals(listOf("noche-reggae"), cards.map(LiveCardUi::slug))
    }

    @Test
    fun `off air 24-7 channel stays hidden even with a next occurrence`() {
        val cards = LiveCardMapper.map(
            listOf(offAirSpecial("rock", at("2026-10-17T12:00"), kind = "channel")), at("2026-10-16T12:00"), madrid
        )

        assertEquals(emptyList<LiveCardUi>(), cards)
    }

    @Test
    fun `upcoming special keeps the coming soon text`() {
        val channel = upcoming("viernes-2000s", at("2026-10-16T22:00"))
            .copy(nextOccurrenceUtcMs = at("2026-10-16T22:00"))

        val card = LiveCardMapper.map(listOf(channel), at("2026-10-16T12:00"), madrid).single()

        assertEquals("Próximamente · 22:00", card.subtitle)
    }
}
