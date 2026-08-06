package com.example.resonant.data.network

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSectionItemDecoderTest {

    private val decoder = HomeSectionItemDecoder()

    @Test
    fun `song artistName is enriched from artists`() {
        val section = section(
            """
            {
              "id": "song-1",
              "title": "Rio",
              "imageUrl": "https://cdn.example/rio.jpg",
              "artists": [
                { "id": "artist-1", "name": "Duran Duran", "imageUrl": "https://cdn.example/artist.jpg" }
              ]
            }
            """
        )

        val songs = decoder.decodeSongs(section)

        assertEquals("Duran Duran", songs?.single()?.artistName)
    }

    @Test
    fun `nested artist object is metadata and not mistaken for an envelope`() {
        val section = section(
            """
            {
              "id": "song-1",
              "title": "Blinding Lights",
              "imageUrl": "https://cdn.example/blinding-lights.jpg",
              "artist": {
                "id": "artist-1",
                "name": "The Weeknd"
              }
            }
            """
        )

        val songs = decoder.decodeSongs(section)

        assertEquals("song-1", songs?.single()?.id)
        assertEquals("The Weeknd", songs?.single()?.artistName)
    }

    @Test
    fun `recommendation artist envelope is unwrapped`() {
        val section = section(
            """
            {
              "item": {
                "id": "artist-1",
                "name": "INNA",
                "imageUrl": "https://cdn.example/inna.jpg"
              },
              "score": 0.95,
              "reason": { "message": "Porque escuchaste INNA" }
            }
            """
        )

        val artists = decoder.decodeArtists(section)

        assertEquals("INNA", artists?.single()?.name)
        assertEquals("https://cdn.example/inna.jpg", artists?.single()?.url)
    }

    @Test
    fun `coverUrl alias is accepted for an artist`() {
        val section = section(
            """
            {
              "id": "artist-1",
              "name": "The Weeknd",
              "coverUrl": "https://cdn.example/weeknd.jpg"
            }
            """
        )

        val artists = decoder.decodeArtists(section)

        assertEquals("https://cdn.example/weeknd.jpg", artists?.single()?.url)
    }

    @Test
    fun `album artistName is enriched from artists`() {
        val section = section(
            """
            {
              "id": "album-1",
              "title": "After Hours",
              "artists": [
                { "id": "artist-1", "name": "The Weeknd" }
              ]
            }
            """
        )

        val albums = decoder.decodeAlbums(section)

        assertEquals("The Weeknd", albums?.single()?.artistName)
    }

    @Test
    fun `incomplete song projection rejects the entire section`() {
        val section = section(
            """
            {
              "id": "song-1",
              "title": "Rio",
              "imageUrl": "https://cdn.example/rio.jpg"
            }
            """
        )

        assertNull(decoder.decodeSongs(section))
    }

    @Test
    fun `invalid artist envelope rejects the entire section`() {
        val section = section(
            """
            {
              "item": {
                "id": "",
                "name": "",
                "imageUrl": null
              }
            }
            """
        )

        assertNull(decoder.decodeArtists(section))
    }

    @Test
    fun `empty backend section remains a valid empty section`() {
        val result = decoder.decodeSongs(
            HomeSectionDTO(
                id = "playback-history",
                title = "Escuchado recientemente",
                type = "songs"
            )
        )

        assertTrue(result?.isEmpty() == true)
    }

    private fun section(vararg items: String): HomeSectionDTO {
        return HomeSectionDTO(
            id = "test-section",
            title = "Test",
            type = "songs",
            items = items.map(JsonParser::parseString)
        )
    }
}
