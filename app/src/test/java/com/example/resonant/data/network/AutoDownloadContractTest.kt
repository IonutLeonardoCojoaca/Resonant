package com.example.resonant.data.network

import com.example.resonant.data.models.AutoDownloadClaimResponseDTO
import com.example.resonant.data.models.AutoDownloadPlanListDTO
import com.example.resonant.data.models.AutoDownloadSubscriptionDTO
import com.example.resonant.data.models.AutoDownloadSubscriptionListDTO
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ejemplos JSON de design.md de `add-auto-downloads`. */
class AutoDownloadContractTest {
    private val gson = Gson()

    @Test
    fun `parses subscription list`() {
        val json = """
            { "maxSubscriptions": 100, "items": [ { "artistId": "3f6c", "name": "Rosalía",
              "imageUrl": "https://resonantapp.ddns.net/a.jpg", "createdAtUtc": "2026-10-15T09:12:00.000Z" } ] }
        """.trimIndent()

        val list = gson.fromJson(json, AutoDownloadSubscriptionListDTO::class.java)

        assertEquals(100, list.maxSubscriptions)
        val item = list.items!!.single()
        assertEquals("3f6c", item.artistId)
        assertEquals("Rosalía", item.name)
        assertEquals("2026-10-15T09:12:00.000Z", item.createdAtUtc)
    }

    @Test
    fun `parses single subscription`() {
        val json = """{ "artistId": "3f6c", "name": "Rosalía", "imageUrl": null, "createdAtUtc": "2026-10-15T09:12:00.000Z" }"""

        val item = gson.fromJson(json, AutoDownloadSubscriptionDTO::class.java)

        assertEquals("3f6c", item.artistId)
        assertNull(item.imageUrl)
    }

    @Test
    fun `parses plan list with song`() {
        val json = """
            { "items": [ { "planId": "a1b2", "songId": "9d8e", "reasonArtistId": "3f6c",
              "reasonArtistName": "Rosalía", "batchId": "job-7f3a", "status": "pending", "resultCode": null,
              "createdAtUtc": "2026-10-15T08:00:00.000Z", "contentLength": 8421376,
              "song": { "id": "9d8e", "title": "Canción", "artistName": "Rosalía",
                        "album": { "id": "al1", "title": "Álbum" }, "duration": "3:21", "imageUrl": "https://x/c.jpg" } } ],
              "hasMore": false }
        """.trimIndent()

        val page = gson.fromJson(json, AutoDownloadPlanListDTO::class.java)

        assertFalse(page.hasMore)
        val plan = page.items!!.single()
        assertEquals("a1b2", plan.planId)
        assertEquals("pending", plan.status)
        assertNull(plan.resultCode)
        assertEquals(8_421_376L, plan.contentLength)
        assertEquals("9d8e", plan.song!!.id)
        assertEquals("https://x/c.jpg", plan.song!!.coverUrl)
    }

    @Test
    fun `content length can be null`() {
        val json = """{ "items": [ { "planId": "p", "songId": "s", "contentLength": null } ], "hasMore": true }"""

        val page = gson.fromJson(json, AutoDownloadPlanListDTO::class.java)

        assertTrue(page.hasMore)
        assertNull(page.items!!.single().contentLength)
    }

    @Test
    fun `parses claim response`() {
        val json = """{ "planId": "a1b2", "claimedUntilUtc": "2026-10-15T08:40:00.000Z", "attempt": 1 }"""

        val claim = gson.fromJson(json, AutoDownloadClaimResponseDTO::class.java)

        assertEquals(1, claim.attempt)
        assertEquals("2026-10-15T08:40:00.000Z", claim.claimedUntilUtc)
    }

    @Test
    fun `capabilities expose autoDownloads`() {
        val dto = gson.fromJson("""{ "features": { "autoDownloads": true } }""", CapabilitiesDTO::class.java)

        assertTrue(dto.features!!.autoDownloads)
    }
}
