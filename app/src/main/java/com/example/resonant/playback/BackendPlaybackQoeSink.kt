package com.example.resonant.playback

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Data
import com.example.resonant.data.network.NetworkTypeDetector
import com.example.resonant.data.network.PlaybackQoeEventDTO
import com.example.resonant.data.network.V2EndpointAvailability
import com.example.resonant.managers.SessionIdManager
import com.example.resonant.managers.UserManager
import com.example.resonant.workers.PlaybackQoeUploadWorker
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

data class PlaybackQoeContext(
    val streamId: String,
    val deliveryMode: String
)

/**
 * Persists the HTTP upload in WorkManager. Firebase remains active through the
 * composite sink, while the server receives idempotent events in the background.
 */
class BackendPlaybackQoeSink(
    context: Context,
    private val contextProvider: (mediaId: String?) -> PlaybackQoeContext?
) : PlaybackQoeSink {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)

    override fun onStartup(source: String, startupTimeMs: Long) = Unit

    override fun onSummary(metrics: PlaybackQoeMetrics) {
        if (!V2EndpointAvailability.shouldTry(
                appContext,
                V2EndpointAvailability.FEATURE_QOE
            )
        ) {
            return
        }
        val playbackContext = contextProvider(metrics.mediaId) ?: return
        if (playbackContext.streamId.length < 8) return
        val ownerUserId = UserManager(appContext).getUserId()
            ?.takeIf(String::isNotBlank)
            ?: return

        val event = PlaybackQoeEventDTO(
            eventId = UUID.randomUUID().toString(),
            sessionId = SessionIdManager.getOrCreateSessionId(appContext),
            streamId = playbackContext.streamId,
            source = metrics.source.toContractSource(),
            deliveryMode = playbackContext.deliveryMode.toContractDeliveryMode(),
            startupTimeMs = (metrics.startupTimeMs ?: 0L).coerceIn(0L, 300_000L),
            rebufferCount = metrics.rebufferCount.coerceIn(0, 10_000),
            rebufferDurationMs = metrics.rebufferDurationMs.coerceIn(0L, 86_400_000L),
            averageBitrate = (metrics.estimatedBitrateKbps ?: 0L)
                .times(1_000L)
                .coerceIn(0L, 2_000_000L),
            networkType = NetworkTypeDetector.current(appContext),
            fatalErrorCode = metrics.fatalErrorCode?.take(96),
            occurredAtUtc = Instant.now().toString()
        )

        val request = OneTimeWorkRequestBuilder<PlaybackQoeUploadWorker>()
            .setInputData(event.toWorkData(ownerUserId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(
            PlaybackQoeUploadWorker.uniqueWorkName(event.eventId),
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun PlaybackQoeEventDTO.toWorkData(ownerUserId: String): Data {
        val builder = Data.Builder()
            .putString(PlaybackQoeUploadWorker.KEY_EVENT_ID, eventId)
            .putString(PlaybackQoeUploadWorker.KEY_OWNER_USER_ID, ownerUserId)
            .putString(PlaybackQoeUploadWorker.KEY_SESSION_ID, sessionId)
            .putString(PlaybackQoeUploadWorker.KEY_STREAM_ID, streamId)
            .putString(PlaybackQoeUploadWorker.KEY_SOURCE, source)
            .putString(PlaybackQoeUploadWorker.KEY_DELIVERY_MODE, deliveryMode)
            .putLong(PlaybackQoeUploadWorker.KEY_STARTUP_TIME_MS, startupTimeMs)
            .putInt(PlaybackQoeUploadWorker.KEY_REBUFFER_COUNT, rebufferCount)
            .putLong(
                PlaybackQoeUploadWorker.KEY_REBUFFER_DURATION_MS,
                rebufferDurationMs
            )
            .putLong(PlaybackQoeUploadWorker.KEY_AVERAGE_BITRATE, averageBitrate)
            .putString(PlaybackQoeUploadWorker.KEY_NETWORK_TYPE, networkType)
            .putString(PlaybackQoeUploadWorker.KEY_OCCURRED_AT_UTC, occurredAtUtc)
        fatalErrorCode?.let {
            builder.putString(PlaybackQoeUploadWorker.KEY_FATAL_ERROR_CODE, it)
        }
        return builder.build()
    }

    private fun String.toContractSource(): String {
        return when (lowercase()) {
            "album" -> "album"
            "playlist", "playmix" -> "playlist"
            "search" -> "search"
            "download", "downloaded_songs" -> "download"
            else -> "auto"
        }
    }

    private fun String.toContractDeliveryMode(): String {
        return when (lowercase()) {
            "hls" -> "hls"
            "offline" -> "offline"
            else -> "progressive"
        }
    }
}
