package com.example.resonant.ui.adapters

import com.example.resonant.ui.viewmodels.AriaAction
import com.example.resonant.ui.viewmodels.AriaSongDisambiguationChoice
import org.junit.Assert.assertEquals
import org.junit.Test

class AriaSongDisambiguationUiTest {
    @Test
    fun `choices render in choice_number order with available metadata`() {
        val action = AriaAction(
            type = "seleccionar_cancion",
            entityKind = "song_disambiguation",
            songDisambiguation = listOf(
                choice(number = 3, title = "Tercera"),
                choice(
                    number = 1,
                    title = "Primera",
                    artists = "Artista, Remixer",
                    album = "Álbum",
                    durationSeconds = 210
                ),
                choice(number = 2, title = "Segunda")
            )
        )

        val rows = AriaSongDisambiguationUi.sortedChoices(action)

        assertEquals(listOf(1, 2, 3), rows.map { it.choiceNumber })
        assertEquals("ELIGE UNA VERSIÓN", AriaSongDisambiguationUi.CARD_TITLE)
        assertEquals("Artista, Remixer · Álbum", AriaSongDisambiguationUi.subtitle(rows.first()))
        assertEquals("3:30", AriaSongDisambiguationUi.duration(rows.first().durationSeconds))
    }

    @Test
    fun `option 2 creates the normal Aria follow-up prompt`() {
        assertEquals("Elijo la opción 2", AriaSongDisambiguationUi.selectionPrompt(2))
    }

    private fun choice(
        number: Int,
        title: String,
        artists: String? = null,
        album: String? = null,
        durationSeconds: Int? = null
    ) = AriaSongDisambiguationChoice(
        choiceNumber = number,
        songId = "00000000-0000-0000-0000-00000000000$number",
        title = title,
        artistNames = artists,
        albumTitle = album,
        durationSeconds = durationSeconds
    )
}
