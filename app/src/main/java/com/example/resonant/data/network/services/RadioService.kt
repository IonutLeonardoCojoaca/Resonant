package com.example.resonant.data.network.services

import com.example.resonant.data.network.FlowPreferencesDTO
import com.example.resonant.data.network.FlowSavePlaylistRequestDTO
import com.example.resonant.data.network.FlowSavedPlaylistDTO
import com.example.resonant.data.network.RadioRequestDTO
import com.example.resonant.data.network.RadioResponseDTO
import com.example.resonant.data.network.RecommendationFeedbackRequestDTO
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface RadioService {
    /**
     * Fetches the next batch of tracks for Resonant Radio (autoplay when
     * the current queue ends).
     *
     * Returns [Response] rather than the body directly so callers can
     * inspect status codes: 204 means the server declined to seed
     * (e.g. offline-only context), which is not an error — the client
     * simply stops autoplay silently.
     */
    @POST("api/radio")
    suspend fun getRadio(
        @Body request: RadioRequestDTO
    ): Response<RadioResponseDTO>

    // ── Flow v2 (capacidad `flowV2`) ──────────────────────────────────────

    @GET("api/me/flow-preferences")
    suspend fun getFlowPreferences(): Response<FlowPreferencesDTO>

    @PUT("api/me/flow-preferences")
    suspend fun putFlowPreferences(@Body preferences: FlowPreferencesDTO): Response<Unit>

    @POST("api/recommendation-feedback")
    suspend fun sendRecommendationFeedback(
        @Body feedback: RecommendationFeedbackRequestDTO
    ): Response<Unit>

    @DELETE("api/recommendation-feedback/{targetType}/{targetId}")
    suspend fun removeRecommendationFeedback(
        @Path("targetType") targetType: String,
        @Path("targetId") targetId: String
    ): Response<Unit>

    @POST("api/radio/playlist")
    suspend fun saveFlowSession(
        @Body request: FlowSavePlaylistRequestDTO
    ): Response<FlowSavedPlaylistDTO>
}
