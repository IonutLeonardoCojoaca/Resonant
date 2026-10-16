package com.example.resonant.data.network.services

import com.example.resonant.data.network.NotificationPageDTO
import com.example.resonant.data.network.NotificationReadAllRequest
import com.example.resonant.data.network.NotificationReadAllResultDTO
import com.example.resonant.data.network.NotificationSettingsDTO
import com.example.resonant.data.network.NotificationUnreadCountDTO
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Every route answers `404 FEATURE_DISABLED` while `PlatformFeatures__NotificationsEnabled` is off. */
interface NotificationService {
    /** Null [cursor] or [limit] are left out of the query (server default: 30, range 1-50). */
    @GET("api/notifications")
    suspend fun getNotifications(
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int?
    ): Response<NotificationPageDTO>

    @GET("api/notifications/unread-count")
    suspend fun getUnreadCount(): Response<NotificationUnreadCountDTO>

    /** 204, idempotent; 404 `NOTIFICATION_NOT_FOUND` for someone else's notification. */
    @POST("api/notifications/{id}/read")
    suspend fun markRead(@Path("id") id: String): Response<Unit>

    @POST("api/notifications/read-all")
    suspend fun markAllRead(@Body request: NotificationReadAllRequest): Response<NotificationReadAllResultDTO>

    @GET("api/notifications/settings")
    suspend fun getSettings(): Response<NotificationSettingsDTO>

    /** Partial update; 400 `NOTIFICATIONS_INVALID_CATEGORY` for an unknown category. */
    @PUT("api/notifications/settings")
    suspend fun putSettings(@Body request: NotificationSettingsDTO): Response<NotificationSettingsDTO>
}
