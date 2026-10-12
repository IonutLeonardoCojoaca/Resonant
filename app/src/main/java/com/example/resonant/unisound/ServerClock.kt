package com.example.resonant.unisound

/**
 * Estimates the offset between this device and the server clock from `SyncClock()` samples.
 * The sample with the shortest round trip wins: its midpoint is the least affected by
 * network asymmetry, which matters more than averaging for audio sync.
 */
class ServerClock(
    private val deviceNowMs: () -> Long = System::currentTimeMillis
) {
    data class Sample(
        val sentAtMs: Long,
        val serverTimeMs: Long,
        val receivedAtMs: Long
    ) {
        val roundTripMs: Long get() = receivedAtMs - sentAtMs
        val offsetMs: Long get() = serverTimeMs - (sentAtMs + receivedAtMs) / 2
    }

    @Volatile
    var offsetMs: Long = 0L
        private set

    @Volatile
    var roundTripMs: Long? = null
        private set

    val hasEstimate: Boolean get() = roundTripMs != null

    /** Applies the best sample; returns false when none of them is usable. */
    fun applySamples(samples: List<Sample>): Boolean {
        val best = samples.filter { it.roundTripMs >= 0 }.minByOrNull(Sample::roundTripMs)
            ?: return false
        offsetMs = best.offsetMs
        roundTripMs = best.roundTripMs
        return true
    }

    /** Rough offset from a snapshot's `serverTimeUtcMs`, used only until real samples exist. */
    fun seedFromServerTime(serverTimeMs: Long, receivedAtMs: Long = deviceNowMs()) {
        if (hasEstimate || serverTimeMs <= 0L) return
        offsetMs = serverTimeMs - receivedAtMs
    }

    fun reset() {
        offsetMs = 0L
        roundTripMs = null
    }

    fun now(): Long = deviceNowMs() + offsetMs

    /** Converts a server instant into this device's wall clock. */
    fun toDeviceTime(serverTimeMs: Long): Long = serverTimeMs - offsetMs
}
