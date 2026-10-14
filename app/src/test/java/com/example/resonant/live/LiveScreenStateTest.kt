package com.example.resonant.live

import com.example.resonant.live.LiveFixtures.BLOCK_START
import com.example.resonant.live.LiveFixtures.now
import com.example.resonant.live.LiveFixtures.standardBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
