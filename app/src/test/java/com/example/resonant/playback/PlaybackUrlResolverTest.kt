package com.example.resonant.playback

import com.example.resonant.data.network.SongPlaybackDTO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

class PlaybackUrlResolverTest {

    private val clock = Clock.fixed(
        Instant.parse("2026-07-26T12:00:00Z"),
        ZoneOffset.UTC
    )

    @Test
    fun `seeded URL avoids a playback request`() {
        val requests = AtomicInteger()
        val resolver = PlaybackUrlResolver(
            loadPlaybackInfo = {
                requests.incrementAndGet()
                playbackInfo("https://cdn.example/new.mp3")
            },
            clock = clock
        )

        resolver.seed("song-1", "https://cdn.example/seeded.mp3")

        assertEquals("https://cdn.example/seeded.mp3", resolver.resolveBlocking("song-1"))
        assertEquals(0, requests.get())
    }

    @Test
    fun `missing URL is resolved once and coalesced in cache`() {
        val requests = AtomicInteger()
        val resolver = PlaybackUrlResolver(
            loadPlaybackInfo = {
                requests.incrementAndGet()
                playbackInfo("https://cdn.example/resolved.mp3")
            },
            clock = clock
        )

        assertEquals("https://cdn.example/resolved.mp3", resolver.resolveBlocking("song-2"))
        assertEquals("https://cdn.example/resolved.mp3", resolver.resolveBlocking("song-2"))
        assertEquals(1, requests.get())
    }

    @Test
    fun `invalidate forces a fresh playback request`() {
        val requests = AtomicInteger()
        val resolver = PlaybackUrlResolver(
            loadPlaybackInfo = {
                val count = requests.incrementAndGet()
                playbackInfo("https://cdn.example/$count.mp3")
            },
            clock = clock
        )

        assertEquals("https://cdn.example/1.mp3", resolver.resolveBlocking("song-3"))
        resolver.invalidate("song-3")
        assertEquals("https://cdn.example/2.mp3", resolver.resolveBlocking("song-3"))
        assertEquals(2, requests.get())
    }

    @Test
    fun `amazon expiry is parsed from the signature`() {
        val expiry = PlaybackUrlResolver.parsePresignedExpiryEpochMs(
            "https://cdn.example/song.mp3" +
                "?X-Amz-Date=20260726T120000Z&X-Amz-Expires=900"
        )

        assertEquals(Instant.parse("2026-07-26T12:15:00Z").toEpochMilli(), expiry)
    }

    @Test
    fun `backend UTC expiry is parsed for HMAC delivery URLs`() {
        val expiry = PlaybackUrlResolver.parseBackendExpiryEpochMs(
            "2026-07-26T12:15:00+00:00"
        )

        assertEquals(Instant.parse("2026-07-26T12:15:00Z").toEpochMilli(), expiry)
    }

    @Test
    fun `expired backend URL is not seeded into cache`() {
        val requests = AtomicInteger()
        val resolver = PlaybackUrlResolver(
            loadPlaybackInfo = {
                requests.incrementAndGet()
                playbackInfo("https://delivery.example/fresh")
            },
            clock = clock
        )

        resolver.seed(
            songId = "song-4",
            url = "https://delivery.example/expired",
            expiresAtUtc = "2026-07-26T11:59:00Z"
        )

        assertEquals(
            "https://delivery.example/fresh",
            resolver.resolveBlocking("song-4")
        )
        assertEquals(1, requests.get())
    }

    @Test
    fun `stable URI contains only the song identity`() {
        val resolver = PlaybackUrlResolver(
            loadPlaybackInfo = { playbackInfo("https://cdn.example/song.mp3") },
            clock = clock
        )

        val uri = URI(resolver.stableUri("song id/with symbols"))
        val decodedId = URLDecoder.decode(
            uri.rawPath.removePrefix("/"),
            StandardCharsets.UTF_8.name()
        )

        assertEquals("resonant", uri.scheme)
        assertEquals("song", uri.authority)
        assertEquals("song id/with symbols", decodedId)
        assertTrue(uri.query.isNullOrBlank())
    }

    private fun playbackInfo(url: String) = SongPlaybackDTO(
        id = "song",
        streamUrl = url,
        durationMs = 180_000,
        bpm = null,
        musicalKey = null,
        introStartMs = null,
        outroStartMs = null,
        loudness = null
    )
}
