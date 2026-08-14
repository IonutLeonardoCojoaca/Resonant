package com.example.resonant.ui.adapters

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.resonant.R
import com.example.resonant.ui.viewmodels.AriaAction
import com.example.resonant.ui.viewmodels.AriaMessage
import com.example.resonant.ui.viewmodels.AriaMessageRole
import com.example.resonant.ui.viewmodels.AriaSongDisambiguationChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AriaChatAdapterDisambiguationTest {
    @Test
    fun option2_isRenderedInOrder_andSendsNumberedPromptWithoutOpeningSong() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var sentPrompt: String? = null
        var openedSongId: String? = null

        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme)
            val parent = FrameLayout(context)
            val view = LayoutInflater.from(context)
                .inflate(R.layout.item_aria_message_aria, parent, false)
            val holder = AriaChatAdapter.AriaViewHolder(view)
            val action = AriaAction(
                type = "seleccionar_cancion",
                executionStatus = "needs_input",
                verified = true,
                entityKind = "song_disambiguation",
                songDisambiguation = listOf(
                    choice(2, "Título Remix", "Artista, Remixer", "Álbum Remix", 2024, 210),
                    choice(1, "Título Original", "Artista", "Álbum", 2020, 180)
                )
            )

            holder.bind(
                message = AriaMessage(
                    role = AriaMessageRole.ARIA,
                    text = "¿Cuál quieres que reproduzca?",
                    isComplete = true,
                    intentType = "seleccionar_cancion",
                    actionData = action
                ),
                onFeedback = { _, _ -> },
                onActionCardClick = {},
                onSongCardClick = { openedSongId = it },
                onSongDisambiguationChoiceClick = { sentPrompt = it },
                onArtistCardClick = {},
                onSuggestedFollowupClick = {}
            )

            assertEquals(
                "ELIGE UNA VERSIÓN",
                view.findViewById<TextView>(R.id.songCardsKindLabel).text.toString()
            )
            val list = view.findViewById<LinearLayout>(R.id.songCardsList)
            assertEquals(2, list.childCount)
            assertEquals("1", choiceNumber(list.getChildAt(0) as LinearLayout))

            val option2 = list.getChildAt(1) as LinearLayout
            assertEquals("2", choiceNumber(option2))
            assertEquals("Título Remix", title(option2))
            assertEquals("Artista, Remixer · Álbum Remix", subtitle(option2))
            assertEquals(listOf("3:30", "2024"), metadata(option2))
            assertTrue(option2.performClick())
        }

        assertEquals("Elijo la opción 2", sentPrompt)
        assertNull(openedSongId)
    }

    private fun choiceNumber(row: LinearLayout): String =
        (row.getChildAt(0) as TextView).text.toString()

    private fun title(row: LinearLayout): String =
        ((row.getChildAt(1) as LinearLayout).getChildAt(0) as TextView).text.toString()

    private fun subtitle(row: LinearLayout): String =
        ((row.getChildAt(1) as LinearLayout).getChildAt(1) as TextView).text.toString()

    private fun metadata(row: LinearLayout): List<String> {
        val metadata = row.getChildAt(2) as LinearLayout
        return (0 until metadata.childCount).map { index ->
            (metadata.getChildAt(index) as TextView).text.toString()
        }
    }

    private fun choice(
        number: Int,
        title: String,
        artists: String,
        album: String,
        year: Int,
        duration: Int
    ) = AriaSongDisambiguationChoice(
        choiceNumber = number,
        songId = "00000000-0000-0000-0000-00000000000$number",
        title = title,
        artistNames = artists,
        albumTitle = album,
        releaseYear = year,
        durationSeconds = duration
    )
}
