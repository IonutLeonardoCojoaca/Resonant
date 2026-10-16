package com.example.resonant.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class ListenClockTest {

    @Test
    fun `counts wall time while playing`() {
        val clock = ListenClock()
        clock.restart(nowMs = 1_000L, playing = true)

        assertEquals(60, clock.listenedSeconds(1_000L, 61_000L, songDurationSeconds = 200))
    }

    @Test
    fun `discounts closed and open pauses`() {
        val clock = ListenClock()
        clock.restart(nowMs = 0L, playing = true)
        clock.onPlayingChanged(playing = false, nowMs = 30_000L)
        clock.onPlayingChanged(playing = true, nowMs = 630_000L)
        clock.onPlayingChanged(playing = false, nowMs = 640_000L)

        // 30 s + 10 s escuchados; el resto, en pausa (la última sigue abierta).
        assertEquals(40, clock.listenedSeconds(0L, 900_000L, songDurationSeconds = 200))
    }

    @Test
    fun `starting paused does not count until playback begins`() {
        val clock = ListenClock()
        clock.restart(nowMs = 0L, playing = false)
        clock.onPlayingChanged(playing = true, nowMs = 5_000L)

        assertEquals(20, clock.listenedSeconds(0L, 25_000L, songDurationSeconds = 200))
    }

    @Test
    fun `never exceeds the song duration`() {
        val clock = ListenClock()
        clock.restart(nowMs = 0L, playing = true)

        assertEquals(180, clock.listenedSeconds(0L, 500_000L, songDurationSeconds = 180))
    }

    @Test
    fun `unknown duration is not capped`() {
        val clock = ListenClock()
        clock.restart(nowMs = 0L, playing = true)

        assertEquals(500, clock.listenedSeconds(0L, 500_000L, songDurationSeconds = 0))
    }

    @Test
    fun `restart clears previous pauses`() {
        val clock = ListenClock()
        clock.restart(nowMs = 0L, playing = true)
        clock.onPlayingChanged(playing = false, nowMs = 10_000L)
        clock.restart(nowMs = 100_000L, playing = true)

        assertEquals(30, clock.listenedSeconds(100_000L, 130_000L, songDurationSeconds = 200))
    }

    @Test
    fun `repeated playing or paused callbacks do not double count`() {
        val clock = ListenClock()
        clock.restart(nowMs = 0L, playing = true)
        clock.onPlayingChanged(playing = true, nowMs = 5_000L)
        clock.onPlayingChanged(playing = false, nowMs = 60_000L)
        clock.onPlayingChanged(playing = false, nowMs = 90_000L)
        clock.onPlayingChanged(playing = true, nowMs = 660_000L)
        clock.onPlayingChanged(playing = true, nowMs = 670_000L)

        // 60 s antes de la pausa de 10 min y 40 s después.
        assertEquals(100, clock.listenedSeconds(0L, 700_000L, songDurationSeconds = 300))
    }
}
