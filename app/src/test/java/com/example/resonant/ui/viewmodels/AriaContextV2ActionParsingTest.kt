package com.example.resonant.ui.viewmodels

import com.example.resonant.data.network.AriaClientActionDTO
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SSE action payloads of design.md ("Acciones SSE nuevas"), add-aria-context-v2. */
class AriaContextV2ActionParsingTest {
    private val viewModel = AriaViewModel()

    private fun parse(json: String): AriaAction = requireNotNull(viewModel.parseActionPayload(json))

    @Test
    fun `live tune in exposes slug and name and is executed`() {
        val action = parse(
            """{ "type": "live_tune_in", "client_side": true, "execution_status": "pending_client", "live_channel_slug": "chill", "live_channel_name": "Chill", "action_id": "A", "log_id": "L", "intent": "live_tune_in" }"""
        )

        assertEquals("chill", action.liveChannelSlug)
        assertEquals("Chill", action.liveChannelName)
        assertEquals("A", action.actionId)
        assertEquals("L", action.logId)
        assertTrue(viewModel.shouldExecuteClientAction(action))
    }

    @Test
    fun `the five client actions are executed only when pending on the client`() {
        listOf(
            """{ "type": "live_leave", "client_side": true, "execution_status": "pending_client", "live_channel_slug": "rock", "action_id": "1" }""",
            """{ "type": "unisound_create", "client_side": true, "execution_status": "pending_client", "action_id": "2" }""",
            """{ "type": "unisound_show_code", "client_side": true, "execution_status": "pending_client", "unisound_session_id": "S", "action_id": "3" }""",
            """{ "type": "open_screen", "client_side": true, "execution_status": "pending_client", "destination": "live_channel", "live_channel_slug": "chill", "action_id": "4" }"""
        ).forEach { assertTrue(it, viewModel.shouldExecuteClientAction(parse(it))) }

        val completed = parse("""{ "type": "open_screen", "client_side": true, "execution_status": "completed", "destination": "explore" }""")
        assertFalse(viewModel.shouldExecuteClientAction(completed))
    }

    @Test
    fun `open screen and show code keep destination and room`() {
        val open = parse(
            """{ "type": "open_screen", "client_side": true, "execution_status": "pending_client", "destination": "live_channel", "live_channel_slug": "chill", "action_id": "uuid" }"""
        )
        assertEquals("live_channel", open.destination)
        assertEquals("chill", open.liveChannelSlug)

        val code = parse(
            """{ "type": "unisound_show_code", "client_side": true, "execution_status": "pending_client", "unisound_session_id": "room-1", "action_id": "uuid" }"""
        )
        assertEquals("room-1", code.unisoundSessionId)
    }

    @Test
    fun `streak status builds the streak card and followups`() {
        val action = parse(
            """
            { "type": "streak_status", "execution_status": "completed", "verified": true, "success": true,
              "streak": { "current_days": 5, "longest_days": 12, "today_qualified": false, "today_seconds": 420, "goal_seconds": 900, "freezes_available": 1, "next_milestone": 7 },
              "content": { "kind": "streak", "current_days": 5, "longest_days": 12, "today_qualified": false, "today_seconds": 420, "goal_seconds": 900, "freezes_available": 1, "next_milestone": 7 },
              "pasos": [ { "intent": "live_tune_in", "type": "live_tune_in", "execution_status": "pending_client", "resumen": "canal Chill" }, { "intent": "streak_status", "execution_status": "completed", "resumen": "5 días, hoy faltan 8 min" } ],
              "n_pasos": 2,
              "suggested_followups": ["Ponme algo para cerrar la racha", "Sal de la radio"] }
            """.trimIndent()
        )

        val card = requireNotNull(action.streakCard)
        assertEquals(5, card.currentDays)
        assertEquals(12, card.longestDays)
        assertEquals(47, card.progressPercent)
        assertEquals(7, card.nextMilestone)
        assertEquals("streak", action.entityKind)
        assertEquals(listOf("Ponme algo para cerrar la racha", "Sal de la radio"), action.suggestedFollowups)
        assertFalse(viewModel.shouldExecuteClientAction(action))
    }

    @Test
    fun `streak without goal seconds paints no card`() {
        val action = parse(
            """{ "type": "streak_status", "execution_status": "completed", "content": { "kind": "streak", "current_days": 5, "today_seconds": 420 } }"""
        )

        assertNull(action.streakCard)
    }

    @Test
    fun `explain pick builds the pick card with evidence`() {
        val action = parse(
            """
            { "type": "explain_pick", "execution_status": "completed", "verified": true, "success": true, "song_id": "song-1", "source": "aria_pick",
              "reason": "Llevas una semana con Bad Omens", "batch_date": "2026-10-14",
              "evidence": [ { "type": "artist_plays", "artist": "Bad Omens", "plays_30d": 23 }, { "type": "tempo_match", "bpm": 128, "typical_bpm": 125 } ],
              "content": { "kind": "pick_explanation", "song": { "id": "song-1", "title": "Just Pretend", "artist": "Bad Omens", "cover_url": "https://x/c.jpg" }, "source": "aria_pick", "reason": "Llevas una semana con Bad Omens", "evidence": ["23 escuchas de Bad Omens este mes"] } }
            """.trimIndent()
        )

        val card = requireNotNull(action.pickExplanation)
        assertEquals("song-1", card.songId)
        assertEquals("Elegida por Aria", card.sourceLabel)
        assertEquals(listOf("23 escuchas de Bad Omens este mes"), card.evidence)
        assertEquals("song-1", action.songId)
    }

    @Test
    fun `flow feedback keeps target and feedback`() {
        val action = parse(
            """{ "type": "flow_feedback", "execution_status": "completed", "verified": true, "success": true, "target_type": "artist", "target_name": "Bad Omens", "feedback": "less", "suggested_followups": ["Deshaz eso"] }"""
        )

        assertEquals("artist", action.targetType)
        assertEquals("Bad Omens", action.targetName)
        assertEquals("less", action.feedback)
        assertEquals(listOf("Deshaz eso"), action.suggestedFollowups)
    }

    @Test
    fun `save a resolved reference targets its song id, not the current song`() {
        val action = parse(
            """{ "type": "guardar_actual", "execution_status": "completed", "song_id": "earlier-song", "title": "La de antes", "artist": "Artista", "action_id": "f1" }"""
        )

        assertEquals("earlier-song", action.clientSongId)
        assertEquals("earlier-song", action.songId)
        assertEquals("La de antes", action.clientSongTitle)
        assertEquals("Artista", action.clientSongArtist)
        assertTrue(viewModel.shouldExecuteClientAction(action))

        val current = parse("""{ "type": "quitar_actual", "action_id": "f2" }""")
        assertNull(current.clientSongId)
        assertNull(current.songId)
    }

    @Test
    fun `DTO reads the new fields and ignores unknown ones`() {
        val dto = Gson().fromJson(
            """{ "type": "explain_pick", "destination": "streak", "evidence": [ { "type": "genre_top", "genre": "metalcore", "rank": 1 } ], "future": {"x": 1} }""",
            AriaClientActionDTO::class.java
        )

        assertEquals("streak", dto.destination)
        assertNotNull(dto.evidence)
        assertEquals("genre_top", dto.evidence!!.single()["type"])
    }
}
