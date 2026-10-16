package com.example.resonant.live

import com.example.resonant.live.LiveFixtures.BLOCK_START
import com.example.resonant.live.LiveFixtures.now
import com.example.resonant.live.LiveFixtures.standardBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class LiveScreenStateTest {
    private fun build(timeline: LiveTimeline, nowMs: Long, listening: Boolean = false) =
        LiveScreenStateBuilder.build("Resonant Rock", timeline, listening, nowMs)

    @Test
    fun `on air shows the current song, what comes next and the programme`() {
        val state = build(LiveTimeline.from(now(standardBlock())), BLOCK_START + 250_000L)

        assertEquals("Song 1", state.title)
        assertEquals(50_000L, state.positionMs)
        assertEquals(180_000L, state.durationMs)
        assertEquals("Song 2", state.upNext)
        assertEquals("Resonant Rock · b1", state.blockTitle)
        assertEquals(
            listOf(LiveRowState.PLAYED, LiveRowState.CURRENT, LiveRowState.UPCOMING),
            state.rows.map(LiveScheduleRow::state)
        )
        assertEquals(LiveScreenButton.LISTEN, state.button)
        assertNull(state.message)
        assertNull(state.countdownMs)
    }

    @Test
    fun `listening offers leave`() {
        val state = build(LiveTimeline.from(now(standardBlock())), BLOCK_START, listening = true)

        assertEquals(LiveScreenButton.LEAVE, state.button)
    }

    @Test
    fun `upcoming special shows a countdown and its programme without listen`() {
        val state = build(LiveTimeline.from(now(null, standardBlock())), BLOCK_START - 90_000L)

        assertEquals(90_000L, state.countdownMs)
        assertEquals(LiveScreenButton.NONE, state.button)
        assertEquals(3, state.rows.size)
        assertEquals(LiveRowState.UPCOMING, state.rows.first().state)
    }

    @Test
    fun `off air says so`() {
        val state = build(LiveTimeline.from(now(null)), BLOCK_START)

        assertEquals(LiveErrors.MESSAGE_OFF_AIR, state.message)
        assertEquals(LiveScreenButton.NONE, state.button)
    }

    @Test
    fun `a missing song shows unavailable with the seconds left`() {
        val state = build(LiveTimeline.from(now(standardBlock(missing = setOf(1)))), BLOCK_START + 250_000L)

        assertNull(state.title)
        assertNull(state.rows[1].title)
        assertEquals("Esta canción no está disponible; la emisión sigue en 130 s", state.message)
        assertEquals(LiveScreenButton.LISTEN, state.button)
    }

    @Test
    fun `countdown format`() {
        assertEquals("01:30", LiveScreenStateBuilder.formatCountdown(90_000L))
        assertEquals("00:01", LiveScreenStateBuilder.formatCountdown(1L))
        assertEquals("1 h 05 min", LiveScreenStateBuilder.formatCountdown(3_900_000L))
    }

    private val madrid = ZoneId.of("Europe/Madrid")

    private fun at(text: String) = ZonedDateTime.of(LocalDateTime.parse(text), madrid).toInstant().toEpochMilli()

    @Test
    fun `off air special with a next edition says when it comes back`() {
        val edition = LiveNextEdition(at("2026-10-23T22:00"), 90)

        val state = LiveScreenStateBuilder.build(
            "Viernes noche", LiveTimeline.from(now(null)), false, at("2026-10-20T10:00"), edition, madrid
        )

        assertEquals(edition, state.scheduled)
        assertEquals("Vuelve el vie 23 oct a las 22:00 · 90 min", state.message)
        assertEquals(LiveScreenButton.NONE, state.button)
    }

    @Test
    fun `off air special without next edition keeps the off air message`() {
        val state = LiveScreenStateBuilder.build(
            "Viernes noche", LiveTimeline.from(now(null)), false, at("2026-10-20T10:00"), null, madrid
        )

        assertNull(state.scheduled)
        assertEquals(LiveErrors.MESSAGE_OFF_AIR, state.message)
    }

    @Test
    fun `scheduled text formats months and omits an unknown duration`() {
        assertEquals(
            "Vuelve el sáb 7 nov a las 21:00",
            LiveScreenStateBuilder.scheduledText(LiveNextEdition(at("2026-11-07T21:00"), null), madrid)
        )
        assertEquals(
            "Vuelve el jue 31 dic a las 23:00 · 60 min",
            LiveScreenStateBuilder.scheduledText(LiveNextEdition(at("2026-12-31T23:00"), 60), madrid)
        )
    }

    @Test
    fun `refresh only within twelve hours of the next edition`() {
        val edition = LiveNextEdition(at("2026-10-23T22:00"), 90)

        assertTrue(LiveScreenStateBuilder.shouldRefreshScheduled(edition, at("2026-10-23T10:00")))
        assertFalse(LiveScreenStateBuilder.shouldRefreshScheduled(edition, at("2026-10-23T09:59")))
    }
}
