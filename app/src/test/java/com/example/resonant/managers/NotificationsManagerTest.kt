package com.example.resonant.managers

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.resonant.data.network.NotificationItemDTO
import com.example.resonant.data.network.NotificationPageDTO
import com.example.resonant.data.network.NotificationReadAllRequest
import com.example.resonant.data.network.NotificationReadAllResultDTO
import com.example.resonant.data.network.NotificationSettingsDTO
import com.example.resonant.data.network.NotificationUnreadCountDTO
import com.example.resonant.data.network.services.NotificationService
import com.example.resonant.unisound.errorResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class NotificationsManagerTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    /** Hand-written fake: each call returns the configured response or throws [IOException]. */
    private class FakeNotificationService : NotificationService {
        var pageResponse: Response<NotificationPageDTO>? = null
        var unreadResponse: Response<NotificationUnreadCountDTO>? = null
        var readResponse: Response<Unit>? = null
        var readAllResponse: Response<NotificationReadAllResultDTO>? = null
        var settingsResponse: Response<NotificationSettingsDTO>? = null
        var putSettingsResponse: Response<NotificationSettingsDTO>? = null

        var calls = 0
        val pageRequests = mutableListOf<Pair<String?, Int?>>()
        val readIds = mutableListOf<String>()
        val readAllRequests = mutableListOf<NotificationReadAllRequest>()
        val putRequests = mutableListOf<NotificationSettingsDTO>()

        override suspend fun getNotifications(cursor: String?, limit: Int?): Response<NotificationPageDTO> {
            calls++
            pageRequests += cursor to limit
            return pageResponse ?: throw IOException("offline")
        }

        override suspend fun getUnreadCount(): Response<NotificationUnreadCountDTO> {
            calls++
            return unreadResponse ?: throw IOException("offline")
        }

        override suspend fun markRead(id: String): Response<Unit> {
            calls++
            readIds += id
            return readResponse ?: throw IOException("offline")
        }

        override suspend fun markAllRead(request: NotificationReadAllRequest): Response<NotificationReadAllResultDTO> {
            calls++
            readAllRequests += request
            return readAllResponse ?: throw IOException("offline")
        }

        override suspend fun getSettings(): Response<NotificationSettingsDTO> {
            calls++
            return settingsResponse ?: throw IOException("offline")
        }

        override suspend fun putSettings(request: NotificationSettingsDTO): Response<NotificationSettingsDTO> {
            calls++
            putRequests += request
            return putSettingsResponse ?: throw IOException("offline")
        }
    }

    private val service = FakeNotificationService()
    private var featureEnabled = true
    private var capabilityRefreshes = 0
    private val manager = NotificationsManager(
        service = service,
        isFeatureEnabled = { featureEnabled },
        onFeatureDisabled = { capabilityRefreshes++ }
    )

    @Test
    fun `capability off makes no calls`() = runTest {
        featureEnabled = false

        assertEquals(NotificationsResult.Disabled, manager.refreshUnreadCount())
        assertEquals(NotificationsResult.Disabled, manager.getPage(cursor = null))
        assertEquals(NotificationsResult.Disabled, manager.getSettings())
        assertEquals(NotificationsResult.Disabled, manager.markRead("n1", wasUnread = true))

        assertEquals(0, service.calls)
        assertEquals(0, capabilityRefreshes)
    }

    @Test
    fun `unread count updates the live data`() = runTest {
        service.unreadResponse = Response.success(NotificationUnreadCountDTO(unread = 3))

        assertEquals(NotificationsResult.Ok(3), manager.refreshUnreadCount())
        assertEquals(3, manager.unreadCount.value)
        assertEquals(true, manager.featureAvailable.value)
    }

    @Test
    fun `feature disabled hides the bell and refreshes capabilities`() = runTest {
        service.unreadResponse = Response.success(NotificationUnreadCountDTO(unread = 2))
        manager.refreshUnreadCount()

        service.unreadResponse = errorResponse(404, "FEATURE_DISABLED")

        assertEquals(NotificationsResult.Disabled, manager.refreshUnreadCount())
        assertEquals(false, manager.featureAvailable.value)
        assertEquals(0, manager.unreadCount.value)
        assertEquals(1, capabilityRefreshes)
    }

    @Test
    fun `a later success makes the feature available again`() = runTest {
        service.unreadResponse = errorResponse(404, "FEATURE_DISABLED")
        manager.refreshUnreadCount()

        service.unreadResponse = Response.success(NotificationUnreadCountDTO(unread = 1))
        manager.refreshUnreadCount()

        assertEquals(true, manager.featureAvailable.value)
        assertEquals(1, manager.unreadCount.value)
    }

    @Test
    fun `network and server errors keep the last count silently`() = runTest {
        service.unreadResponse = Response.success(NotificationUnreadCountDTO(unread = 2))
        manager.refreshUnreadCount()

        service.unreadResponse = null
        assertEquals(NotificationsResult.Failed, manager.refreshUnreadCount())
        service.unreadResponse = errorResponse(500, null)
        assertEquals(NotificationsResult.Failed, manager.refreshUnreadCount())
        service.unreadResponse = errorResponse(429, "RATE_LIMITED")
        assertEquals(NotificationsResult.Failed, manager.refreshUnreadCount())

        assertEquals(2, manager.unreadCount.value)
        assertEquals(true, manager.featureAvailable.value)
        assertEquals(0, capabilityRefreshes)
    }

    @Test
    fun `a 404 that is not FEATURE_DISABLED is just a failure`() = runTest {
        service.readResponse = errorResponse(404, "NOTIFICATION_NOT_FOUND")

        assertEquals(NotificationsResult.Failed, manager.markRead("n1", wasUnread = false))
        assertEquals(true, manager.featureAvailable.value)
        assertEquals(0, capabilityRefreshes)
    }

    @Test
    fun `page passes cursor and limit`() = runTest {
        val item = NotificationItemDTO(id = "n1", category = "live", title = "Especial", createdAtUtcMs = 1L)
        service.pageResponse = Response.success(NotificationPageDTO(items = listOf(item), nextCursor = "c2"))

        val result = manager.getPage(cursor = "c1", limit = 20)

        assertEquals(NotificationsResult.Ok(NotificationPageDTO(listOf(item), "c2")), result)
        assertEquals(listOf<Pair<String?, Int?>>("c1" to 20), service.pageRequests)
    }

    @Test
    fun `mark read lowers the counter without waiting`() = runTest {
        service.unreadResponse = Response.success(NotificationUnreadCountDTO(unread = 2))
        manager.refreshUnreadCount()
        service.readResponse = null // offline: the counter still drops

        assertEquals(NotificationsResult.Failed, manager.markRead("n1", wasUnread = true))

        assertEquals(1, manager.unreadCount.value)
        assertEquals(listOf("n1"), service.readIds)
    }

    @Test
    fun `mark all read sends before and refreshes the counter`() = runTest {
        service.readAllResponse = Response.success(NotificationReadAllResultDTO(updated = 2))
        service.unreadResponse = Response.success(NotificationUnreadCountDTO(unread = 0))

        assertEquals(NotificationsResult.Ok(2), manager.markAllRead(beforeUtcMs = 1_791_640_630_250L))

        assertEquals(listOf(NotificationReadAllRequest(1_791_640_630_250L)), service.readAllRequests)
        assertEquals(0, manager.unreadCount.value)
    }

    @Test
    fun `settings put sends only the changed category`() = runTest {
        val all = mapOf("aria" to true, "live" to false, "streak" to true)
        service.putSettingsResponse = Response.success(NotificationSettingsDTO(all))

        val result = manager.setCategoryEnabled("live", false)

        assertEquals(NotificationsResult.Ok(all), result)
        assertEquals(listOf(NotificationSettingsDTO(mapOf("live" to false))), service.putRequests)
    }

    @Test
    fun `settings put failure is reported`() = runTest {
        service.putSettingsResponse = errorResponse(400, "NOTIFICATIONS_INVALID_CATEGORY")

        assertTrue(manager.setCategoryEnabled("live", false) is NotificationsResult.Failed)
    }

    @Test
    fun `settings without categories are an empty map`() = runTest {
        service.settingsResponse = Response.success(NotificationSettingsDTO(categories = null))

        assertEquals(NotificationsResult.Ok(emptyMap<String, Boolean>()), manager.getSettings())
    }
}
