package com.example.resonant.managers

import com.example.resonant.data.network.AriaPickFeedbackRequest
import com.example.resonant.data.network.services.AriaPicksService
import com.example.resonant.unisound.errorResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class AriaPicksManagerTest {

    private class FakeAriaPicksService : AriaPicksService {
        var response: Response<Unit>? = null
        val sent = mutableListOf<Pair<String, AriaPickFeedbackRequest>>()

        override suspend fun sendFeedback(songId: String, request: AriaPickFeedbackRequest): Response<Unit> {
            sent += songId to request
            return response ?: throw IOException("offline")
        }
    }

    private val service = FakeAriaPicksService()
    private val manager = AriaPicksManager(service)

    @Test
    fun `204 is sent`() = runTest {
        service.response = Response.success(204, Unit)

        assertEquals(AriaPickFeedbackResult.Sent, manager.sendFeedback("s1", liked = true))
    }

    @Test
    fun `pick not found and feature disabled are gone`() = runTest {
        service.response = errorResponse(404, "ARIA_PICK_NOT_FOUND")
        assertEquals(AriaPickFeedbackResult.Gone, manager.sendFeedback("s1", liked = false))

        service.response = errorResponse(404, "FEATURE_DISABLED")
        assertEquals(AriaPickFeedbackResult.Gone, manager.sendFeedback("s1", liked = false))
    }

    @Test
    fun `rate limit, server error and network failure are failed`() = runTest {
        service.response = errorResponse(429, "RATE_LIMITED")
        assertEquals(AriaPickFeedbackResult.Failed, manager.sendFeedback("s1", liked = true))

        service.response = errorResponse(500, null)
        assertEquals(AriaPickFeedbackResult.Failed, manager.sendFeedback("s1", liked = true))

        service.response = null
        assertEquals(AriaPickFeedbackResult.Failed, manager.sendFeedback("s1", liked = true))
    }

    @Test
    fun `404 without a known code is failed`() = runTest {
        service.response = errorResponse(404, null)

        assertEquals(AriaPickFeedbackResult.Failed, manager.sendFeedback("s1", liked = true))
    }

    @Test
    fun `body carries liked or dismissed for the song`() = runTest {
        service.response = Response.success(204, Unit)

        manager.sendFeedback("s1", liked = true)
        manager.sendFeedback("s2", liked = false)

        assertEquals("s1" to AriaPickFeedbackRequest("liked"), service.sent[0])
        assertEquals("s2" to AriaPickFeedbackRequest("dismissed"), service.sent[1])
    }
}
