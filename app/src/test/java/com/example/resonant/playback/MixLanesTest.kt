package com.example.resonant.playback

import com.example.resonant.data.network.MixDocumentDTO
import com.example.resonant.data.network.MixLanePointDTO
import com.example.resonant.data.network.MixSideADTO
import com.example.resonant.data.network.MixSideBDTO
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Mismos valores de referencia que MixLanesTests (backend) y test_mix_doc.py (renderer). */
class MixLanesTest {

    @Test
    fun `curves match the shared contract`() {
        val cases = listOf(
            Triple("linear", 0.5, 0.5), Triple("hold", 0.5, 0.0), Triple("hold", 1.0, 0.0),
            Triple("smooth", 0.5, 0.5), Triple("smooth", 1.0, 1.0), Triple("fastStart", 0.5, 0.75),
            Triple("slowStart", 0.5, 0.25), Triple("equalPower", 0.0, 0.0), Triple("equalPower", 1.0, 1.0)
        )
        for ((curve, p, expected) in cases) assertEquals("$curve@$p", expected, MixLanes.shape(curve, p, 0.0, 1.0), 1e-6)
        assertEquals(sin(PI / 4), MixLanes.shape("equalPower", 0.5, 0.0, 1.0), 1e-6)
        assertEquals(cos(PI / 4), 1 + (0 - 1) * MixLanes.shape("equalPower", 0.5, 1.0, 0.0), 1e-6)
    }

    @Test
    fun `lane values outside points, holds and steps`() {
        val points = listOf(MixLanePointDTO(1000, 0.2, "hold"), MixLanePointDTO(3000, 0.8), MixLanePointDTO(3000, 0.4))
        assertEquals(0.2, MixLanes.evaluate(points, 0.0, 1.0), 1e-9)
        assertEquals(0.2, MixLanes.evaluate(points, 2999.0, 1.0), 1e-9)
        assertEquals(0.4, MixLanes.evaluate(points, 3000.0, 1.0), 1e-9)
        assertEquals(0.4, MixLanes.evaluate(points, 9000.0, 1.0), 1e-9)
        assertEquals(1.0, MixLanes.evaluate(null, 500.0, 1.0), 1e-9)
    }

    @Test
    fun `gain and eq defaults and linear middle`() {
        val doc = MixDocumentDTO(
            version = 1, durationMs = 8000, a = MixSideADTO(180_000), b = MixSideBDTO(400, 0),
            lanes = mapOf("a.gain" to listOf(MixLanePointDTO(0, 1.0, "linear"), MixLanePointDTO(8000, 0.0)))
        )
        assertEquals(0.5, MixLanes.gain(doc, 'a', 4000.0), 1e-9)
        assertEquals(1.0, MixLanes.gain(doc, 'b', 4000.0), 1e-9)
        assertEquals(0.0, MixLanes.eqDb(doc, 'a', "low", 4000.0), 1e-9)
    }

    @Test
    fun `device bands are grouped like the document bands`() {
        assertEquals("low", MixLanes.bandForFrequency(60))
        assertEquals("low", MixLanes.bandForFrequency(230))
        assertEquals("mid", MixLanes.bandForFrequency(910))
        assertEquals("high", MixLanes.bandForFrequency(3600))
        assertEquals("high", MixLanes.bandForFrequency(14000))
    }

    @Test
    fun `suggestion payload deserializes`() {
        val json = """{ "suggestions": [ { "code": "beat-match", "name": "Beat Match", "score": 95,
            "met": ["bpmDeltaPct"], "unmet": [],
            "document": { "version": 1, "durationMs": 30000, "a": { "startMs": 182980 },
              "b": { "startMs": 30412, "offsetMs": 0 },
              "lanes": { "a.eq.low": [ { "t": 0, "v": 0, "curve": "hold" }, { "t": 15000, "v": -40, "curve": "linear" } ] },
              "preset": { "code": "beat-match", "version": 1 } } } ] }"""
        val result = Gson().fromJson(json, com.example.resonant.data.network.TransitionSuggestionsDTO::class.java)
        val doc = result.suggestions.single().document
        assertEquals(182_980, doc.a.startMs)
        assertEquals(-40.0, MixLanes.eqDb(doc, 'a', "low", 15_000.0), 1e-9)
    }
}
