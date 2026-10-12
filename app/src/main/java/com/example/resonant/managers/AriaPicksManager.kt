package com.example.resonant.managers

import android.content.Context
import android.util.Log
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.ApiErrorParser
import com.example.resonant.data.network.AriaPickFeedbackRequest
import com.example.resonant.data.network.services.AriaPicksService
import kotlinx.coroutines.CancellationException

enum class AriaPickFeedbackResult {
    Sent,
    /** The pick no longer applies (`ARIA_PICK_NOT_FOUND`, `FEATURE_DISABLED`): drop it silently. */
    Gone,
    Failed
}

/** Feedback on Aria's daily picks (`POST api/recommendations/aria/{songId}/feedback`). */
class AriaPicksManager internal constructor(
    private val service: AriaPicksService,
    private val log: (String) -> Unit = {}
) {

    suspend fun sendFeedback(songId: String, liked: Boolean): AriaPickFeedbackResult {
        val request = AriaPickFeedbackRequest(if (liked) FEEDBACK_LIKED else FEEDBACK_DISMISSED)
        return try {
            val response = service.sendFeedback(songId, request)
            when {
                response.isSuccessful -> AriaPickFeedbackResult.Sent
                response.code() == 404 && ApiErrorParser.parse(response)?.code in GONE_CODES ->
                    AriaPickFeedbackResult.Gone
                else -> AriaPickFeedbackResult.Failed.also { log("feedback HTTP ${response.code()}") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("feedback failed: ${e.javaClass.simpleName}")
            AriaPickFeedbackResult.Failed
        }
    }

    companion object {
        private const val TAG = "AriaPicks"
        const val FEEDBACK_LIKED = "liked"
        const val FEEDBACK_DISMISSED = "dismissed"
        private val GONE_CODES = setOf("ARIA_PICK_NOT_FOUND", "FEATURE_DISABLED")

        @Volatile
        private var instance: AriaPicksManager? = null

        fun get(context: Context): AriaPicksManager =
            instance ?: synchronized(this) {
                instance ?: AriaPicksManager(
                    service = ApiClient.getAriaPicksService(context.applicationContext),
                    log = { Log.w(TAG, it) }
                ).also { instance = it }
            }
    }
}
