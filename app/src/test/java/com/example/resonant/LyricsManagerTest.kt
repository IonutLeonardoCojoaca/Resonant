package com.example.resonant

import com.example.resonant.managers.LyricsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsManagerTest {

    @Test
    fun parseLRC_basicTimestamps_parsesCorrectly() {
        val lrc = """
            [ar:Test Artist]
            [ti:Test Song]
            [00:04.15] First line
            [00:08.50] Second line
            [01:02.300] Third line
        """.trimIndent()

        val lines = LyricsManager.parseLRC(lrc)

        assertEquals(3, lines.size)

        assertEquals(4150L, lines[0].timeMs)
        assertEquals("First line", lines[0].text)

        assertEquals(8500L, lines[1].timeMs)
        assertEquals("Second line", lines[1].text)

        assertEquals(62300L, lines[2].timeMs)
        assertEquals("Third line", lines[2].text)
    }

    @Test
    fun parseLRC_emptyLines_areSkipped() {
        val lrc = """
            [00:01.00]
            [00:02.00] Some text
            [00:03.00]   
        """.trimIndent()

        val lines = LyricsManager.parseLRC(lrc)

        assertEquals(1, lines.size)
        assertEquals("Some text", lines[0].text)
    }

    @Test
    fun parseLRC_metadataLines_areSkipped() {
        val lrc = """
            [ar:Artist Name]
            [ti:Song Title]
            [al:Album Name]
            [by:LRC Creator]
            [00:10.00] Actual lyrics
        """.trimIndent()

        val lines = LyricsManager.parseLRC(lrc)

        assertEquals(1, lines.size)
        assertEquals("Actual lyrics", lines[0].text)
    }

    @Test
    fun parseLRC_sortsByTime() {
        val lrc = """
            [00:20.00] Later line
            [00:05.00] Earlier line
            [00:10.00] Middle line
        """.trimIndent()

        val lines = LyricsManager.parseLRC(lrc)

        assertEquals(3, lines.size)
        assertEquals(5000L, lines[0].timeMs)
        assertEquals(10000L, lines[1].timeMs)
        assertEquals(20000L, lines[2].timeMs)
    }

    @Test
    fun getCurrentLineIndex_returnsCorrectIndex() {
        val lines = LyricsManager.parseLRC("""
            [00:05.00] Line A
            [00:10.00] Line B
            [00:15.00] Line C
        """.trimIndent())

        assertEquals(-1, LyricsManager.getCurrentLineIndex(lines, 2000))
        assertEquals(0, LyricsManager.getCurrentLineIndex(lines, 5000))
        assertEquals(0, LyricsManager.getCurrentLineIndex(lines, 7000))
        assertEquals(1, LyricsManager.getCurrentLineIndex(lines, 10000))
        assertEquals(1, LyricsManager.getCurrentLineIndex(lines, 12000))
        assertEquals(2, LyricsManager.getCurrentLineIndex(lines, 15000))
        assertEquals(2, LyricsManager.getCurrentLineIndex(lines, 99000))
    }

    @Test
    fun getCurrentLineIndex_emptyList_returnsNegOne() {
        assertEquals(-1, LyricsManager.getCurrentLineIndex(emptyList(), 5000))
    }

    @Test
    fun parseLRC_twoDigitCentiseconds() {
        val lrc = "[00:04.15] Sí-sí, sí-sí-sí"
        val lines = LyricsManager.parseLRC(lrc)

        assertEquals(1, lines.size)
        assertEquals(4150L, lines[0].timeMs)
        assertEquals("Sí-sí, sí-sí-sí", lines[0].text)
    }

    @Test
    fun parseLRC_threeDigitMilliseconds() {
        val lrc = "[00:04.150] Sí-sí, sí-sí-sí"
        val lines = LyricsManager.parseLRC(lrc)

        assertEquals(1, lines.size)
        assertEquals(4150L, lines[0].timeMs)
        assertEquals("Sí-sí, sí-sí-sí", lines[0].text)
    }
}
