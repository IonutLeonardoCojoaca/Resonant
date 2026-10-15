package com.example.resonant.data.network

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreakContractTest {
    private val gson = Gson()

    @Test
    fun `parses full streak status from contract example`() {
        val json = """
            { "currentDays": 12, "longestDays": 31, "todaySeconds": 240, "goalSeconds": 600,
              "todayQualified": false, "freezesAvailable": 1, "freezeCoveringYesterday": false,
              "nextMilestone": 14, "date": "2026-10-12", "timeZone": "Europe/Madrid" }
        """.trimIndent()

        val status = gson.fromJson(json, StreakStatusDTO::class.java)

        assertEquals(12, status.currentDays)
        assertEquals(31, status.longestDays)
        assertEquals(240, status.todaySeconds)
        assertEquals(600, status.goalSeconds)
        assertFalse(status.todayQualified)
        assertEquals(1, status.freezesAvailable)
        assertFalse(status.freezeCoveringYesterday)
        assertEquals(14, status.nextMilestone)
        assertEquals("2026-10-12", status.date)
        assertEquals("Europe/Madrid", status.timeZone)
    }

    @Test
    fun `next milestone is null past the last one`() {
        val json = """{ "currentDays": 400, "longestDays": 400, "todayQualified": true, "nextMilestone": null }"""

        val status = gson.fromJson(json, StreakStatusDTO::class.java)

        assertNull(status.nextMilestone)
        assertTrue(status.todayQualified)
    }

    @Test
    fun `parses song of the day with nested song`() {
        val json = """
            { "song": { "id": "a1b2", "title": "Like A Villain", "duration": 245,
                        "imageUrl": "https://x/cover.jpg",
                        "artists": [ { "id": "c3d4", "name": "Bad Omens" } ] },
              "reason": "Bad Omens en su lado más oscuro…", "origin": "aria", "date": "2026-10-12" }
        """.trimIndent()

        val dto = gson.fromJson(json, SongOfDayDTO::class.java)

        assertEquals("a1b2", dto.song?.asJsonObject?.get("id")?.asString)
        assertEquals("Bad Omens en su lado más oscuro…", dto.reason)
        assertEquals("aria", dto.origin)
        assertEquals("2026-10-12", dto.date)
    }

    @Test
    fun `parses pending song of the day`() {
        val dto = gson.fromJson("""{ "status": "pending", "retryAfterSeconds": 15 }""", SongOfDayPendingDTO::class.java)

        assertEquals("pending", dto.status)
        assertEquals(15, dto.retryAfterSeconds)
    }

    @Test
    fun `settings request serializes the time zone`() {
        assertEquals("""{"timeZone":"Europe/Madrid"}""", gson.toJson(StreakSettingsRequest("Europe/Madrid")))
    }
}
