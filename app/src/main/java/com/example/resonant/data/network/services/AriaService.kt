package com.example.resonant.data.network.services

import com.example.resonant.data.network.AriaFeedbackRequest
import com.example.resonant.data.network.AriaFeedbackResponse
import com.example.resonant.data.network.AriaFeedbackStats
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface AriaService {

    @POST("api/aria/feedback")
    suspend fun submitFeedback(
        @Body body: AriaFeedbackRequest
    ): AriaFeedbackResponse

    @GET("api/aria/feedback/stats")
    suspend fun getFeedbackStats(
        @Query("days") days: Int = 30
    ): AriaFeedbackStats
}
