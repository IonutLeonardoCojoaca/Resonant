package com.example.resonant.playback

import com.example.resonant.data.network.PlaymixTransitionDTO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaymixMixModeTest {

    private fun transition(mixMode: String?) = PlaymixTransitionDTO(
        id = "t1", fromPlaymixSongId = "a", toPlaymixSongId = "b",
        exitPointMs = 180_000, entryPointMs = 0, crossfadeDurationMs = 8_000,
        fadeCurveType = "linear", eqSettings = null, compatibility = null,
        mixMode = mixMode
    )

    @Test
    fun `smart and a missing transition are automatic blends`() {
        assertTrue(PlaymixMixMode.isSmart(transition("smart")))
        assertTrue(PlaymixMixMode.isSmart(null))
        assertNull(PlaymixMixMode.advancedOrNull(transition("smart")))
        assertNull(PlaymixMixMode.advancedOrNull(null))
    }

    @Test
    fun `manual modes stay advanced, including an absent mixMode`() {
        listOf("crossfade", "overlap", "freq_split", "club_drop", "hard_edit", null).forEach { mode ->
            val t = transition(mode)
            assertFalse(mode.toString(), PlaymixMixMode.isSmart(t))
            assertSame(t, PlaymixMixMode.advancedOrNull(t))
        }
    }

    @Test
    fun `close tempos predict a beatmatch, distant or unknown ones a blend`() {
        assertEquals(PlaymixMixMode.SmartStyle.BEATMATCH, PlaymixMixMode.predictSmartStyle(128.0, 126.0))
        assertEquals(PlaymixMixMode.SmartStyle.BEATMATCH, PlaymixMixMode.predictSmartStyle(120.0, 120.0))
        assertEquals(PlaymixMixMode.SmartStyle.BLEND, PlaymixMixMode.predictSmartStyle(140.0, 103.0))
        assertEquals(PlaymixMixMode.SmartStyle.BLEND, PlaymixMixMode.predictSmartStyle(0.0, 120.0))
    }

    @Test
    fun `the prediction uses the planner tolerance`() {
        // 6 % es el límite de TransitionPlanner.beatmatchRatio.
        assertEquals(PlaymixMixMode.SmartStyle.BEATMATCH, PlaymixMixMode.predictSmartStyle(105.9, 100.0))
        assertEquals(PlaymixMixMode.SmartStyle.BLEND, PlaymixMixMode.predictSmartStyle(106.5, 100.0))
    }
}
