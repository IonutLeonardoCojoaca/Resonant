package com.example.resonant.playback

import com.example.resonant.data.network.PlaymixAudioAnalysisDTO
import com.example.resonant.data.network.PlaymixSongDTO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaymixSongMapperTest {

    private fun dto(id: String, position: Int, analysis: PlaymixAudioAnalysisDTO? = null) = PlaymixSongDTO(
        playmixSongId = "ps-$id", position = position, songId = id, title = "Title $id", artist = "Artist $id",
        duration = 200, coverUrl = "https://cdn/cover-$id.jpg", imageUrl = null,
        customEntryMs = null, customExitMs = null, audioAnalysis = analysis
    )

    @Test
    fun `maps what the queue needs from the detail`() {
        val analysis = PlaymixAudioAnalysisDTO(
            bpm = 124.0, bpmNormalized = null, musicalKey = "Am", loudnessLufs = -9.5,
            optimalStartPointMs = 1000, optimalExitPointMs = 180_000, durationMs = 200_000
        )

        val song = PlaymixSongMapper.toSong(dto("s1", 3, analysis))

        assertEquals("s1", song.id)
        assertEquals("Title s1", song.title)
        assertEquals("Artist s1", song.artistName)
        assertEquals("200", song.duration)
        assertEquals(3, song.position)
        assertEquals("https://cdn/cover-s1.jpg", song.coverUrl)
        assertNull(song.url)
        val mapped = song.audioAnalysis!!
        assertEquals(124.0, mapped.bpm, 0.0)
        assertEquals(124.0, mapped.bpmNormalized, 0.0)
        assertEquals("Am", mapped.musicalKey)
        assertEquals(-9.5f, mapped.loudnessLufs)
        assertEquals(200_000, mapped.durationMs)
        assertEquals(1000, mapped.optimalStartPointMs)
    }

    @Test
    fun `a song without analysis still knows its duration`() {
        val song = PlaymixSongMapper.toSong(dto("s2", 0))

        assertEquals(200_000, song.audioAnalysis!!.durationMs)
        assertEquals(0.0, song.audioAnalysis!!.bpm, 0.0)
    }

    @Test
    fun `songs come out in set order`() {
        val songs = PlaymixSongMapper.toSongs(listOf(dto("c", 2), dto("a", 0), dto("b", 1)))

        assertEquals(listOf("a", "b", "c"), songs.map { it.id })
    }
}
