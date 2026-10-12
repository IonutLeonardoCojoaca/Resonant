package com.example.resonant.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreSectionMapperTest {

    private val mapper = ExploreSectionMapper()

    @Test
    fun `songs section decodes songs`() {
        val section = mapper.map(sectionJson(songsSection("forgotten", "s1", "s2")))

        section as ExploreSectionUi.Songs
        assertEquals(listOf("s1", "s2"), section.items?.map { it.id })
        assertEquals("Title forgotten", section.title)
    }

    @Test
    fun `albums section decodes albums`() {
        val section = mapper.map(sectionJson(
            """{ "id": "new_from_artists", "type": "albums", "title": "Novedades",
                 "items": [ { "id": "a1", "title": "Album", "artistName": "Artist", "artists": [], "imageUrl": "u" } ] }"""
        ))

        section as ExploreSectionUi.Albums
        assertEquals("a1", section.items?.single()?.id)
    }

    @Test
    fun `artists section decodes artists and collab finder gets its own variant`() {
        val artists = """[ { "id": "ar1", "name": "Bad Omens", "imageUrl": "u" }, { "id": "ar2", "name": "Spiritbox" } ]"""
        val plain = mapper.map(sectionJson("""{ "id": "top_artists", "type": "artists", "title": "T", "items": $artists }"""))
        val collab = mapper.map(sectionJson("""{ "id": "collab_finder", "type": "artists", "title": "Collab Finder", "items": $artists }"""))

        plain as ExploreSectionUi.Artists
        assertEquals(2, plain.items?.size)
        collab as ExploreSectionUi.CollabFinder
        assertEquals("Bad Omens", collab.items?.first()?.name)
    }

    @Test
    fun `playlist keeps ownerName although the model field is transient`() {
        val section = mapper.map(sectionJson(
            """{ "id": "community_playlists", "type": "playlists", "title": "Comunidad",
                 "items": [ { "id": "p1", "name": "Indie", "imageUrl": "u", "numberOfTracks": 32,
                              "isSystemPlaylist": true, "ownerName": "Resonant" } ] }"""
        ))

        section as ExploreSectionUi.Playlists
        val playlist = section.items!!.single()
        assertEquals("Resonant", playlist.ownerName)
        assertEquals(32, playlist.numberOfTracks)
        assertTrue(playlist.isSystemPlaylist)
    }

    @Test
    fun `genres section decodes genres`() {
        val section = mapper.map(sectionJson(
            """{ "id": "your_genres", "type": "genres", "title": "Tus géneros",
                 "items": [ { "id": "g1", "name": "Metal", "gradientColors": "#000,#fff" } ] }"""
        ))

        section as ExploreSectionUi.Genres
        assertEquals("Metal", section.items?.single()?.name)
    }

    @Test
    fun `genre without name rejects the section`() {
        assertNull(mapper.map(sectionJson(
            """{ "id": "your_genres", "type": "genres", "title": "T", "items": [ { "id": "g1" } ] }"""
        )))
    }

    @Test
    fun `unknown type is ignored`() {
        assertNull(mapper.map(sectionJson(
            """{ "id": "live", "type": "live_sessions", "title": "En directo", "items": [ {} ] }"""
        )))
        assertNull(mapper.map(sectionJson(pendingSection("room", type = "unisound_current"))))
    }

    @Test
    fun `empty items and error hide the section`() {
        assertNull(mapper.map(sectionJson("""{ "id": "forgotten", "type": "songs", "title": "T", "items": [] }""")))
        assertNull(mapper.map(sectionJson(
            """{ "id": "forgotten", "type": "songs", "title": "T", "items": [],
                 "error": { "code": "EXPLORE_SECTION_UNAVAILABLE", "retryable": true } }"""
        )))
    }

    @Test
    fun `null items is pending`() {
        val section = mapper.map(sectionJson(pendingSection("forgotten")))

        assertTrue(section is ExploreSectionUi.Songs)
        assertTrue(section!!.isPending)
    }

    @Test
    fun `incomplete song rejects the whole section`() {
        assertNull(mapper.map(sectionJson(
            """{ "id": "forgotten", "type": "songs", "title": "T",
                 "items": [ ${songJson("s1")}, { "id": "s2", "title": "" } ] }"""
        )))
    }

    @Test
    fun `aria picks are sorted by rank and items without reason are dropped`() {
        val section = mapper.map(sectionJson(
            """{ "id": "aria_picks", "type": "aria_picks", "title": "Aria ha elegido para ti",
                 "items": [ ${ariaPickJson("s3", "Tercera", 3)}, ${ariaPickJson("s1", "Primera", 1)},
                            ${ariaPickJson("s2", null, 2)}, ${ariaPickJson("s4", "  ", 4)} ] }"""
        ))

        section as ExploreSectionUi.AriaPicks
        assertEquals(listOf("s1", "s3"), section.items?.map { it.song.id })
        assertEquals("Primera", section.items?.first()?.reason)
    }

    @Test
    fun `aria picks without any reason hide the section`() {
        assertNull(mapper.map(sectionJson(
            """{ "id": "aria_picks", "type": "aria_picks", "title": "T", "items": [ ${ariaPickJson("s1", null, 1)} ] }"""
        )))
    }

    @Test
    fun `subtitle comes from the reason`() {
        val section = mapper.map(sectionJson(
            """{ "id": "forgotten", "type": "songs", "title": "Vuelve a escuchar",
                 "reason": { "code": "forgotten", "args": {} }, "items": [ ${songJson("s1")} ] }"""
        ))

        assertEquals("Canciones que hace tiempo que no escuchas", section?.subtitle)
    }
}
