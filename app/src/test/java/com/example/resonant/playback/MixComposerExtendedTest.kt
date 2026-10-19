package com.example.resonant.playback

import com.example.resonant.data.network.MixSelectionDTO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Espejo de `MixComposerExtendedTests` del backend (playmix-extended-beatmatch 1.3 / 4.4). */
class MixComposerExtendedTest {

    private fun song(bpm: Double, stability: Double? = 0.97) = MixComposer.Song(
        durationMs = 240_000, audioStartMs = 0, audioEndMs = 240_000, bpm = bpm, bpmPrecise = bpm,
        bpmStability = stability, beatsPerBar = 4, firstDownbeatMs = 500,
        downbeats = (0 until 120).map { 500 + it * 2_000 }, mixInMs = 32_500, mixOutMs = 200_500
    )

    private val from = song(120.0)
    private val selection = MixSelectionDTO(volume = "overlap", eq = "bass-swap-center", filter = "lpf-in-out")

    @Test
    fun `extended range boundaries`() {
        for ((rate, expected) in listOf(0.94 to false, 0.9399 to true, 0.88 to true, 0.8799 to false, 1.12 to true, 1.1201 to false)) {
            val to = song(120.0 / rate)
            assertEquals("rate $rate", expected, MixComposer.inExtendedRange(from, to))
            assertTrue(MixComposer.tempoClash(from, to))
            assertEquals(!expected, MixComposer.tempoClash(from, to, extended = true))
            assertEquals(expected, MixComposer.canSync(from, to, extended = true))
            assertFalse(MixComposer.canSync(from, to))
        }
    }

    @Test
    fun `low stability is not extended even when forced`() {
        val to = song(120.0 / 1.08, stability = 0.7)
        assertFalse(MixComposer.inExtendedRange(from, to))
        val window = MixComposer.automaticWindow(from, to, extended = true)!!
        val doc = MixComposer.compose(selection, window, from, to, true, "test", extended = true)!!
        assertEquals(false, doc.beatSync)
        assertNull(doc.extendedSync)
    }

    @Test
    fun `mi gente to gasolina is synced over eight bars`() {
        val miGente = song(105.0)
        val gasolina = song(96.77)
        assertEquals(MixComposer.CLASH_BARS, MixComposer.automaticWindow(miGente, gasolina)!!.bars)
        val window = MixComposer.automaticWindow(miGente, gasolina, extended = true)!!
        assertEquals(MixComposer.LONG_BARS, window.bars)
        val doc = MixComposer.compose(selection, window, miGente, gasolina, null, "test", extended = true)!!
        assertEquals(true, doc.beatSync)
        assertEquals(true, doc.extendedSync)
    }

    @Test
    fun `danza kuduro and explicit no sync stay without extended flag`() {
        val kuduro = song(130.0)
        val miGente = song(105.0)
        val window = MixComposer.automaticWindow(kuduro, miGente, extended = true)!!
        assertEquals(MixComposer.CLASH_BARS, window.bars)
        assertNull(MixComposer.compose(selection, window, kuduro, miGente, true, "test", extended = true)!!.extendedSync)

        val gasolina = song(96.77)
        val w = MixComposer.automaticWindow(miGente, gasolina, extended = true)!!
        val off = MixComposer.compose(selection, w, miGente, gasolina, false, "test", extended = true)!!
        assertEquals(false, off.beatSync)
        assertNull(off.extendedSync)
    }

    @Test
    fun `without extended the result is the current one`() {
        for ((a, b) in listOf(105.0 to 96.77, 130.0 to 105.0, 128.0 to 126.0, 120.0 to 135.0)) {
            val songA = song(a)
            val songB = song(b)
            for (beatSync in listOf(null, true, false)) {
                val current = MixComposer.compose(selection, MixComposer.automaticWindow(songA, songB)!!, songA, songB, beatSync, "t")
                val off = MixComposer.compose(
                    selection, MixComposer.automaticWindow(songA, songB, extended = false)!!, songA, songB, beatSync, "t", extended = false
                )
                assertEquals(current, off)
            }
        }
    }

    @Test
    fun `standard pairs are identical with extended`() {
        val songB = song(126.0)
        val songA = song(128.0)
        val plain = MixComposer.compose(selection, MixComposer.automaticWindow(songA, songB)!!, songA, songB, null, "t")
        val wide = MixComposer.compose(selection, MixComposer.automaticWindow(songA, songB, extended = true)!!, songA, songB, null, "t", extended = true)
        assertEquals(plain, wide)
        assertNull(wide!!.extendedSync)
    }
}
