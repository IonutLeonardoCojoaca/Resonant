package com.example.resonant.managers

import com.example.resonant.data.models.Song
import com.example.resonant.data.network.SongPlaybackDTO
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class SongManagerTest {
    private var nowMs = 1_000_000L
    private val service = FakeSongService()

    /** Cada llamada simula una pantalla distinta creando su propio SongManager. */
    private fun manager() = SongManager(
        songService = service,
        artistServiceProvider = { error("unused") },
        playlistServiceProvider = { error("unused") },
        albumServiceProvider = { error("unused") },
        now = { nowMs },
        log = { _, _ -> }
    )

    @Before
    fun setUp() {
        SongManager.clearAll()
        service.songs["s1"] = Song(id = "s1", title = "One")
    }

    @After
    fun tearDown() = SongManager.clearAll()

    @Test
    fun `a song fetched by one instance is served from cache to another`() = runTest {
        val first = manager().getSongById("s1")
        val second = manager().getSongById("s1")

        assertEquals("One", first?.title)
        assertSame(first, second)
        assertEquals(1, service.songByIdCalls)
    }

    @Test
    fun `cache expires after twenty minutes`() = runTest {
        manager().getSongById("s1")
        nowMs += 20 * 60 * 1000L + 1

        manager().getSongById("s1")

        assertEquals(2, service.songByIdCalls)
    }

    @Test
    fun `clearAll forgets cached songs`() = runTest {
        manager().getSongById("s1")
        SongManager.clearAll()

        manager().getSongById("s1")

        assertEquals(2, service.songByIdCalls)
    }

    @Test
    fun `playback info is merged into the cached song`() = runTest {
        service.playback["s1"] = SongPlaybackDTO(
            id = "s1", streamUrl = "https://cdn/s1", durationMs = 180_000, bpm = 120.0,
            musicalKey = "Am", introStartMs = null, outroStartMs = null, loudness = -9.0
        )

        val song = manager().getSongById("s1")

        assertEquals("https://cdn/s1", song?.url)
        assertEquals(120.0, song?.audioAnalysis?.bpm)
        assertEquals(180_000, song?.audioAnalysis?.durationMs)
        assertEquals(1, service.playbackCalls)
    }

    @Test
    fun `a failed refresh returns the stale cached song`() = runTest {
        manager().getSongById("s1")
        nowMs += 21 * 60 * 1000L
        service.songs.remove("s1")

        val song = manager().getSongById("s1")

        assertEquals("One", song?.title)
        assertEquals(2, service.songByIdCalls)
    }
}
