package com.example.resonant.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.PlaybackQoeEventDTO
import com.example.resonant.data.network.V2EndpointAvailability
import com.example.resonant.managers.UserManager
import kotlinx.coroutines.CancellationException

class PlaybackQoeUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ownerUserId = inputData.getString(KEY_OWNER_USER_ID) ?: return Result.failure()
        if (UserManager(applicationContext).getUserId() != ownerUserId) {
            return Result.failure()
        }
        val event = inputData.toEvent() ?: return Result.failure()
        return try {
            val response = ApiClient
                .getPlaybackAnalyticsService(applicationContext)
                .submitPlaybackQoe(event)
            when {
                response.isSuccessful -> {
                    V2EndpointAvailability.markAvailable(
                        applicationContext,
                        V2EndpointAvailability.FEATURE_QOE
                    )
                    Result.success()
                }
                response.code() in UNSUPPORTED_HTTP_CODES -> {
                    V2EndpointAvailability.markUnsupported(
                        applicationContext,
                        V2EndpointAvailability.FEATURE_QOE
                    )
                    Result.failure()
                }
                (response.code() == 429 || response.code() >= 500) &&
                    runAttemptCount < MAX_ATTEMPTS -> Result.retry()
                else -> Result.failure()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private fun androidx.work.Data.toEvent(): PlaybackQoeEventDTO? {
        val eventId = getString(KEY_EVENT_ID) ?: return null
        val sessionId = getString(KEY_SESSION_ID) ?: return null
        val streamId = getString(KEY_STREAM_ID) ?: return null
        val source = getString(KEY_SOURCE) ?: return null
        val deliveryMode = getString(KEY_DELIVERY_MODE) ?: return null
        val networkType = getString(KEY_NETWORK_TYPE) ?: return null
        val occurredAtUtc = getString(KEY_OCCURRED_AT_UTC) ?: return null
        return PlaybackQoeEventDTO(
            eventId = eventId,
            sessionId = sessionId,
            streamId = streamId,
            source = source,
            deliveryMode = deliveryMode,
            startupTimeMs = getLong(KEY_STARTUP_TIME_MS, 0L),
            rebufferCount = getInt(KEY_REBUFFER_COUNT, 0),
            rebufferDurationMs = getLong(KEY_REBUFFER_DURATION_MS, 0L),
            averageBitrate = getLong(KEY_AVERAGE_BITRATE, 0L),
            networkType = networkType,
            fatalErrorCode = getString(KEY_FATAL_ERROR_CODE),
            occurredAtUtc = occurredAtUtc
        )
    }

    companion object {
        const val KEY_EVENT_ID = "event_id"
        const val KEY_OWNER_USER_ID = "owner_user_id"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_STREAM_ID = "stream_id"
        const val KEY_SOURCE = "source"
        const val KEY_DELIVERY_MODE = "delivery_mode"
        const val KEY_STARTUP_TIME_MS = "startup_time_ms"
        const val KEY_REBUFFER_COUNT = "rebuffer_count"
        const val KEY_REBUFFER_DURATION_MS = "rebuffer_duration_ms"
        const val KEY_AVERAGE_BITRATE = "average_bitrate"
        const val KEY_NETWORK_TYPE = "network_type"
        const val KEY_FATAL_ERROR_CODE = "fatal_error_code"
        const val KEY_OCCURRED_AT_UTC = "occurred_at_utc"

        private const val MAX_ATTEMPTS = 5
        private const val WORK_PREFIX = "playback-qoe:"
        private val UNSUPPORTED_HTTP_CODES = setOf(404, 405, 501)

        fun uniqueWorkName(eventId: String) = WORK_PREFIX + eventId
    }
}
