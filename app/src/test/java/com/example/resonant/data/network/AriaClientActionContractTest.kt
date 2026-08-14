package com.example.resonant.data.network

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AriaClientActionContractTest {
    private val gson = Gson()

    @Test
    fun `parses new playback action fields and ignores unknown fields`() {
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
              "selection_mode": "future_mode_not_known_by_android",
              "song": {
                "song_id": "95edf595-543b-4559-9778-0ac823824747",
                "title": "Mayores",
                "artist_names": "Bad Bunny, Becky G",
                "album_id": "album-id",
                "album_title": "MALA SANTA",
                "duration_seconds": 203,
                "release_year": 2019,
                "streams": 1,
                "selection_mode": "community_affinity",
                "future_song_field": {"ignored": true}
              },
              "log_id": "action-log-id",
              "future_top_level_field": [1, 2, 3]
            }
        """.trimIndent()

        val action = gson.fromJson(payload, AriaClientActionDTO::class.java)

        assertEquals("95edf595-543b-4559-9778-0ac823824747", action.songId)
        assertEquals("aria-action-id", action.actionId)
        assertEquals("pending_client", action.executionStatus)
        assertEquals(true, action.verified)
        assertEquals(false, action.success)
        assertEquals("future_mode_not_known_by_android", action.selectionMode)
        assertEquals("community_affinity", action.song?.selectionMode)
        assertEquals("Mayores", action.song?.title)
        assertEquals("action-log-id", action.logId)
    }

    @Test
    fun `old action without new fields remains valid`() {
        val action = gson.fromJson(
            """{"type":"controlar_reproduccion","comando":"pause"}""",
            AriaClientActionDTO::class.java
        )

        assertEquals("pause", action.command)
        assertFalse(action.clientSide)
        assertNull(action.songId)
        assertNull(action.song)
        assertNull(action.selectionMode)
        assertNull(action.logId)
        assertNull(action.actionId)
        assertNull(action.executionStatus)
        assertNull(action.verified)
        assertNull(action.success)
    }
}
