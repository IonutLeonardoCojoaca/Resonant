package com.example.resonant.managers

import com.example.resonant.data.network.StreakSettingsRequest
import com.example.resonant.data.network.StreakStatusDTO
import com.example.resonant.data.network.services.StreakService
import com.example.resonant.streak.SongOfDayResult
import com.example.resonant.streak.StreakResult
import com.example.resonant.unisound.errorResponse
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate

class StreakManagerTest {

    private class FakeStreakService : StreakService {
        var streakResponse: Response<StreakStatusDTO>? = null
        var settingsResponse: Response<Unit>? = null
        var songResponse: Response<ResponseBody>? = null
        var streakCalls = 0
        var songCalls = 0
        val sentZones = mutableListOf<String>()

        override suspend fun getStreak(): Response<StreakStatusDTO> {
            streakCalls++
            return streakResponse ?: throw IOException("offline")
        }

        override suspend fun putSettings(request: StreakSettingsRequest): Response<Unit> {
            sentZones += request.timeZone
            return settingsResponse ?: throw IOException("offline")
        }

        override suspend fun getSongOfDay(): Response<ResponseBody> {
            songCalls++
            return songResponse ?: throw IOException("offline")
        }
    }

    private class FakeTimeZoneStore : StreakTimeZoneStore {
        val saved = mutableMapOf<String, String>()
        override fun lastSent(userId: String): String? = saved[userId]
        override fun save(userId: String, timeZone: String) {
            saved[userId] = timeZone
        }
    }

    private val service = FakeStreakService()
    private val store = FakeTimeZoneStore()
    private var nowMs = 0L
    private var user: String? = "u1"
    private var day = LocalDate.of(2026, 10, 15)
    private val manager = StreakManager(
        service = service,
        userId = { user },
        timeZoneStore = store,
        now = { nowMs },
        today = { day }
    )

    private val status = StreakStatusDTO(currentDays = 12, todayQualified = true)

    private fun json(code: Int, body: String): Response<ResponseBody> =
        Response.success(code, body.toResponseBody("application/json".toMediaType()))

    private fun songJson(origin: String = "aria", title: String = "Like A Villain") = """
        { "song": { "id": "s1", "title": "$title", "artists": [ { "id": "a1", "name": "Bad Omens" } ] },
          "reason": "Porque sí", "origin": "$origin", "date": "2026-10-15" }
    """.trimIndent()

    // ── Racha ──

    @Test
    fun `status is cached for 30 seconds`() = runTest {
        service.streakResponse = Response.success(status)

        assertEquals(StreakResult.Ok(status), manager.getStatus())
        nowMs += 30_000L - 1
        manager.getStatus()
        assertEquals(1, service.streakCalls)

        nowMs += 2
        manager.getStatus()
        assertEquals(2, service.streakCalls)
    }

    @Test
    fun `force and invalidate skip the cache`() = runTest {
        service.streakResponse = Response.success(status)
        manager.getStatus()

        manager.getStatus(force = true)
        manager.invalidateStatus()
        manager.getStatus()

        assertEquals(3, service.streakCalls)
    }

    @Test
    fun `another user drops the cached status`() = runTest {
        service.streakResponse = Response.success(status)
        manager.getStatus()

        user = "u2"
        manager.getStatus()

        assertEquals(2, service.streakCalls)
    }

    @Test
    fun `feature disabled is disabled`() = runTest {
        service.streakResponse = errorResponse(404, "FEATURE_DISABLED")

        assertEquals(StreakResult.Disabled, manager.getStatus())
    }

    @Test
    fun `server and network errors fail and are not cached`() = runTest {
        service.streakResponse = errorResponse(429, "RATE_LIMITED")
        assertEquals(StreakResult.Failed, manager.getStatus())

        service.streakResponse = errorResponse(500, null)
        assertEquals(StreakResult.Failed, manager.getStatus())

        service.streakResponse = null
        assertEquals(StreakResult.Failed, manager.getStatus())

        assertEquals(3, service.streakCalls)
    }

    // ── Canción del día ──

    @Test
    fun `200 from aria is ready with the badge`() = runTest {
        service.songResponse = json(200, songJson(origin = "aria"))

        val result = manager.getSongOfDay() as SongOfDayResult.Ready

        assertEquals("s1", result.song.id)
        assertEquals("Bad Omens", result.song.artistName)
        assertEquals("Porque sí", result.reason)
        assertTrue(result.isAria)
        assertEquals("2026-10-15", result.date)
    }

