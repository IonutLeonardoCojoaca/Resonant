package com.example.resonant.playback

import com.example.resonant.data.models.AudioAnalysis
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.SongPlaybackDTO
import com.example.resonant.data.network.PlaybackResolveItemDTO

fun Song.withPlaybackInfo(playback: SongPlaybackDTO): Song {
    val effectiveBpm = playback.bpm ?: audioAnalysis?.bpm ?: 0.0
    val analysis = audioAnalysis?.copy(
        durationMs = playback.durationMs.takeIf { it > 0 } ?: audioAnalysis?.durationMs ?: 0,
        audioStartMs = playback.introStartMs ?: audioAnalysis?.audioStartMs ?: 0,
        audioEndMs = playback.outroStartMs
            ?: playback.durationMs.takeIf { it > 0 }
            ?: audioAnalysis?.audioEndMs
            ?: 0,
        bpm = effectiveBpm,
        bpmNormalized = effectiveBpm.takeIf { it > 0.0 }
            ?: audioAnalysis?.bpmNormalized
            ?: 0.0,
        musicalKey = playback.musicalKey ?: audioAnalysis?.musicalKey,
        loudnessLufs = playback.loudness?.toFloat()
            ?: audioAnalysis?.loudnessLufs
            ?: 0f
    ) ?: AudioAnalysis(
        id = id,
        songId = id,
        durationMs = playback.durationMs,
        audioStartMs = playback.introStartMs ?: 0,
        audioEndMs = playback.outroStartMs ?: playback.durationMs,
        loudnessLufs = playback.loudness?.toFloat() ?: 0f,
        bpm = effectiveBpm,
        bpmNormalized = effectiveBpm,
        musicalKey = playback.musicalKey
    )

    return copy(
        url = playback.streamUrl ?: url,
        playbackMimeType = playback.mimeType ?: playbackMimeType,
        playbackExpiresAtUtc = playback.expiresAtUtc ?: playbackExpiresAtUtc,
        playbackStreamId = playback.streamId ?: playbackStreamId,
        playbackDeliveryMode = playback.deliveryMode ?: playbackDeliveryMode,
        playbackQuality = playback.quality ?: playbackQuality,
        playbackContentLength = playback.contentLength ?: playbackContentLength,
        playbackSupportsRanges = playback.supportsRanges ?: playbackSupportsRanges,
        audioAnalysis = analysis
    )
}

fun Song.withPlaybackResolution(resolution: PlaybackResolveItemDTO): Song {
    val resolvedUrl = resolution.streamUrl
        ?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
    return copy(
        url = resolvedUrl ?: url,
        playbackMimeType = resolution.mimeType ?: playbackMimeType,
        playbackExpiresAtUtc = resolution.expiresAtUtc ?: playbackExpiresAtUtc,
        playbackStreamId = resolution.streamId ?: playbackStreamId,
        playbackDeliveryMode = resolution.deliveryMode ?: playbackDeliveryMode,
        playbackQuality = resolution.quality ?: playbackQuality,
        playbackContentLength = resolution.contentLength ?: playbackContentLength,
        playbackSupportsRanges = resolution.supportsRanges ?: playbackSupportsRanges
    )
}

/**
 * Batch resolve is used to learn delivery hints for upcoming items while the
 * actual stream URL is still resolved per song through the stable URI path.
 */
fun Song.withPlaybackQueueHints(resolution: PlaybackResolveItemDTO): Song {
    return copy(
        playbackMimeType = resolution.mimeType ?: playbackMimeType,
        playbackExpiresAtUtc = resolution.expiresAtUtc ?: playbackExpiresAtUtc,
        playbackStreamId = resolution.streamId ?: playbackStreamId,
        playbackDeliveryMode = resolution.deliveryMode ?: playbackDeliveryMode,
        playbackQuality = resolution.quality ?: playbackQuality,
        playbackContentLength = resolution.contentLength ?: playbackContentLength,
        playbackSupportsRanges = resolution.supportsRanges ?: playbackSupportsRanges
    )
}
