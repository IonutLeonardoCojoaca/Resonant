package com.example.resonant.data.network.services

import com.example.resonant.data.network.StreakSettingsRequest
import com.example.resonant.data.network.StreakStatusDTO
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT

interface StreakService {
    /** 404 `FEATURE_DISABLED` when streaks are off on the server. */
    @GET("api/me/streak")
    suspend fun getStreak(): Response<StreakStatusDTO>

    /** 204 on success; 400 `STREAK_INVALID_TIMEZONE`. */
    @PUT("api/me/streak/settings")
    suspend fun putSettings(@Body request: StreakSettingsRequest): Response<Unit>

    /** Raw body: `200` and `202` have different shapes and `204` has none. */
    @GET("api/me/song-of-the-day")
    suspend fun getSongOfDay(): Response<ResponseBody>
}
