package com.example.resonant.playback

import android.content.Context
import android.util.Log
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.ClientCompatibilityInterceptor
import com.example.resonant.data.network.NetworkTypeDetector
import com.example.resonant.data.network.PlaybackResolveRequestDTO
import com.example.resonant.data.network.V2EndpointAvailability
import com.example.resonant.data.network.services.SongService
import com.example.resonant.managers.SettingsManager
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.UUID
import retrofit2.HttpException

/**
 * Resolves one bounded queue window before Media3 chooses each MediaSource.
 * A failed/unsupported v2 request returns the original queue, so old servers
 * and progressive playback remain fully compatible.
 */
class PlaybackQueueEnricher(
    context: Context,
    private val songService: SongService,
    private val urlResolver: PlaybackUrlResolver,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    private val appContext = context.applicationContext
    private val settingsManager = SettingsManager(appContext)

    suspend fun enrich(songs: List<Song>, currentIndex: Int): List<Song> {
        if (songs.isEmpty() || currentIndex !in songs.indices) return songs
        if (!V2EndpointAvailability.shouldTry(
                appContext,
                V2EndpointAvailability.FEATURE_PLAYBACK_BATCH
            )
        ) {
            return songs
        }

        val selectedIds = boundedWindow(songs, currentIndex)
            .map(Song::id)
            .filter(String::isNotBlank)
            .filter { runCatching { UUID.fromString(it) }.isSuccess }
            .distinct()
        if (selectedIds.isEmpty()) return songs

        val response = try {
            withTimeout(timeoutMs) {
                songService.resolvePlayback(
                    request = PlaybackResolveRequestDTO(
                        songIds = selectedIds,
                        preferredQuality = settingsManager.streamingQualityFlow.first().apiValue,
                        networkType = NetworkTypeDetector.current(appContext)
                    ),
                    capabilities = ClientCompatibilityInterceptor.HLS_PLAYBACK_CAPABILITIES
                )
            }
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) {
                throw error
            }
            if (error is TimeoutCancellationException) {
                Log.w(TAG, "Playback v2 batch timed out; using progressive fallback")
            } else if (error is HttpException && error.code() in UNSUPPORTED_HTTP_CODES) {
                V2EndpointAvailability.markUnsupported(
                    appContext,
                    V2EndpointAvailability.FEATURE_PLAYBACK_BATCH
                )
                Log.d(TAG, "Playback v2 batch is not enabled; using progressive fallback")
            } else {
                Log.w(
                    TAG,
                    "Playback v2 batch unavailable; using progressive fallback: ${error.message}"
                )
            }
            return songs
        }
        V2EndpointAvailability.markAvailable(
            appContext,
            V2EndpointAvailability.FEATURE_PLAYBACK_BATCH
        )

        val resolvedById = response.items
            .asSequence()
            .filter { it.error == null }
            .associateBy { it.id }
        if (resolvedById.isEmpty()) return songs

        return songs.map { song ->
            val resolution = resolvedById[song.id] ?: return@map song
            song.withPlaybackQueueHints(resolution)
        }
    }

    internal fun boundedWindow(songs: List<Song>, currentIndex: Int): List<Song> {
        val beforeCount = minOf(PREVIOUS_WINDOW_SIZE, currentIndex)
        val start = currentIndex - beforeCount
        val endExclusive = minOf(songs.size, start + MAX_BATCH_SIZE)
        return songs.subList(start, endExclusive)
    }

    companion object {
        internal const val MAX_BATCH_SIZE = 50
        internal const val PREVIOUS_WINDOW_SIZE = 4
        private const val DEFAULT_TIMEOUT_MS = 2_000L
        private const val TAG = "PlaybackBatch"
        private val UNSUPPORTED_HTTP_CODES = setOf(404, 405, 501)
    }
}
