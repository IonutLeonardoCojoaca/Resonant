package com.example.resonant.managers

import com.example.resonant.data.network.RecapDetailDTO
import com.example.resonant.data.network.RecapPlaylistDTO
import com.example.resonant.data.network.RecapSummaryDTO
import com.example.resonant.data.network.services.RecapService
import com.example.resonant.recap.RecapSamples
import com.example.resonant.unisound.errorResponse
import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class RecapManagerTest {

    private class FakeRecapService : RecapService {
        var listResponse: Response<List<RecapSummaryDTO>>? = null
        var detailResponse: Response<RecapDetailDTO>? = null
        var seenResponse: Response<Unit>? = null
        var playlistResponse: Response<RecapPlaylistDTO>? = null
        var listCalls = 0
        val seen = mutableListOf<String>()

        override suspend fun list(): Response<List<RecapSummaryDTO>> {
            listCalls++
            return listResponse ?: throw IOException("offline")
        }

        override suspend fun get(periodKey: String): Response<RecapDetailDTO> =
            detailResponse ?: throw IOException("offline")

        override suspend fun markSeen(periodKey: String): Response<Unit> {
            seen += periodKey
            return seenResponse ?: throw IOException("offline")
        }

        override suspend fun savePlaylist(periodKey: String): Response<RecapPlaylistDTO> =
            playlistResponse ?: throw IOException("offline")
    }

    private class FakeDismissStore : RecapDismissStore {
        val stored = mutableMapOf<String, Set<String>>()
        override fun dismissed(userId: String): Set<String> = stored[userId].orEmpty()
        override fun dismiss(userId: String, periodKey: String) {
            stored[userId] = dismissed(userId) + periodKey
        }
    }

    private val service = FakeRecapService()
    private val store = FakeDismissStore()
    private var nowMs = 0L
    private var user: String? = "u1"
    private val manager = RecapManager(service, { user }, store, now = { nowMs })

    private val recaps = listOf(RecapSummaryDTO("2026-09", seen = false, generatedAt = "2026-10-01T01:04:40Z"))

    @Test
    fun `list is cached for sixty seconds and invalidate forces a refetch`() = runTest {
        service.listResponse = Response.success(recaps)

        assertEquals(RecapResult.Ok(recaps), manager.list())
        nowMs += RecapManager.LIST_CACHE_MS - 1
        manager.list()
        assertEquals(1, service.listCalls)

        manager.invalidate()
        manager.list()
        assertEquals(2, service.listCalls)

        nowMs += RecapManager.LIST_CACHE_MS
        manager.list()
        assertEquals(3, service.listCalls)
    }

    @Test
    fun `feature disabled and not found are mapped`() = runTest {
        service.listResponse = errorResponse(404, "FEATURE_DISABLED")
        assertEquals(RecapResult.Disabled, manager.list())

        service.detailResponse = errorResponse(404, "RECAP_NOT_FOUND")
        assertEquals(RecapResult.NotFound, manager.detail("2026-08"))
    }

    @Test
    fun `server errors and offline fail without caching`() = runTest {
        service.listResponse = errorResponse(500, null)
        assertEquals(RecapResult.Failed, manager.list())
        service.listResponse = null
        assertEquals(RecapResult.Failed, manager.list())

        service.listResponse = Response.success(recaps)
        assertEquals(RecapResult.Ok(recaps), manager.list())
        assertEquals(3, service.listCalls)
    }

    @Test
    fun `detail parses the cards`() = runTest {
        service.detailResponse = Response.success(Gson().fromJson(RecapSamples.DETAIL, RecapDetailDTO::class.java))

        val result = manager.detail("2026-09") as RecapResult.Ok

        assertEquals(9, result.value.cards.size)
    }

    @Test
    fun `save playlist distinguishes created, existing and empty`() = runTest {
        val dto = RecapPlaylistDTO("p1", "Tu septiembre 2026", 50)
        service.playlistResponse = Response.success(201, dto)
        assertEquals(RecapPlaylistResult.Saved(dto, created = true), manager.savePlaylist("2026-09"))

        service.playlistResponse = Response.success(200, dto)
        assertEquals(RecapPlaylistResult.Saved(dto, created = false), manager.savePlaylist("2026-09"))

        service.playlistResponse = errorResponse(409, "RECAP_PLAYLIST_EMPTY")
        assertEquals(RecapPlaylistResult.Empty, manager.savePlaylist("2026-09"))

        service.playlistResponse = errorResponse(404, "FEATURE_DISABLED")
        assertEquals(RecapPlaylistResult.Disabled, manager.savePlaylist("2026-09"))

        service.playlistResponse = null
        assertEquals(RecapPlaylistResult.Failed, manager.savePlaylist("2026-09"))
    }

    @Test
    fun `mark seen invalidates the list`() = runTest {
        service.listResponse = Response.success(recaps)
        manager.list()
        service.seenResponse = Response.success(204, Unit)

        assertTrue(manager.markSeen("2026-09"))
        manager.list()

        assertEquals(listOf("2026-09"), service.seen)
        assertEquals(2, service.listCalls)
    }

    @Test
    fun `failed seen keeps the cache`() = runTest {
        service.listResponse = Response.success(recaps)
        manager.list()

        assertFalse(manager.markSeen("2026-09"))
        manager.list()

        assertEquals(1, service.listCalls)
    }

    @Test
    fun `dismissals are per period and per user`() {
        manager.dismiss("2026-09")
        assertEquals(setOf("2026-09"), manager.dismissedPeriods())

        user = "u2"
        assertTrue(manager.dismissedPeriods().isEmpty())

        user = null
        manager.dismiss("2026-10")
        assertTrue(store.stored.values.none { "2026-10" in it })
    }
}
