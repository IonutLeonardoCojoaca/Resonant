package com.example.resonant.ui.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AriaPlaybackActionParsingTest {
    @Test
    fun `SSE action parser exposes authoritative playback fields`() {
        val payload = """
            {
              "type": "controlar_reproduccion",
              "comando": "play_song",
              "client_side": true,
              "action_id": "aria-action-id",
              "execution_status": "pending_client",
              "verified": true,
              "success": false,
              "song_id": "95edf595-543b-4559-9778-0ac823824747",
              "nombre_cancion": "Mayores",
              "nombre_artista": "Bad Bunny, Becky G",
              "selection_mode": "community_affinity",
              "song": {
                "song_id": "95edf595-543b-4559-9778-0ac823824747",
                "title": "Mayores",
                "artist_names": "Bad Bunny, Becky G",
                "album_id": "album-id",
                "album_title": "MALA SANTA",
                "duration_seconds": 203,
                "release_year": 2019,
                "streams": 1,
                "selection_mode": "future_selection_mode"
              },
              "log_id": "action-log-id",
              "unknown_future_field": true
            }
        """.trimIndent()

        val action = AriaViewModel().parseActionPayload(payload)

        assertNotNull(action)
        requireNotNull(action)
        assertEquals("controlar_reproduccion", action.type)
        assertEquals("play_song", action.playbackCommand)
        assertTrue(action.clientSide)
        assertEquals("aria-action-id", action.actionId)
        assertEquals("pending_client", action.executionStatus)
        assertEquals(true, action.verified)
        assertEquals(false, action.success)
        assertEquals("95edf595-543b-4559-9778-0ac823824747", action.songId)
        assertEquals(action.songId, action.clientSongId)
        assertEquals("community_affinity", action.selectionMode)
        assertEquals("future_selection_mode", action.song?.selectionMode)
        assertEquals("MALA SANTA", action.song?.albumTitle)
        assertEquals("action-log-id", action.logId)
    }

    @Test
    fun `SSE action parser exposes song disambiguation separately and completely`() {
        val payload = """
            {
              "type": "seleccionar_cancion",
              "action_id": "clarification-action-id",
              "execution_status": "needs_input",
              "success": null,
              "verified": true,
              "pregunta": "¿Cuál quieres que reproduzca?",
              "content": {
                "kind": "song_disambiguation",
                "songs": [
                  {
                    "choice_number": 2,
                    "song_id": "22222222-2222-2222-2222-222222222222",
                    "title": "Título Remix",
                    "artist_names": "Artista, Remixer",
                    "album_id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                    "album_title": "Álbum Remix",
                    "release_year": 2024,
                    "duration_seconds": 210,
                    "genres": ["House"],
                    "match_kind": "version"
                  },
                  {
                    "choice_number": 1,
                    "song_id": "11111111-1111-1111-1111-111111111111",
                    "title": "Título Original",
                    "artist_names": "Artista",
                    "match_kind": "exact_title"
                  }
                ]
              }
            }
        """.trimIndent()

        val action = requireNotNull(AriaViewModel().parseActionPayload(payload))

        assertEquals("seleccionar_cancion", action.type)
        assertEquals("clarification-action-id", action.actionId)
        assertEquals("needs_input", action.executionStatus)
        assertEquals(true, action.verified)
        assertNull(action.success)
        assertEquals("¿Cuál quieres que reproduzca?", action.question)
        assertEquals("song_disambiguation", action.entityKind)
        assertNull(action.songRecommendations)
        assertEquals(2, action.songDisambiguation?.size)
        val remix = requireNotNull(action.songDisambiguation?.first())
        assertEquals(2, remix.choiceNumber)
        assertEquals("22222222-2222-2222-2222-222222222222", remix.songId)
        assertEquals("Título Remix", remix.title)
        assertEquals("Artista, Remixer", remix.artistNames)
        assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", remix.albumId)
        assertEquals("Álbum Remix", remix.albumTitle)
        assertEquals(2024, remix.releaseYear)
        assertEquals(210, remix.durationSeconds)
        assertEquals(listOf("House"), remix.genres)
        assertEquals("version", remix.matchKind)
    }

    @Test
    fun `pending disambiguation blocks playback until the next user turn`() {
        val viewModel = AriaViewModel()
        val clarification = AriaAction(
            type = "seleccionar_cancion",
            actionId = "clarification-id",
            executionStatus = "needs_input",
            verified = true,
            entityKind = "song_disambiguation",
            songDisambiguation = listOf(
                AriaSongDisambiguationChoice(
                    choiceNumber = 1,
                    songId = "11111111-1111-1111-1111-111111111111",
                    title = "Timeless"
                ),
                AriaSongDisambiguationChoice(
                    choiceNumber = 2,
                    songId = "22222222-2222-2222-2222-222222222222",
                    title = "Timeless (AHTD Tour)"
                )
            )
        )
        val prematurePlayback = AriaAction(
            type = "controlar_reproduccion",
            actionId = "premature-playback-id",
            executionStatus = "pending_client",
            clientSide = true,
            playbackCommand = "play_song",
            clientSongId = "22222222-2222-2222-2222-222222222222"
        )

        assertFalse(viewModel.shouldExecuteClientAction(clarification))
        assertTrue(viewModel.isAwaitingSongChoice.value)
        assertFalse(viewModel.shouldExecuteClientAction(prematurePlayback))

        viewModel.addUserMessage("Elijo la opción 1")

        assertFalse(viewModel.isAwaitingSongChoice.value)
        assertTrue(viewModel.shouldExecuteClientAction(prematurePlayback))
    }
}
