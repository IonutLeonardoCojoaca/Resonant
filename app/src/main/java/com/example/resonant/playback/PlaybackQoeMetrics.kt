package com.example.resonant.playback

data class PlaybackQoeMetrics(
    val source: String,
    val startupTimeMs: Long?,
    val rebufferCount: Int,
    val rebufferDurationMs: Long,
    val loadErrorCount: Int,
    val audioUnderrunCount: Int,
    val estimatedBitrateKbps: Long?,
    val fatalErrorCode: String?,
    val mediaId: String? = null
)

/**
 * Pure state holder so QoE calculations can be covered by local unit tests.
 */
class PlaybackQoeAccumulator {
    private var source: String = "unknown"
    private var requestedAtMs: Long? = null
    private var firstAudioAtMs: Long? = null
    private var bufferingStartedAtMs: Long? = null
    private var rebufferCount: Int = 0
    private var rebufferDurationMs: Long = 0
    private var loadErrorCount: Int = 0
    private var audioUnderrunCount: Int = 0
    private var estimatedBitrateKbps: Long? = null
    private var fatalErrorCode: String? = null

    fun begin(source: String, nowMs: Long) {
        this.source = source.ifBlank { "unknown" }
        requestedAtMs = nowMs
        firstAudioAtMs = null
        bufferingStartedAtMs = null
        rebufferCount = 0
        rebufferDurationMs = 0
        loadErrorCount = 0
        audioUnderrunCount = 0
        estimatedBitrateKbps = null
        fatalErrorCode = null
    }

    fun onBuffering(nowMs: Long) {
        if (requestedAtMs == null || bufferingStartedAtMs != null) return
        bufferingStartedAtMs = nowMs
        if (firstAudioAtMs != null) {
            rebufferCount++
        }
    }

    /**
     * @return startup latency the first time audible playback starts.
     */
    fun onPlaying(nowMs: Long): Long? {
        val requestStart = requestedAtMs ?: return null
        bufferingStartedAtMs?.let { bufferingStart ->
            if (firstAudioAtMs != null) {
                rebufferDurationMs += (nowMs - bufferingStart).coerceAtLeast(0)
            }
        }
        bufferingStartedAtMs = null

        if (firstAudioAtMs == null) {
            firstAudioAtMs = nowMs
            return (nowMs - requestStart).coerceAtLeast(0)
        }
        return null
    }

    fun onLoadError() {
        loadErrorCount++
    }

    fun onAudioUnderrun() {
        audioUnderrunCount++
    }

    fun onBandwidthEstimate(bitsPerSecond: Long) {
        if (bitsPerSecond > 0) {
            estimatedBitrateKbps = bitsPerSecond / 1_000
        }
    }

    fun onFatalError(code: String) {
        fatalErrorCode = code
    }

    fun snapshot(nowMs: Long): PlaybackQoeMetrics? {
        val requestStart = requestedAtMs ?: return null
        val pendingRebufferMs = if (
            firstAudioAtMs != null &&
            bufferingStartedAtMs != null
        ) {
            (nowMs - bufferingStartedAtMs!!).coerceAtLeast(0)
        } else {
            0L
        }

        return PlaybackQoeMetrics(
            source = source,
            startupTimeMs = firstAudioAtMs?.let {
                (it - requestStart).coerceAtLeast(0)
            },
            rebufferCount = rebufferCount,
            rebufferDurationMs = rebufferDurationMs + pendingRebufferMs,
            loadErrorCount = loadErrorCount,
            audioUnderrunCount = audioUnderrunCount,
            estimatedBitrateKbps = estimatedBitrateKbps,
            fatalErrorCode = fatalErrorCode
        )
    }
}
