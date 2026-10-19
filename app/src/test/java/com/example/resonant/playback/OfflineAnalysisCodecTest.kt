package com.example.resonant.playback

import com.example.resonant.data.models.AudioAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Análisis completo guardado con una descarga: ida y vuelta del JSON (collection-queue-offline 4.2). */
class OfflineAnalysisCodecTest {

    @Test
    fun `full analysis survives the round trip`() {
        val analysis = AudioAnalysis(
            id = "a1", songId = "s1", durationMs = 214_000, audioStartMs = 120, audioEndMs = 213_500,
            loudnessLufs = -8.5f, bpm = 124.0, bpmNormalized = 124.0, optimalStartPointMs = 900,
            optimalExitPointMs = 198_000, musicalKey = "8A",
            bpmPrecise = 123.98, firstDownbeatMs = 412, beatsPerBar = 4, mixInMs = 15_000, mixOutMs = 190_000,
            dropMs = 61_000
        )
        assertEquals(analysis, OfflineAnalysisCodec.decode(OfflineAnalysisCodec.encode(analysis)))
    }

    @Test
    fun `missing or broken json gives no analysis`() {
        assertNull(OfflineAnalysisCodec.decode(null))
        assertNull(OfflineAnalysisCodec.decode(""))
        assertNull(OfflineAnalysisCodec.decode("{no es json"))
        assertNull(OfflineAnalysisCodec.encode(null))
    }

    @Test
    fun `ordered song ids round trip`() {
        val ids = listOf("c", "a", "b")
        assertEquals(ids, OfflineAnalysisCodec.decodeIds(OfflineAnalysisCodec.encodeIds(ids)))
        assertEquals(emptyList<String>(), OfflineAnalysisCodec.decodeIds("garbage"))
    }
}
