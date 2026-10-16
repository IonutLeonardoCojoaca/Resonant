package com.example.resonant.ui.adapters

import com.example.resonant.data.network.PlaymixRenderDTO
import com.example.resonant.data.network.PlaymixTransitionDTO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaymixTransitionLabelsTest {

    private fun transition(
        mixMode: String? = "crossfade",
        durationMs: Int = 14_100,
        gapMs: Int = 0,
        presetName: String? = null,
        modified: Boolean = false,
        render: PlaymixRenderDTO? = null
    ) = PlaymixTransitionDTO(
        id = "t1", fromPlaymixSongId = "a", toPlaymixSongId = "b",
        exitPointMs = 180_000, entryPointMs = 0, crossfadeDurationMs = durationMs,
        fadeCurveType = "linear", eqSettings = null, compatibility = null,
        mixMode = mixMode, presetName = presetName, isPresetModified = modified,
        gapMs = gapMs, render = render
    )

    @Test
    fun `automatic blend explains the technique and the tempos`() {
        val beatmatch = PlaymixTransitionLabels.summarize(transition("smart"), 128.0, 126.0)
        assertEquals("AUTO", beatmatch.eyebrow)
        assertEquals("Blend automático", beatmatch.title)
        assertEquals("Beatmatch · 128 → 126 BPM", beatmatch.details)

        val blend = PlaymixTransitionLabels.summarize(null, 140.0, 103.0)
        assertEquals("Blend ~8 s · 140 → 103 BPM", blend.details)

        assertEquals("Blend ~8 s", PlaymixTransitionLabels.summarize(null, null, 120.0).details)
    }

    @Test
    fun `advanced shows mode, duration, pause and preset`() {
        val s = PlaymixTransitionLabels.summarize(
            transition("club_drop", gapMs = 500, presetName = "Energy Boost", modified = true), null, null
        )
        assertEquals("AVANZADA", s.eyebrow)
        assertEquals("Club Drop", s.title)
        assertEquals("14,1 s · pausa 500 ms · Energy Boost editado", s.details)
    }

    @Test
    fun `a ready studio render wins over the manual mode`() {
        val s = PlaymixTransitionLabels.summarize(
            transition(render = PlaymixRenderDTO(status = "ready", durationMs = 9_000)), null, null
        )
        assertEquals("STUDIO", s.eyebrow)
        assertEquals("9 s · Editada en la web", s.details)
    }

    @Test
    fun `durations and verdicts`() {
        assertEquals("Corte", PlaymixTransitionLabels.duration(0))
        assertEquals("8 s", PlaymixTransitionLabels.duration(8_000))
        assertEquals("16,9 s", PlaymixTransitionLabels.duration(16_900))
        assertEquals("Encaje bueno", PlaymixTransitionLabels.verdict("good"))
        assertNull(PlaymixTransitionLabels.verdict(null))
    }
}
