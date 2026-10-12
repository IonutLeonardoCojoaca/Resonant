package com.example.resonant.unisound

/** Drift numbers for one reporting window, used to tune [UnisoundDriftPolicy] with real use. */
data class UnisoundDriftReport(
    val p50Ms: Long,
    val maxMs: Long,
    val seeks: Int,
    val samples: Int
)

/** Collects absolute drift samples and hands back a report once per [windowMs]. */
class UnisoundDriftStats(private val windowMs: Long = 60_000L) {
    private val samples = mutableListOf<Long>()
    private var seeks = 0
    private var windowStartMs: Long? = null

    fun record(absDriftMs: Long, seeked: Boolean, nowMs: Long): UnisoundDriftReport? {
        val start = windowStartMs ?: nowMs.also { windowStartMs = it }
        samples += absDriftMs
        if (seeked) seeks++
        if (nowMs - start < windowMs) return null
        val sorted = samples.sorted()
        val report = UnisoundDriftReport(
            p50Ms = sorted[sorted.size / 2],
            maxMs = sorted.last(),
            seeks = seeks,
            samples = sorted.size
        )
        reset()
        return report
    }

    fun reset() {
        samples.clear()
        seeks = 0
        windowStartMs = null
    }
}
