package com.example.resonant.managers

import com.example.resonant.autodownload.FakeAutoDownloadService
import com.example.resonant.autodownload.autoDownloadError
import com.example.resonant.data.models.AutoDownloadPlanDTO
import com.example.resonant.data.models.AutoDownloadSubscriptionDTO
import com.example.resonant.data.models.AutoDownloadSubscriptionListDTO
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class AutoDownloadManagerTest {
    private val service = FakeAutoDownloadService()
    private val manager = AutoDownloadManager(service)

    @Test
    fun `subscription limit is mapped`() = runTest {
        service.subscribeResponse = autoDownloadError(409, "AUTO_DOWNLOAD_SUBSCRIPTION_LIMIT")

        assertEquals(AutoDownloadCall.SubscriptionLimit, manager.subscribe("a1"))
    }

    @Test
    fun `feature disabled is mapped`() = runTest {
        service.plansError = autoDownloadError(404, "FEATURE_DISABLED")

        assertEquals(AutoDownloadCall.Disabled, manager.getPlans())
    }

    @Test
    fun `other 404 is a plain failure`() = runTest {
        service.subscribeResponse = autoDownloadError(404, "ARTIST_NOT_FOUND")

        assertEquals(AutoDownloadCall.Failed(404, "ARTIST_NOT_FOUND"), manager.subscribe("a1"))
    }

    @Test
    fun `plan conflict keeps its code`() = runTest {
        service.pendingPlans += AutoDownloadPlanDTO(planId = "p1", songId = "s1")
        service.claimErrors["p1"] = autoDownloadError(409, "AUTO_DOWNLOAD_PLAN_CLAIMED")

        assertEquals(AutoDownloadCall.Failed(409, "AUTO_DOWNLOAD_PLAN_CLAIMED"), manager.claim("p1", "inst"))
    }

    @Test
    fun `network error is reported`() = runTest {
        service.subscriptionsResponse = null

        assertEquals(AutoDownloadCall.NetworkError, manager.getSubscriptions())
    }

    @Test
    fun `is subscribed looks up the list`() = runTest {
        service.subscriptionsResponse = Response.success(
            AutoDownloadSubscriptionListDTO(100, listOf(AutoDownloadSubscriptionDTO("A1", "X")))
        )

        assertEquals(AutoDownloadCall.Ok(true), manager.isSubscribed("a1"))
        assertEquals(AutoDownloadCall.Ok(false), manager.isSubscribed("b2"))
    }

    @Test
    fun `plans limit is clamped and incomplete plans are dropped`() = runTest {
        service.pendingPlans += AutoDownloadPlanDTO(planId = "p1")
        service.pendingPlans += AutoDownloadPlanDTO(planId = null)

        val page = manager.getPlans(limit = 500) as AutoDownloadCall.Ok

        assertEquals(listOf(50), service.requestedLimits)
        assertEquals(listOf("p1"), page.value.items.map { it.planId })
    }

    @Test
    fun `unsubscribe and result succeed without body`() = runTest {
        assertTrue(manager.unsubscribe("a1") is AutoDownloadCall.Ok)
        assertTrue(manager.reportResult("p1", "inst", "completed") is AutoDownloadCall.Ok)
        assertEquals(listOf("p1" to "completed"), service.results)
    }
}
