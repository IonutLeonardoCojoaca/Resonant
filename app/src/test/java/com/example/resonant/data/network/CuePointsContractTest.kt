package com.example.resonant.data.network

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CuePointsContractTest {
    private val gson = Gson()

    @Test
    fun `playback with v2 and cue points`() {
        val json = """
            { "id": "3f1c", "durationMs": 214560, "bpm": 128.47, "musicalKey": "C#m",
              "introStartMs": 30412, "outroStartMs": 182980, "loudness": -8.31,
              "analysisVersion": 2, "integratedLufs": -8.93, "keyCamelot": "12A",
              "bpmPrecise": 128.47, "firstDownbeatMs": 412, "beatsPerBar": 4,
              "mixInMs": 30412, "mixOutMs": 182980, "dropMs": 60292 }
        """.trimIndent()

        val dto = gson.fromJson(json, SongPlaybackDTO::class.java)

        assertEquals(2, dto.analysisVersion)
        assertEquals(412, dto.firstDownbeatMs)
        assertEquals(128.47, dto.bpmPrecise!!, 1e-9)
        assertEquals(60_292, dto.dropMs)
    }

    @Test
    fun `old playback payload keeps working`() {
        val json = """{ "id": "3f1c", "durationMs": 1000, "bpm": 120.0, "musicalKey": null,
            "introStartMs": null, "outroStartMs": null, "loudness": null }"""

        val dto = gson.fromJson(json, SongPlaybackDTO::class.java)

        assertNull(dto.firstDownbeatMs)
        assertNull(dto.mixInMs)
    }

    @Test
    fun `waveform song with phrases`() {
        val json = """{ "songId": "a", "title": "t", "artist": "x", "durationMs": 1000, "bpm": 120.0,
            "musicalKey": null, "loudnessLufs": null, "waveformUrl": null, "beatGrid": [412, 879],
            "downbeatGrid": [412], "phrases": [{"startMs": 412, "bars": 8}], "beatsPerBar": 4, "mixInMs": 412 }"""

        val song = gson.fromJson(json, WaveformSongDTO::class.java)

        assertEquals(listOf(412), song.downbeatGrid)
        assertEquals(8, song.phrases!!.single().bars)
        assertEquals(412, song.mixInMs)
    }
}
