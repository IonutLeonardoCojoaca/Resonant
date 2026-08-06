package com.example.resonant.data.network.services

import com.example.resonant.data.network.PlaybackQoeEventDTO
import com.example.resonant.data.network.PlaybackQoeResponseDTO
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface PlaybackAnalyticsService {
    @POST("api/v2/analytics/playback-qoe")
    suspend fun submitPlaybackQoe(
        @Body event: PlaybackQoeEventDTO
    ): Response<PlaybackQoeResponseDTO>
}
