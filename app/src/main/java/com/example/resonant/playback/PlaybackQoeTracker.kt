package com.example.resonant.playback

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.google.firebase.analytics.FirebaseAnalytics
import java.io.IOException

interface PlaybackQoeSink {
    fun onStartup(source: String, startupTimeMs: Long)
    fun onSummary(metrics: PlaybackQoeMetrics)
}

class CompositePlaybackQoeSink(
    private vararg val sinks: PlaybackQoeSink
) : PlaybackQoeSink {
    override fun onStartup(source: String, startupTimeMs: Long) {
        sinks.forEach { sink ->
            runCatching { sink.onStartup(source, startupTimeMs) }
                .onFailure { Log.w("PlaybackQoE", "QoE startup sink failed", it) }
        }
    }

    override fun onSummary(metrics: PlaybackQoeMetrics) {
        sinks.forEach { sink ->
            runCatching { sink.onSummary(metrics) }
                .onFailure { Log.w("PlaybackQoE", "QoE summary sink failed", it) }
        }
    }
}

class FirebasePlaybackQoeSink(context: Context) : PlaybackQoeSink {
    private val analytics = FirebaseAnalytics.getInstance(context.applicationContext)

    override fun onStartup(source: String, startupTimeMs: Long) {
        analytics.logEvent(EVENT_PLAYBACK_STARTUP, Bundle().apply {
            putString(PARAM_SOURCE, source)
            putLong(PARAM_STARTUP_MS, startupTimeMs)
        })
    }

    override fun onSummary(metrics: PlaybackQoeMetrics) {
        analytics.logEvent(EVENT_PLAYBACK_QOE, Bundle().apply {
            putString(PARAM_SOURCE, metrics.source)
            metrics.startupTimeMs?.let { putLong(PARAM_STARTUP_MS, it) }
            putLong(PARAM_REBUFFER_COUNT, metrics.rebufferCount.toLong())
            putLong(PARAM_REBUFFER_MS, metrics.rebufferDurationMs)
            putLong(PARAM_LOAD_ERRORS, metrics.loadErrorCount.toLong())
            putLong(PARAM_UNDERRUNS, metrics.audioUnderrunCount.toLong())
            metrics.estimatedBitrateKbps?.let { putLong(PARAM_BITRATE_KBPS, it) }
            metrics.fatalErrorCode?.let { putString(PARAM_ERROR_CODE, it) }
        })
    }

    companion object {
        private const val EVENT_PLAYBACK_STARTUP = "playback_startup"
        private const val EVENT_PLAYBACK_QOE = "playback_qoe"
        private const val PARAM_SOURCE = "source"
        private const val PARAM_STARTUP_MS = "startup_ms"
        private const val PARAM_REBUFFER_COUNT = "rebuffer_count"
        private const val PARAM_REBUFFER_MS = "rebuffer_ms"
        private const val PARAM_LOAD_ERRORS = "load_errors"
        private const val PARAM_UNDERRUNS = "audio_underruns"
        private const val PARAM_BITRATE_KBPS = "bitrate_kbps"
        private const val PARAM_ERROR_CODE = "error_code"
    }
}

@OptIn(UnstableApi::class)
class PlaybackQoeTracker(
    private val sink: PlaybackQoeSink,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime
) : AnalyticsListener {

    private val accumulator = PlaybackQoeAccumulator()
    private var source: String = "unknown"
    private var activeMediaId: String? = null
    private var hasActiveMeasurement = false

    fun markPlayRequested(source: String, mediaId: String? = null) {
        finishMeasurement()
        this.source = source.ifBlank { "unknown" }
        if (!mediaId.isNullOrBlank()) {
            activeMediaId = mediaId
        }
        accumulator.begin(this.source, elapsedRealtimeMs())
        hasActiveMeasurement = true
    }

    fun markPreparedPlayerHandoff(source: String, mediaId: String? = null) {
        markPlayRequested(source, mediaId)
        accumulator.onPlaying(elapsedRealtimeMs())?.let { startupMs ->
            sink.onStartup(this.source, startupMs)
        }
    }

    fun release() {
        finishMeasurement()
    }

    override fun onMediaItemTransition(
        eventTime: AnalyticsListener.EventTime,
        mediaItem: MediaItem?,
        reason: Int
    ) {
        val newMediaId = mediaItem?.mediaId
        if (newMediaId == activeMediaId) return

        if (activeMediaId != null) {
            finishMeasurement()
            accumulator.begin(source, elapsedRealtimeMs())
            hasActiveMeasurement = true
        }
        activeMediaId = newMediaId
    }

    override fun onPlaybackStateChanged(
        eventTime: AnalyticsListener.EventTime,
        state: Int
    ) {
        if (state == Player.STATE_BUFFERING && hasActiveMeasurement) {
            accumulator.onBuffering(elapsedRealtimeMs())
        }
    }

    override fun onIsPlayingChanged(
        eventTime: AnalyticsListener.EventTime,
        isPlaying: Boolean
    ) {
        if (!isPlaying || !hasActiveMeasurement) return
        accumulator.onPlaying(elapsedRealtimeMs())?.let { startupMs ->
            sink.onStartup(source, startupMs)
        }
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean
    ) {
        if (!wasCanceled && hasActiveMeasurement) {
            accumulator.onLoadError()
        }
    }

    override fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long
    ) {
        if (hasActiveMeasurement) {
            accumulator.onBandwidthEstimate(bitrateEstimate)
        }
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long
    ) {
        if (hasActiveMeasurement) {
            accumulator.onAudioUnderrun()
        }
    }

    override fun onPlayerError(
        eventTime: AnalyticsListener.EventTime,
        error: PlaybackException
    ) {
        if (!hasActiveMeasurement) return
        accumulator.onFatalError(error.errorCodeName)
        Log.w("PlaybackQoE", "Fatal playback error: ${error.errorCodeName}")
        finishMeasurement()
    }

    private fun finishMeasurement() {
        if (!hasActiveMeasurement) return
        accumulator.snapshot(elapsedRealtimeMs())
            ?.copy(mediaId = activeMediaId)
            ?.let(sink::onSummary)
        hasActiveMeasurement = false
    }
}
