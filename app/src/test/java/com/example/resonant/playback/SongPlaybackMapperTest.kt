package com.example.resonant.playback

import com.example.resonant.data.models.Song
import com.example.resonant.data.network.PlaybackResolveItemDTO
import com.example.resonant.data.network.SongPlaybackDTO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongPlaybackMapperTest {

    @Test
    fun `playback response enriches a catalog song without replacing metadata`() {
        val song = Song(
            id = "song-1",
            title = "Title",
            artistName = "Artist"
        )
        val playback = SongPlaybackDTO(
            id = "song-1",
            streamUrl = "https://cdn.example/song.mp3",
            durationMs = 200_000,
            bpm = 120.0,
            musicalKey = "Am",
            introStartMs = 1_500,
            outroStartMs = 190_000,
            loudness = -12.5
        )

        val enriched = song.withPlaybackInfo(playback)

        assertEquals("Title", enriched.title)
        assertEquals("Artist", enriched.artistName)
        assertEquals(playback.streamUrl, enriched.url)
        assertEquals(200_000, enriched.audioAnalysis?.durationMs)
        assertEquals(1_500, enriched.audioAnalysis?.audioStartMs)
        assertEquals(190_000, enriched.audioAnalysis?.audioEndMs)
        assertEquals(120.0, enriched.audioAnalysis?.bpm ?: 0.0, 0.0)
        assertEquals(-12.5f, enriched.audioAnalysis?.loudnessLufs ?: 0f, 0f)
    }

    @Test
    fun `batch playback hints do not replace the stream url`() {
        val song = Song(
            id = "song-1",
            title = "Title",
            artistName = "Artist",
            url = "https://catalog.example/original.mp3"
        )
        val resolution = PlaybackResolveItemDTO(
            id = "song-1",
            streamUrl = "https://batch.example/transient.m3u8",
            mimeType = "application/vnd.apple.mpegurl",
            deliveryMode = "hls",
            quality = "high"
        )

        val enriched = song.withPlaybackQueueHints(resolution)

        assertEquals("https://catalog.example/original.mp3", enriched.url)
        assertEquals("application/vnd.apple.mpegurl", enriched.playbackMimeType)
        assertEquals("hls", enriched.playbackDeliveryMode)
        assertEquals("high", enriched.playbackQuality)
    }

    @Test
    fun `hls is only preferred when the song exposes an explicit manifest hint`() {
        val ambiguous = Song(
            id = "song-1",
            playbackDeliveryMode = "hls",
            url = "https://cdn.example/audio"
        )
        val verified = Song(
            id = "song-2",
            playbackDeliveryMode = "hls",
            playbackMimeType = "application/vnd.apple.mpegurl"
        )

        assertFalse(ambiguous.usesVerifiedHls())
        assertEquals(
            PlaybackUrlResolver.DELIVERY_MODE_PROGRESSIVE,
            ambiguous.preferredStreamingDeliveryMode()
        )
        assertTrue(verified.usesVerifiedHls())
        assertEquals(
            PlaybackUrlResolver.DELIVERY_MODE_HLS,
            verified.preferredStreamingDeliveryMode()
        )
    }
}
