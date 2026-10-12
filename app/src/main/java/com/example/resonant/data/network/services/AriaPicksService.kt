package com.example.resonant.data.network.services

import com.example.resonant.data.network.AriaPickFeedbackRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

interface AriaPicksService {
    /** 204 on success; 404 `ARIA_PICK_NOT_FOUND` or `FEATURE_DISABLED` when the pick no longer applies. */
    @POST("api/recommendations/aria/{songId}/feedback")
    suspend fun sendFeedback(
        @Path("songId") songId: String,
        @Body request: AriaPickFeedbackRequest
    ): Response<Unit>
}
