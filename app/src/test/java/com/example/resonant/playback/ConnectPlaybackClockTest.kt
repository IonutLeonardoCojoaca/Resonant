package com.example.resonant.playback

import com.example.resonant.data.network.PlaybackConnectStateDTO
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectPlaybackClockTest {
    @Test
    fun playingStateAdvancesFromServerAnchor() {
        val state = playback(
            positionMs = 10_000L,
            durationMs = 60_000L,
            isPlaying = true,
            anchor = "2026-08-29T10:00:00Z"
        )

        assertEquals(
            10_750L,
            state.anchoredPositionMs(1_787_997_600_750L)
        )
    }

    @Test
    fun pausedStateDoesNotAdvanceAndPositionIsClamped() {
        val state = playback(
            positionMs = 70_000L,
            durationMs = 60_000L,
            isPlaying = false,
            anchor = "2026-08-29T10:00:00Z"
        )

        assertEquals(60_000L, state.anchoredPositionMs(1_787_997_610_000L))
    }

    private fun playback(
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
        anchor: String
    ) = PlaybackConnectStateDTO(
        stateRevision = 1L,
        queueRevision = 1L,
        currentIndex = 0,
        positionMs = positionMs,
        durationMs = durationMs,
        isPlaying = isPlaying,
        positionUpdatedAtUtc = anchor,
        sourceType = "QUEUE",
        sourceId = null,
        repeatMode = 0,
        shuffleEnabled = false
    )
}
