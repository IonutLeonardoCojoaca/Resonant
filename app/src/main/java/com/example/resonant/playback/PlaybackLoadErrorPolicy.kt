package com.example.resonant.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import kotlin.math.min

/**
 * Refreshes expired signatures without discarding the player timeline, and
 * applies a bounded backoff for transient network failures.
 */
@OptIn(UnstableApi::class)
class PlaybackLoadErrorPolicy(
    private val urlResolver: PlaybackUrlResolver
) : DefaultLoadErrorHandlingPolicy(MINIMUM_RETRY_COUNT) {

    override fun getRetryDelayMsFor(
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
    ): Long {
        val exception = loadErrorInfo.exception
        val errorCount = loadErrorInfo.errorCount

        if (exception is HttpDataSource.InvalidResponseCodeException &&
            exception.responseCode in SIGNATURE_REFRESH_HTTP_CODES
        ) {
            val dataSpec = loadErrorInfo.loadEventInfo.dataSpec
            urlResolver.invalidateCacheKey(dataSpec.key)
            return if (errorCount <= SIGNATURE_REFRESH_RETRIES) {
                0L
            } else {
                C.TIME_UNSET
            }
        }

        if (errorCount <= MINIMUM_RETRY_COUNT) {
            val exponentialDelay = BASE_RETRY_DELAY_MS * (1L shl (errorCount - 1))
            return min(exponentialDelay, MAX_RETRY_DELAY_MS)
        }

        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    companion object {
        private const val MINIMUM_RETRY_COUNT = 3
        private const val SIGNATURE_REFRESH_RETRIES = 2
        private const val BASE_RETRY_DELAY_MS = 500L
        private const val MAX_RETRY_DELAY_MS = 4_000L
        private val SIGNATURE_REFRESH_HTTP_CODES = setOf(401, 403)
    }
}
