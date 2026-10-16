package com.example.resonant.recap

import org.junit.Assert.assertEquals
import org.junit.Test

class RecapShareTest {

    @Test
    fun `file name has period, position and type only`() {
        assertEquals("recap_2026-09_2_top_songs.png", RecapShareFiles.fileName("2026-09", 1, "top_songs"))
        assertEquals("recap_2026-09_1_card.png", RecapShareFiles.fileName("2026-09", 0, "../"))
    }

    @Test
    fun `only png files older than a day expire`() {
        val now = 10L * RecapShareFiles.MAX_AGE_MS
        val files = mapOf(
            "old.png" to now - RecapShareFiles.MAX_AGE_MS - 1,
            "new.png" to now - 1_000,
            "old.txt" to 0L
        )
        assertEquals(listOf("old.png"), RecapShareFiles.expired(files, now))
    }
}
