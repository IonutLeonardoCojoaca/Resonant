package com.example.resonant.playback

import com.example.resonant.playback.TransitionTiming.EntryMode
import com.example.resonant.playback.TransitionTiming.FadeDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionTimingTest {

    // ── Duración efectiva y disparo ─────────────────────────────────────────

    @Test
    fun `real duration shorter than analysis wins`() {
        assertEquals(180_000L, TransitionTiming.effectiveDurationMs(exoDurationMs = 180_000, analysisDurationMs = 200_000))
        assertEquals(
            172_000L,
            TransitionTiming.computeTriggerPositionMs(
                exoDurationMs = 180_000, analysisDurationMs = 200_000, audioEndMs = 0, mixDurationMs = 8_000
            )
        )
    }

    @Test
    fun `analysis duration shorter than real wins`() {
        assertEquals(
            192_000L,
            TransitionTiming.computeTriggerPositionMs(
                exoDurationMs = 210_000, analysisDurationMs = 200_000, audioEndMs = 0, mixDurationMs = 8_000
            )
        )
    }

    @Test
    fun `unknown durations are ignored and no duration means no trigger`() {
        assertEquals(200_000L, TransitionTiming.effectiveDurationMs(exoDurationMs = -1, analysisDurationMs = 200_000))
        assertEquals(-1L, TransitionTiming.effectiveDurationMs(exoDurationMs = 0, analysisDurationMs = 0))
        assertEquals(
            -1L,
            TransitionTiming.computeTriggerPositionMs(exoDurationMs = 0, analysisDurationMs = 0, audioEndMs = 0, mixDurationMs = 8_000)
        )
    }

    @Test
    fun `valid audio end is respected`() {
        assertEquals(
            187_000L,
            TransitionTiming.computeTriggerPositionMs(
                exoDurationMs = 200_000, analysisDurationMs = 200_000, audioEndMs = 195_000, mixDurationMs = 8_000
            )
        )
    }

    @Test
    fun `invalid audio end falls back to duration`() {
        // Mayor que la duración.
        assertEquals(
            192_000L,
            TransitionTiming.computeTriggerPositionMs(
                exoDurationMs = 200_000, analysisDurationMs = 200_000, audioEndMs = 250_000, mixDurationMs = 8_000
            )
        )
        // Cero (sin análisis).
        assertEquals(
            192_000L,
            TransitionTiming.computeTriggerPositionMs(
                exoDurationMs = 200_000, analysisDurationMs = 0, audioEndMs = 0, mixDurationMs = 8_000
            )
        )
    }

    @Test
    fun `mix longer than song or zero mix gives no trigger`() {
        assertEquals(
            -1L,
            TransitionTiming.computeTriggerPositionMs(exoDurationMs = 5_000, analysisDurationMs = 0, audioEndMs = 0, mixDurationMs = 8_000)
        )
        assertEquals(
            -1L,
            TransitionTiming.computeTriggerPositionMs(exoDurationMs = 200_000, analysisDurationMs = 0, audioEndMs = 0, mixDurationMs = 0)
        )
    }

    // ── Decisión de inicio del fundido ──────────────────────────────────────

    @Test
    fun `fade is shortened to remaining audio`() {
        val decision = TransitionTiming.decideFadeStart(remainingAMs = 3_000, desiredFadeMs = 8_000)
        assertEquals(FadeDecision.Start(3_000), decision)
    }

    @Test
    fun `fade keeps desired duration when enough audio remains`() {
        val decision = TransitionTiming.decideFadeStart(remainingAMs = 20_000, desiredFadeMs = 8_000)
        assertEquals(FadeDecision.Start(8_000), decision)
    }

    @Test
    fun `waits for end when less than minimum remains`() {
        assertEquals(FadeDecision.WaitForEnd, TransitionTiming.decideFadeStart(remainingAMs = 1_499, desiredFadeMs = 8_000))
        assertEquals(FadeDecision.WaitForEnd, TransitionTiming.decideFadeStart(remainingAMs = 0, desiredFadeMs = 8_000))
        assertEquals(FadeDecision.Start(1_500), TransitionTiming.decideFadeStart(remainingAMs = 1_500, desiredFadeMs = 8_000))
    }

    @Test
    fun `a cut is always executed regardless of remaining audio`() {
        assertEquals(FadeDecision.Start(0), TransitionTiming.decideFadeStart(remainingAMs = 200, desiredFadeMs = 0))
        assertEquals(FadeDecision.Start(0), TransitionTiming.decideFadeStart(remainingAMs = 20_000, desiredFadeMs = 0))
    }

    // ── Progreso del fundido ────────────────────────────────────────────────

    @Test
    fun `progress follows the outgoing player position`() {
        assertEquals(0f, TransitionTiming.fadeProgress(positionAMs = 100_000, fadeStartPositionAMs = 100_000, fadeDurationMs = 8_000), 0f)
        assertEquals(0.5f, TransitionTiming.fadeProgress(positionAMs = 104_000, fadeStartPositionAMs = 100_000, fadeDurationMs = 8_000), 0.0001f)
        assertEquals(1f, TransitionTiming.fadeProgress(positionAMs = 108_000, fadeStartPositionAMs = 100_000, fadeDurationMs = 8_000), 0f)
        assertEquals(1f, TransitionTiming.fadeProgress(positionAMs = 120_000, fadeStartPositionAMs = 100_000, fadeDurationMs = 8_000), 0f)
    }

    @Test
    fun `progress is frozen when the outgoing player does not advance`() {
        val frozen = TransitionTiming.fadeProgress(positionAMs = 102_000, fadeStartPositionAMs = 100_000, fadeDurationMs = 8_000)
        val stillFrozen = TransitionTiming.fadeProgress(positionAMs = 102_000, fadeStartPositionAMs = 100_000, fadeDurationMs = 8_000)
        assertEquals(frozen, stillFrozen, 0f)
        // Si la posición retrocede, no se produce progreso negativo.
        assertEquals(0f, TransitionTiming.fadeProgress(positionAMs = 99_000, fadeStartPositionAMs = 100_000, fadeDurationMs = 8_000), 0f)
    }

    @Test
    fun `zero duration fade is complete immediately`() {
        assertEquals(1f, TransitionTiming.fadeProgress(positionAMs = 100_000, fadeStartPositionAMs = 100_000, fadeDurationMs = 0), 0f)
    }

    @Test
    fun `deadline adds the handoff timeout to the remaining audio`() {
        assertEquals(TransitionTiming.HANDOFF_TIMEOUT_MS + 8_000, TransitionTiming.transitionDeadlineMs(8_000))
        assertEquals(TransitionTiming.HANDOFF_TIMEOUT_MS, TransitionTiming.transitionDeadlineMs(-500))
    }

    // ── Punto de entrada ────────────────────────────────────────────────────

    @Test
    fun `playmix entry uses the editor point`() {
        assertEquals(12_000L, TransitionTiming.resolveEntryPointMs(EntryMode.PLAYMIX, playmixEntryMs = 12_000, intelligentInPointMs = 3_000, audioStartMs = 800))
        assertEquals(0L, TransitionTiming.resolveEntryPointMs(EntryMode.PLAYMIX, playmixEntryMs = null, intelligentInPointMs = 3_000, audioStartMs = 800))
    }

    @Test
    fun `simple entry uses the initial silence`() {
        assertEquals(800L, TransitionTiming.resolveEntryPointMs(EntryMode.SIMPLE, playmixEntryMs = 12_000, intelligentInPointMs = 3_000, audioStartMs = 800))
        assertEquals(0L, TransitionTiming.resolveEntryPointMs(EntryMode.SIMPLE, playmixEntryMs = null, intelligentInPointMs = null, audioStartMs = null))
    }

    @Test
    fun `intelligent entry is clamped`() {
        assertEquals(3_000L, TransitionTiming.resolveEntryPointMs(EntryMode.INTELLIGENT, playmixEntryMs = null, intelligentInPointMs = 3_000, audioStartMs = 800))
        assertEquals(0L, TransitionTiming.resolveEntryPointMs(EntryMode.INTELLIGENT, playmixEntryMs = null, intelligentInPointMs = -4_000, audioStartMs = 800))
        assertEquals(
            TransitionTiming.MAX_INTELLIGENT_ENTRY_MS.toLong(),
            TransitionTiming.resolveEntryPointMs(EntryMode.INTELLIGENT, playmixEntryMs = null, intelligentInPointMs = 35_900, audioStartMs = 800)
        )
    }

    @Test
    fun `clampIntelligentEntry bounds negative and late points`() {
        assertEquals(0, TransitionTiming.clampIntelligentEntry(-5_000))
        assertEquals(12_000, TransitionTiming.clampIntelligentEntry(40_000))
        assertEquals(6_500, TransitionTiming.clampIntelligentEntry(6_500))
    }

    @Test
    fun `preload seek only when far from the entry point`() {
        assertFalse(TransitionTiming.needsSeek(currentPositionMs = 0, entryMs = 200))
        assertTrue(TransitionTiming.needsSeek(currentPositionMs = 0, entryMs = 12_000))
        assertTrue(TransitionTiming.needsSeek(currentPositionMs = 12_600, entryMs = 12_000))
    }
}
