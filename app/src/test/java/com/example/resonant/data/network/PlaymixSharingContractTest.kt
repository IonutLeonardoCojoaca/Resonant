package com.example.resonant.data.network

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ejemplos de `docs/playmix-sharing-contract.md` (playmix-sharing-discovery). */
class PlaymixSharingContractTest {
    private val gson = Gson()

    @Test
    fun `parses ownership visibility and likes of a foreign playmix`() {
        val json = """
            { "id": "3f0c", "name": "Mix con Houdini", "description": null, "numberOfTracks": 12, "duration": 2400,
              "isPublic": true, "coverUrl": null, "songs": [], "transitions": [],
              "visibility": "public", "ownerId": "9a1e", "ownerName": "Daniel", "isOwner": false,
              "likeCount": 3, "isLiked": true, "sourcePlaymixId": null, "contentVersion": "9f2c41aa07d3be10" }
        """.trimIndent()

        val detail = gson.fromJson(json, PlaymixDetailDTO::class.java)

        assertEquals("public", detail.effectiveVisibility)
        assertEquals("Daniel", detail.ownerName)
        assertEquals(false, detail.isOwner)
        assertEquals(3, detail.likeCount)
        assertTrue(detail.isLiked)
        assertNull(detail.sourcePlaymixId)
    }

    @Test
    fun `old backend without the new fields falls back to isPublic`() {
        val json = """
            { "id": "p", "name": "Set", "numberOfTracks": 0, "duration": 0, "isPublic": false,
              "createdAt": "2026-10-01T00:00:00Z" }
        """.trimIndent()

        val playmix = gson.fromJson(json, PlaymixDTO::class.java)

        assertEquals("private", playmix.effectiveVisibility)
        assertNull(playmix.isOwner)
        assertEquals(0, playmix.likeCount)
        assertFalse(playmix.isLiked)
    }

    @Test
    fun `parses a public page with cursor`() {
        val json = """
            { "items": [ { "id": "a", "name": "Mix", "numberOfTracks": 12, "duration": 2400, "isPublic": false,
                           "createdAt": "2026-10-01T00:00:00Z", "visibility": "unlisted", "ownerName": "Ana",
                           "isOwner": true, "likeCount": 0, "isLiked": false } ],
              "nextCursor": "eyJWZXJzaW9uIjox" }
        """.trimIndent()

        val page = gson.fromJson(json, PlaymixPageDTO::class.java)

        assertEquals(1, page.items!!.size)
        assertEquals("unlisted", page.items!![0].effectiveVisibility)
        assertEquals(true, page.items!![0].isOwner)
        assertEquals("eyJWZXJzaW9uIjox", page.nextCursor)
    }

    @Test
    fun `parses a sort preview with seed`() {
        val json = """
            { "songs": [ { "playmixSongId": "ps1", "position": 0, "songId": "s1", "title": "Uno", "artist": "A",
                           "duration": 200, "customEntryMs": null, "customExitMs": null, "audioAnalysis": null } ],
              "seed": 184467 }
        """.trimIndent()

        val result = gson.fromJson(json, PlaymixSortResponseDTO::class.java)

        assertEquals("ps1", result.songs!![0].playmixSongId)
        assertEquals(184467, result.seed)
    }

    @Test
    fun `sort and merge requests serialize the contract fields`() {
        assertEquals(
            """{"criterion":"harmonic","direction":"asc","preview":true}""",
            gson.toJson(PlaymixSortRequest(criterion = "harmonic", preview = true))
        )
        assertEquals(
            """{"playmixIds":["a","b"],"name":"Fusión"}""",
            gson.toJson(PlaymixMergeRequest(listOf("a", "b"), "Fusión"))
        )
        assertEquals("""{"name":"Mix","visibility":"unlisted"}""", gson.toJson(CreatePlaymixRequest("Mix", visibility = "unlisted")))
        assertEquals("""{"name":"Mix"}""", gson.toJson(CreatePlaymixRequest("Mix")))
    }
}
