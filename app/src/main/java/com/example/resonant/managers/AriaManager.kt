package com.example.resonant.managers

import android.content.Context
import android.util.Log
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.AriaFeedbackRequest
import com.example.resonant.data.network.AriaFeedbackStats

object AriaManager {

    suspend fun submitFeedback(
        context: Context,
        logId: String,
        rating: Int,
        comment: String? = null
    ): Boolean {
        return try {
            val service = ApiClient.getAriaService(context)
            val response = service.submitFeedback(
                AriaFeedbackRequest(
                    logId = logId,
                    rating = rating,
                    comment = comment
                )
            )
            response.status == "ok"
        } catch (e: Exception) {
            Log.e("AriaManager", "Error enviando feedback", e)
            false
        }
    }

    suspend fun getFeedbackStats(
        context: Context,
        days: Int = 30
    ): AriaFeedbackStats? {
        return try {
            ApiClient.getAriaService(context).getFeedbackStats(days)
        } catch (e: Exception) {
            Log.e("AriaManager", "Error obteniendo stats", e)
            null
        }
    }
}
