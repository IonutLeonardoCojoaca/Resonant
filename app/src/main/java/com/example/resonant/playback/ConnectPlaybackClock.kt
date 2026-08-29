package com.example.resonant.playback

import com.example.resonant.data.network.PlaybackConnectStateDTO
import java.time.Instant

internal fun PlaybackConnectStateDTO.anchoredPositionMs(
    nowEpochMs: Long = System.currentTimeMillis()
): Long {
    var position = positionMs.coerceAtLeast(0L)
    if (isPlaying) {
        val anchorEpochMs = runCatching {
            positionUpdatedAtUtc?.let(Instant::parse)?.toEpochMilli()
        }.getOrNull()
        val elapsed = anchorEpochMs?.let(nowEpochMs::minus)
        // Ignore obviously skewed device clocks. Fresh SignalR delivery is
        // normally well below one second.
        if (elapsed != null && elapsed in 0L..30_000L) position += elapsed
    }
    return if (durationMs > 0L) position.coerceAtMost(durationMs) else position
}
