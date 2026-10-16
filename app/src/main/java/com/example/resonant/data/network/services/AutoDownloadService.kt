package com.example.resonant.data.network.services

import com.example.resonant.data.models.AutoDownloadClaimRequest
import com.example.resonant.data.models.AutoDownloadClaimResponseDTO
import com.example.resonant.data.models.AutoDownloadPlanListDTO
import com.example.resonant.data.models.AutoDownloadResultRequest
import com.example.resonant.data.models.AutoDownloadSubscriptionDTO
import com.example.resonant.data.models.AutoDownloadSubscriptionListDTO
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Todas las rutas responden 404 `FEATURE_DISABLED` con el flag del servidor desactivado. */
interface AutoDownloadService {
    @GET("api/me/auto-download/artists")
    suspend fun getSubscriptions(): Response<AutoDownloadSubscriptionListDTO>

    /** 404 `ARTIST_NOT_FOUND`; 409 `AUTO_DOWNLOAD_SUBSCRIPTION_LIMIT`. */
    @PUT("api/me/auto-download/artists/{artistId}")
    suspend fun subscribe(@Path("artistId") artistId: String): Response<AutoDownloadSubscriptionDTO>

    /** 204 aunque no existiera la suscripción. */
    @DELETE("api/me/auto-download/artists/{artistId}")
    suspend fun unsubscribe(@Path("artistId") artistId: String): Response<Unit>

    @GET("api/me/auto-download/plans")
    suspend fun getPlans(
        @Query("status") status: String,
        @Query("limit") limit: Int
    ): Response<AutoDownloadPlanListDTO>

    /** 409 `AUTO_DOWNLOAD_PLAN_CLAIMED` / `AUTO_DOWNLOAD_PLAN_CLOSED`. */
    @POST("api/me/auto-download/plans/{planId}/claim")
    suspend fun claim(
        @Path("planId") planId: String,
        @Body request: AutoDownloadClaimRequest
    ): Response<AutoDownloadClaimResponseDTO>

    /** 204; idempotente. */
    @POST("api/me/auto-download/plans/{planId}/result")
    suspend fun reportResult(
        @Path("planId") planId: String,
        @Body request: AutoDownloadResultRequest
    ): Response<Unit>
}
