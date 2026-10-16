package com.example.resonant.data.network.services

import com.example.resonant.data.network.RecapDetailDTO
import com.example.resonant.data.network.RecapPlaylistDTO
import com.example.resonant.data.network.RecapSummaryDTO
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/** Resumen mensual. Todas responden `404 FEATURE_DISABLED` con `features.monthlyRecap` apagado. */
interface RecapService {
    @GET("api/me/recaps")
    suspend fun list(): Response<List<RecapSummaryDTO>>

    /** 404 `RECAP_NOT_FOUND`, 400 `RECAP_INVALID_PERIOD`. */
    @GET("api/me/recaps/{periodKey}")
    suspend fun get(@Path("periodKey") periodKey: String): Response<RecapDetailDTO>

    /** 204; idempotente. */
    @POST("api/me/recaps/{periodKey}/seen")
    suspend fun markSeen(@Path("periodKey") periodKey: String): Response<Unit>

    /** 201 creada, 200 la misma de antes, 409 `RECAP_PLAYLIST_EMPTY`. */
    @POST("api/me/recaps/{periodKey}/playlist")
    suspend fun savePlaylist(@Path("periodKey") periodKey: String): Response<RecapPlaylistDTO>
}
