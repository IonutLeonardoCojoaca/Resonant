package com.example.resonant.live

import com.example.resonant.data.models.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveDjPolicyTest {
    private fun slot(position: Int = 3, intro: String? = "Subimos el ritmo con Muse.", song: Song? = Song(id = "s", title = "Hysteria")) =
        LiveSlot(blockId = "b1", position = position, startUtcMs = 0L, durationMs = 200_000L, song = song, djIntro = intro)

    private fun decide(
        slot: LiveSlot? = slot(),
        positionMs: Long = 3_000L,
        presented: Set<String> = emptySet(),
        mode: LiveDjMode = LiveDjMode.VOICE,
        featureOn: Boolean = true,
        ttsReady: Boolean = true
    ) = LiveDjPolicy.decide(slot, positionMs, presented, mode, featureOn, ttsReady)

    @Test
    fun `entering at 3 s presents the intro`() {
        assertEquals(LiveDjDecision.Speak("b1:3", "Subimos el ritmo con Muse."), decide())
    }

    @Test
    fun `entering at 40 s does not present it`() {
        assertEquals(LiveDjDecision.None, decide(positionMs = 40_000L))
    }

    @Test
    fun `an intro already presented is not repeated`() {
        assertEquals(LiveDjDecision.None, decide(presented = setOf("b1:3")))
    }

    @Test
    fun `subtitles mode shows a subtitle`() {
        assertEquals(LiveDjDecision.Subtitle("b1:3", "Subimos el ritmo con Muse."), decide(mode = LiveDjMode.SUBTITLES))
    }

    @Test
    fun `off mode or capability off presents nothing`() {
        assertEquals(LiveDjDecision.None, decide(mode = LiveDjMode.OFF))
        assertEquals(LiveDjDecision.None, decide(featureOn = false))
    }

    @Test
    fun `voice without tts falls back to subtitle`() {
        assertEquals(LiveDjDecision.Subtitle("b1:3", "Subimos el ritmo con Muse."), decide(ttsReady = false))
    }

    @Test
    fun `null or blank intro presents nothing`() {
        assertEquals(LiveDjDecision.None, decide(slot = slot(intro = null)))
        assertEquals(LiveDjDecision.None, decide(slot = slot(intro = "   ")))
        assertEquals(LiveDjDecision.None, decide(slot = null))
        assertEquals(LiveDjDecision.None, decide(slot = slot(song = null)))
    }

    @Test
    fun `the opening at position 0 is treated like any intro`() {
        assertEquals(
            LiveDjDecision.Speak("b1:0", "Viernes noche en Resonant Rock."),
            decide(slot = slot(position = 0, intro = "Viernes noche en Resonant Rock."), positionMs = 0L)
        )
    }

    @Test
    fun `a text replaced before the song starts presents the latest one`() {
        // A /now brought "Primera."; a later one replaced it before the song began.
        val updated = slot(intro = "Segunda.")

        assertEquals(LiveDjDecision.Speak("b1:3", "Segunda."), decide(slot = updated, positionMs = 1_000L))
    }

    @Test
    fun `unknown stored mode reads as voice`() {
        assertEquals(LiveDjMode.VOICE, LiveDjMode.fromStored(null))
        assertEquals(LiveDjMode.VOICE, LiveDjMode.fromStored("LOUD"))
        assertEquals(LiveDjMode.SUBTITLES, LiveDjMode.fromStored("SUBTITLES"))
    }
}
