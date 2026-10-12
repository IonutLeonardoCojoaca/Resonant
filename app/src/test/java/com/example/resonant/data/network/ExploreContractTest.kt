package com.example.resonant.data.network

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreContractTest {
    private val gson = Gson()

    @Test
    fun `parses layout from the backend contract example`() {
        val json = """
            {
              "generatedAtUtc": "2026-10-11T21:30:00Z",
              "localDate": "2026-10-11",
              "daypart": "evening",
              "isWeekend": true,
              "sections": [
                { "id": "daypart", "type": "songs", "title": "Para tu noche",
                  "reason": { "code": "daypart", "args": { "daypart": "evening", "isWeekend": true } },
                  "items": [ { "id": "s1", "title": "Rio", "artistName": "Duran Duran", "artists": [], "imageUrl": "u", "duration": 214 } ] },
                { "id": "because:artist:6c1f", "type": "songs", "title": "Porque escuchas Bad Omens",
                  "reason": { "code": "because_artist", "args": { "artistId": "6c1f", "artistName": "Bad Omens" } },
                  "items": [] },
                { "id": "forgotten", "type": "songs", "title": "Vuelve a escuchar",
                  "reason": { "code": "forgotten", "args": {} }, "items": null },
                { "id": "community_playlists", "type": "playlists", "title": "Playlists de la comunidad", "items": null }
              ]
            }
        """.trimIndent()

        val response = gson.fromJson(json, ExploreResponseDTO::class.java)

        assertEquals("evening", response.daypart)
        assertTrue(response.isWeekend)
        assertEquals("2026-10-11", response.localDate)
        val sections = response.sections!!
        assertEquals(4, sections.size)
        assertEquals(1, sections[0].items?.size)
        assertEquals("evening", sections[0].reason?.args?.get("daypart")?.asString)
        assertTrue(sections[0].reason?.args?.get("isWeekend")!!.asBoolean)
        assertEquals("Bad Omens", sections[1].reason?.args?.get("artistName")?.asString)
        assertNull(sections[2].items)
        assertNull(sections[3].reason)
        assertNull(sections[3].items)
    }

    @Test
    fun `parses failed section error`() {
        val json = """
            { "id": "new_from_artists", "type": "albums", "title": "Novedades de tus artistas", "items": [],
              "error": { "code": "EXPLORE_SECTION_UNAVAILABLE", "message": "unavailable",
                         "correlationId": "c1", "retryable": true, "retryAfterSeconds": 10 } }
        """.trimIndent()

        val section = gson.fromJson(json, ExploreSectionDTO::class.java)

        assertEquals("EXPLORE_SECTION_UNAVAILABLE", section.error?.code)
        assertTrue(section.error!!.retryable)
        assertEquals(0, section.items?.size)
    }

    @Test
    fun `section without error leaves it null`() {
        val section = gson.fromJson(
            """{ "id": "forgotten", "type": "songs", "title": "Vuelve a escuchar", "items": [] }""",
            ExploreSectionDTO::class.java
        )

        assertNull(section.error)
        assertNotNull(section.items)
    }

    @Test
    fun `parses playlist and aria pick item extras`() {
        val playlist = gson.fromJson(
            """{ "id": "p1", "name": "Indie de domingo", "imageUrl": "u", "numberOfTracks": 32,
                 "isSystemPlaylist": false, "ownerName": "Ionut" }""",
            ExplorePlaylistItemDTO::class.java
        )
        val pick = gson.fromJson(
            """{ "id": "s1", "title": "Like a Villain", "reason": "Llevas una semana con Bad Omens", "rank": 1 }""",
            AriaPickItemDTO::class.java
        )

        assertEquals("Ionut", playlist.ownerName)
        assertEquals(32, playlist.numberOfTracks)
        assertFalse(playlist.isSystemPlaylist)
        assertEquals("Llevas una semana con Bad Omens", pick.reason)
        assertEquals(1, pick.rank)
    }

    @Test
    fun `feedback request serializes the feedback value`() {
        assertEquals("""{"feedback":"dismissed"}""", gson.toJson(AriaPickFeedbackRequest("dismissed")))
    }
}
