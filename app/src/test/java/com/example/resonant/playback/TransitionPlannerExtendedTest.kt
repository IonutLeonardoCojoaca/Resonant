package com.example.resonant.playback

import com.example.resonant.data.network.MixDocumentDTO
import com.example.resonant.data.network.MixSideADTO
import com.example.resonant.data.network.MixSideBDTO
import com.example.resonant.playback.TransitionPlanner.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rango ampliado de Playmix en el planificador (playmix-extended-beatmatch D6). */
class TransitionPlannerExtendedTest {

    private fun track(bpm: Double?, stability: Double? = 0.97) = Track(
        200_000, 0, 198_000, null, null,
        bpm?.let { PhaseAlignment.Grid(it, 500, 4) }, stability
    )

    /** B con el tempo que da bpmA/bpmB = [rate] frente a A a 120 BPM. */
    private fun pair(rate: Double, stability: Double? = 0.97) = track(120.0) to track(120.0 / rate, stability)

    private fun doc(extendedSync: Boolean? = true, beatSync: Boolean? = true) = MixDocumentDTO(
        version = 2, durationMs = 16_000, a = MixSideADTO(180_000), b = MixSideBDTO(2_000),
        lanes = emptyMap(), bars = 8, beatSync = beatSync, extendedSync = extendedSync
    )

    @Test
    fun `extended range boundaries`() {
        for ((rate, expected) in listOf(1.06 to false, 1.0601 to true, 1.12 to true, 1.1201 to false,
            0.94 to false, 0.9399 to true, 0.88 to true, 0.8799 to false)) {
            val (a, b) = pair(rate)
            val ratio = TransitionPlanner.extendedBeatmatchRatio(a, b)
            assertEquals("rate $rate", expected, ratio != null)
            if (ratio != null) assertEquals(rate, ratio, 1e-9)
        }
    }

    @Test
    fun `standard ratio is not extended`() {
        val (a, b) = pair(1.016)
        assertNull(TransitionPlanner.extendedBeatmatchRatio(a, b))
        assertEquals(1.016, TransitionPlanner.beatmatchRatio(a, b)!!, 1e-9)
    }

    @Test
    fun `stability is mandatory`() {
        assertNull(TransitionPlanner.extendedBeatmatchRatio(track(120.0), track(120.0 / 1.08, stability = 0.7)))
        assertNull(TransitionPlanner.extendedBeatmatchRatio(track(120.0), track(120.0 / 1.08, stability = null)))
        assertEquals(1.08, TransitionPlanner.extendedBeatmatchRatio(track(120.0), track(120.0 / 1.08, stability = 0.8))!!, 1e-9)
    }

    @Test
    fun `unknown tempo is not extended`() {
        assertNull(TransitionPlanner.extendedBeatmatchRatio(track(120.0), track(null)))
    }

    @Test
    fun `mi gente to gasolina is extended and danza kuduro is not`() {
        assertEquals(105.0 / 96.77, TransitionPlanner.extendedBeatmatchRatio(track(105.0), track(96.77))!!, 1e-9)
        assertNull(TransitionPlanner.extendedBeatmatchRatio(track(130.0), track(105.0)))
        assertNull(TransitionPlanner.beatmatchRatio(track(130.0), track(105.0)))
    }

    @Test
    fun `playmix plan uses the extended range only with capability and mark`() {
        val a = track(105.0)
        val b = track(96.77)

        val extended = TransitionPlanner.playmixPlan(doc(), "studio", a, b, extendedEnabled = true)
        assertTrue(extended.beatSync)
        assertTrue(extended.extendedSync)
        assertEquals(105.0 / 96.77, extended.bpmRatio, 1e-9)

        for (plan in listOf(
            TransitionPlanner.playmixPlan(doc(), "studio", a, b, extendedEnabled = false),
            TransitionPlanner.playmixPlan(doc(extendedSync = null), "studio", a, b, extendedEnabled = true),
            TransitionPlanner.playmixPlan(doc(beatSync = false), "studio", a, b, extendedEnabled = true)
        )) {
            assertFalse(plan.beatSync)
            assertFalse(plan.extendedSync)
            assertEquals(1.0, plan.bpmRatio, 0.0)
        }
    }

    @Test
    fun `playmix plan of a standard pair is the current one`() {
        val (a, b) = pair(1.016)
        for (enabled in listOf(true, false)) for (mark in listOf(true, null)) {
            val plan = TransitionPlanner.playmixPlan(doc(extendedSync = mark), "studio", a, b, extendedEnabled = enabled)
            val ratio = TransitionPlanner.beatmatchRatio(a, b)
            assertEquals(TransitionPlanner.Plan(doc(extendedSync = mark), "studio", beatSync = true, bpmRatio = ratio!!), plan)
            assertFalse(plan.extendedSync)
        }
    }

    @Test
    fun `plan defaults are not extended`() {
        assertFalse(TransitionPlanner.Plan(doc(), "x").extendedSync)
        assertNull(track(120.0).copy(stability = null).stability)
    }
}
