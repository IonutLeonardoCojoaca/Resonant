package com.example.resonant.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackQoeAccumulatorTest {

    @Test
    fun `startup latency excludes no events and records first audio once`() {
        val accumulator = PlaybackQoeAccumulator()
        accumulator.begin("HOME", 1_000)
        accumulator.onBuffering(1_050)

        assertEquals(400L, accumulator.onPlaying(1_400))
        assertNull(accumulator.onPlaying(1_500))
        assertEquals(400L, accumulator.snapshot(1_500)?.startupTimeMs)
    }

    @Test
    fun `rebuffer duration and failures are accumulated`() {
        val accumulator = PlaybackQoeAccumulator()
        accumulator.begin("PLAYLIST", 0)
        accumulator.onPlaying(200)
        accumulator.onBuffering(1_000)
        accumulator.onLoadError()
        accumulator.onAudioUnderrun()
        accumulator.onPlaying(1_350)
        accumulator.onBandwidthEstimate(2_400_000)

        val metrics = accumulator.snapshot(2_000)!!

        assertEquals(1, metrics.rebufferCount)
        assertEquals(350L, metrics.rebufferDurationMs)
        assertEquals(1, metrics.loadErrorCount)
        assertEquals(1, metrics.audioUnderrunCount)
        assertEquals(2_400L, metrics.estimatedBitrateKbps)
    }
}