    @Test
    fun `fallback and unknown origins have no badge`() = runTest {
        service.songResponse = json(200, songJson(origin = "resonant_ai"))
        assertFalse((manager.getSongOfDay() as SongOfDayResult.Ready).isAria)

        day = day.plusDays(1)
        service.songResponse = json(200, songJson(origin = "something_else"))
        assertFalse((manager.getSongOfDay() as SongOfDayResult.Ready).isAria)
    }

    @Test
    fun `202 is pending with its retry`() = runTest {
        service.songResponse = json(202, """{ "status": "pending", "retryAfterSeconds": 15 }""")

        assertEquals(SongOfDayResult.Pending(15), manager.getSongOfDay())
    }

    @Test
    fun `204 is empty`() = runTest {
        service.songResponse = Response.success(204, null)

        assertEquals(SongOfDayResult.Empty, manager.getSongOfDay())
    }

    @Test
    fun `incomplete song is empty`() = runTest {
        service.songResponse = json(200, """{ "song": { "id": "s1" }, "reason": "x", "origin": "aria" }""")

        assertEquals(SongOfDayResult.Empty, manager.getSongOfDay())
    }

    @Test
    fun `song of the day errors`() = runTest {
        service.songResponse = errorResponse(404, "FEATURE_DISABLED")
        assertEquals(SongOfDayResult.Disabled, manager.getSongOfDay())

        service.songResponse = errorResponse(500, null)
        assertEquals(SongOfDayResult.Failed, manager.getSongOfDay())

        service.songResponse = null
        assertEquals(SongOfDayResult.Failed, manager.getSongOfDay())
    }

    @Test
    fun `ready is reused the same day and asked again on a new day or user`() = runTest {
        service.songResponse = json(200, songJson())
        manager.getSongOfDay()
        manager.getSongOfDay()
        assertEquals(1, service.songCalls)

        service.songResponse = json(200, songJson())
        day = day.plusDays(1)
        manager.getSongOfDay()
        assertEquals(2, service.songCalls)

        service.songResponse = json(200, songJson())
        user = "u2"
        manager.getSongOfDay()
        assertEquals(3, service.songCalls)
    }

    @Test
    fun `pending is not reused`() = runTest {
        service.songResponse = json(202, """{ "status": "pending", "retryAfterSeconds": 15 }""")
        manager.getSongOfDay()
        service.songResponse = json(202, """{ "status": "pending", "retryAfterSeconds": 15 }""")
        manager.getSongOfDay()

        assertEquals(2, service.songCalls)
    }

    // ── Zona horaria ──

    @Test
    fun `first time sends the zone and saves it on 204`() = runTest {
        service.settingsResponse = Response.success(204, Unit)

        manager.syncTimeZoneIfNeeded("Europe/Madrid")

        assertEquals(listOf("Europe/Madrid"), service.sentZones)
        assertEquals("Europe/Madrid", store.saved["u1"])
    }

    @Test
    fun `same zone is not sent again`() = runTest {
        store.saved["u1"] = "Europe/Madrid"

        manager.syncTimeZoneIfNeeded("Europe/Madrid")

        assertTrue(service.sentZones.isEmpty())
    }

    @Test
    fun `a different zone is sent`() = runTest {
        store.saved["u1"] = "Europe/Madrid"
        service.settingsResponse = Response.success(204, Unit)

        manager.syncTimeZoneIfNeeded("America/Bogota")

        assertEquals(listOf("America/Bogota"), service.sentZones)
        assertEquals("America/Bogota", store.saved["u1"])
    }

    @Test
    fun `another user sends its own zone`() = runTest {
        store.saved["u1"] = "Europe/Madrid"
        service.settingsResponse = Response.success(204, Unit)
        user = "u2"

        manager.syncTimeZoneIfNeeded("Europe/Madrid")

        assertEquals(listOf("Europe/Madrid"), service.sentZones)
        assertEquals("Europe/Madrid", store.saved["u2"])
    }

    @Test
    fun `failures do not save the zone`() = runTest {
        service.settingsResponse = errorResponse(400, "STREAK_INVALID_TIMEZONE")
        manager.syncTimeZoneIfNeeded("Marte/Olympus")

        service.settingsResponse = errorResponse(500, null)
        manager.syncTimeZoneIfNeeded("Europe/Madrid")

        service.settingsResponse = null
        manager.syncTimeZoneIfNeeded("Europe/Madrid")

        assertEquals(3, service.sentZones.size)
        assertTrue(store.saved.isEmpty())
    }
}
