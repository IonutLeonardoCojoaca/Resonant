package com.example.resonant.recap

import com.example.resonant.data.network.RecapDetailDTO
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecapCardParserTest {
    private val parser = RecapCardParser()

    private fun cards(json: String) = parser.parse(JsonParser.parseString(json).asJsonArray.toList())

    @Test
    fun `the nine example cards are parsed in order`() {
        val detail = Gson().fromJson(RecapSamples.DETAIL, RecapDetailDTO::class.java)
        val cards = parser.parse(detail.cards)

        assertEquals(
            listOf("minutes", "top_songs", "top_artists", "genres", "moment", "streak", "unisound", "aria", "playlist"),
            cards.map { it.type }
        )
        val minutes = cards[0] as RecapCard.Minutes
        assertEquals(842, minutes.minutes)
        assertEquals(38, minutes.changePercent)
        val songs = (cards[1] as RecapCard.TopSongs).songs
        assertEquals(listOf(1, 3), songs.map { it.rank })
        assertEquals("Bad Omens", songs[0].song.artistName)
        assertEquals("https://x/s1.jpg", songs[0].song.coverUrl)
        val artists = cards[2] as RecapCard.TopArtists
        assertEquals("Spiritbox", artists.newArtist?.artist?.name)
        val genres = cards[3] as RecapCard.Genres
        assertEquals("Metalcore", genres.top.name)
        assertEquals(0.05, genres.rising?.previousShare ?: 0.0, 0.0001)
        assertEquals("night", (cards[4] as RecapCard.Moment).slot)
        assertEquals(9, (cards[5] as RecapCard.Streak).longestStreakDays)
        assertTrue((cards[7] as RecapCard.Aria).fromAria)
        assertNull((cards[8] as RecapCard.Playlist).playlistId)
    }

    @Test
    fun `unknown card type is dropped`() {
        val cards = cards("""[ { "type": "nueva_tarjeta" }, { "type": "moment", "slot": "dawn", "share": 0.5 } ]""")

        assertEquals(listOf("moment"), cards.map { it.type })
    }

    @Test
    fun `optional nulls are accepted`() {
        val cards = cards(
            """[ { "type": "minutes", "minutes": 70, "previousMinutes": null, "changePercent": null },
                 { "type": "genres", "top": { "id": "g1", "name": "Pop", "share": 0.5 }, "rising": null },
                 { "type": "top_artists", "artists": [ { "rank": 1, "plays": 3, "minutes": 9,
                     "artist": { "id": "a1", "name": "A" } } ], "newArtist": null },
                 { "type": "aria", "text": "Hola.", "origin": "template" } ]"""
        )

        val minutes = cards[0] as RecapCard.Minutes
        assertNull(minutes.previousMinutes)
        assertNull(minutes.changePercent)
        assertNull((cards[1] as RecapCard.Genres).rising)
        assertNull((cards[2] as RecapCard.TopArtists).newArtist)
        assertTrue(!(cards[3] as RecapCard.Aria).fromAria)
    }

    @Test
    fun `broken cards are dropped without error`() {
        val cards = cards(
            """[ "texto", { "type": "minutes" }, { "type": "moment", "slot": "siesta", "share": 0.2 },
                 { "type": "top_songs", "songs": [ { "rank": 1, "song": { "id": "", "title": "" } } ] },
                 { "type": "streak", "longestStreakDays": 2, "daysListened": 5, "daysInMonth": 30 } ]"""
        )

        assertEquals(listOf("streak"), cards.map { it.type })
    }
}
