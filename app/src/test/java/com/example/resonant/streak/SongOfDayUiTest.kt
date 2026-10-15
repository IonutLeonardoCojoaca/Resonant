package com.example.resonant.streak

import com.example.resonant.data.models.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class SongOfDayUiTest {
    private val song = Song(id = "s1", title = "Like A Villain")

    @Test
    fun `ready shows the card`() {
        val step = SongOfDayUi.from(SongOfDayResult.Ready(song, "Porque sí", isAria = true, date = "2026-10-15"), 0)

        assertEquals(SongOfDayUi.Step(SongOfDayUi.Ready(song, "Porque sí", isAria = true)), step)
    }

    @Test
    fun `pending with retries left loads and retries`() {
        assertEquals(
            SongOfDayUi.Step(SongOfDayUi.Loading, retryAfterSeconds = 15),
            SongOfDayUi.from(SongOfDayResult.Pending(15), retriesDone = 3)
        )
    }

    @Test
    fun `pending with retries exhausted hides`() {
        assertEquals(
            SongOfDayUi.Step(SongOfDayUi.Hidden),
            SongOfDayUi.from(SongOfDayResult.Pending(15), retriesDone = 4)
        )
    }

    @Test
    fun `empty disabled and failed hide`() {
        listOf(SongOfDayResult.Empty, SongOfDayResult.Disabled, SongOfDayResult.Failed).forEach {
            assertEquals(SongOfDayUi.Step(SongOfDayUi.Hidden), SongOfDayUi.from(it, 0))
        }
    }
}
