package com.example.resonant.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongInsightsFormatterTest {

    @Test
    fun `keys are read in the usual notations`() {
        assertEquals("Do♯ menor", SongInsightsFormatter.keyName("C#m"))
        assertEquals("Si♭ mayor", SongInsightsFormatter.keyName("Bbmaj"))
        assertEquals("Fa♯ menor", SongInsightsFormatter.keyName("F# minor"))
        assertEquals("La mayor", SongInsightsFormatter.keyName("A"))
        assertEquals("E♭m", SongInsightsFormatter.keyShort("Ebm"))
        assertNull(SongInsightsFormatter.keyName("H"))
        assertNull(SongInsightsFormatter.keyName(""))
    }

    @Test
    fun `camelot comes from the key when the server does not send it`() {
        assertEquals("8B", SongInsightsFormatter.camelot(null, "C"))
        assertEquals("8A", SongInsightsFormatter.camelot(null, "Am"))
        assertEquals("5A", SongInsightsFormatter.camelot(null, "Cm"))
        assertEquals("12A", SongInsightsFormatter.camelot(null, "C#m"))
        assertEquals("12A", SongInsightsFormatter.camelot(null, "Dbm"))
        assertEquals("5B", SongInsightsFormatter.camelot(null, "Eb"))
        assertEquals("3A", SongInsightsFormatter.camelot("3a", "C"))
        assertNull(SongInsightsFormatter.camelot("13A", null))
    }

    @Test
    fun `harmonic neighbours wrap around the wheel`() {
        assertEquals(listOf("4A", "6A", "5B"), SongInsightsFormatter.camelotNeighbours("5A"))
        assertEquals(listOf("12B", "2B", "1A"), SongInsightsFormatter.camelotNeighbours("1B"))
        assertEquals(emptyList<String>(), SongInsightsFormatter.camelotNeighbours(null))
    }

    @Test
    fun `tempo and loudness scales`() {
        assertEquals("Bailable", SongInsightsFormatter.tempoFeel(129.0))
        assertEquals("Lento", SongInsightsFormatter.tempoFeel(70.0))
        assertNull(SongInsightsFormatter.tempoFeel(0.0))
        assertEquals(0f, SongInsightsFormatter.loudnessFraction(-30.0)!!, 0.001f)
        assertEquals(1f, SongInsightsFormatter.loudnessFraction(-3.0)!!, 0.001f)
        assertEquals(0.64f, SongInsightsFormatter.loudnessFraction(-11.2)!!, 0.001f)
        assertEquals("Equilibrada", SongInsightsFormatter.loudnessFeel(-11.2))
        assertEquals("-11,2", SongInsightsFormatter.loudnessText(-11.2))
        assertNull(SongInsightsFormatter.loudnessFraction(0.0))
    }

    @Test
    fun `markers are placed inside the song and sorted`() {
        val markers = SongInsightsFormatter.markers(
            SongInsights(durationMs = 200_000, mixOutMs = 180_000, mixInMs = 20_000, dropMs = 60_000)
        )
        assertEquals(listOf("ENTRA", "DROP", "SALE"), markers.map { it.label })
        assertEquals(0.1f, markers.first().fraction, 0.0001f)
        assertEquals(emptyList<SongInsightsFormatter.Marker>(), SongInsightsFormatter.markers(SongInsights(mixInMs = 1000)))
    }

    @Test
    fun `resampling keeps the peaks`() {
        val values = listOf(0.1f, 0.9f, 0.2f, 0.3f, 0.8f, 0.1f)
        assertEquals(listOf(0.9f, 0.3f, 0.8f), SongInsightsFormatter.resample(values, 3))
        assertEquals(values, SongInsightsFormatter.resample(values, 10))
    }

    @Test
    fun `segments in dB or plain numbers are normalized`() {
        assertEquals(listOf(0f, 1f, 0.5f), SongInsightsFormatter.parseSegments(listOf(-60.0, -20.0, -40.0)))
        assertEquals(
            listOf(0f, 1f),
            SongInsightsFormatter.parseSegments(listOf(mapOf("loudness" to -30.0), mapOf("loudness" to -10.0)))
        )
        assertEquals(listOf(0.5f, 1f), SongInsightsFormatter.parseSegments(listOf(2, 4)))
    }

    @Test
    fun `compact counts and durations`() {
        assertEquals("950", DetailFormat.compactCount(950))
        assertEquals("1,3 K", DetailFormat.compactCount(1_300))
        assertEquals("12 K", DetailFormat.compactCount(12_000))
        assertEquals("4,5 M", DetailFormat.compactCount(4_500_000))
        assertEquals("48:03", DetailFormat.longDuration(2_883))
        assertEquals("1 h 05 min", DetailFormat.longDuration(3_900))
    }
}
