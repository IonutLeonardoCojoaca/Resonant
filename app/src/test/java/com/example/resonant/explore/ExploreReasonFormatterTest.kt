package com.example.resonant.explore

import com.example.resonant.data.network.ExploreReasonDTO
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExploreReasonFormatterTest {

    private fun reason(json: String) = Gson().fromJson(json, ExploreReasonDTO::class.java)

    private fun format(json: String, title: String = "Título") =
        ExploreReasonFormatter.format(reason(json), title)

    @Test
    fun `because artist names the artist`() {
        assertEquals(
            "Porque escuchas Bad Omens",
            format("""{ "code": "because_artist", "args": { "artistId": "a", "artistName": "Bad Omens" } }""", "Similares")
        )
    }

    @Test
    fun `because artist does not repeat an artist already in the title`() {
        assertEquals(
            "Basado en lo que más escuchas",
            format("""{ "code": "because_artist", "args": { "artistName": "Bad Omens" } }""", "Porque escuchas Bad Omens")
        )
    }

    @Test
    fun `daypart with and without weekend`() {
        assertEquals(
            "Elegido para esta noche",
            format("""{ "code": "daypart", "args": { "daypart": "evening", "isWeekend": false } }""")
        )
        assertEquals(
            "Elegido para esta mañana de fin de semana",
            format("""{ "code": "daypart", "args": { "daypart": "morning", "isWeekend": true } }""")
        )
        assertEquals("Elegido para esta tarde", format("""{ "code": "daypart", "args": { "daypart": "afternoon" } }"""))
        assertEquals("Elegido para esta noche", format("""{ "code": "daypart", "args": { "daypart": "night" } }"""))
    }

    @Test
    fun `fixed codes`() {
        assertEquals("Canciones que hace tiempo que no escuchas", format("""{ "code": "forgotten", "args": {} }"""))
        assertEquals("De artistas que sigues o escuchas", format("""{ "code": "new_from_followed_artist" }"""))
        assertEquals("Según lo que más escuchas", format("""{ "code": "genre_affinity", "args": {} }"""))
    }

    @Test
    fun `unknown code yields null`() {
        assertNull(format("""{ "code": "because_song", "args": { "songTitle": "Rio" } }"""))
        assertNull(format("""{ "code": "aria_pick", "args": { "batchDate": "2026-10-12" } }"""))
        assertNull(ExploreReasonFormatter.format(null, "Título"))
    }

    @Test
    fun `incomplete args yield null`() {
        assertNull(format("""{ "code": "because_artist", "args": {} }"""))
        assertNull(format("""{ "code": "because_artist", "args": { "artistName": "  " } }"""))
        assertNull(format("""{ "code": "daypart", "args": {} }"""))
        assertNull(format("""{ "code": "daypart", "args": { "daypart": "weekend" } }"""))
    }
}
