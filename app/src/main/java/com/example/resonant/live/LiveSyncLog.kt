package com.example.resonant.live

/**
 * Debug-only drift log of a Live broadcast (`adb logcat -s LiveSync`), used to check that two phones
 * stay within ~300 ms. Pure: at most one line every [intervalMs]; no user data, never sent anywhere.
 */
class LiveSyncLog(private val intervalMs: Long = DEFAULT_INTERVAL_MS) {
    private var lastAtMs: Long? = null

    /** The line to write now, or null if one was written less than [intervalMs] ago. */
    fun lineOrNull(slug: String, blockId: String, positionMs: Long, driftMs: Long, nowMs: Long): String? {
        val last = lastAtMs
        if (last != null && nowMs - last < intervalMs) return null
        lastAtMs = nowMs
        return "slug=$slug block=$blockId pos=$positionMs driftMs=$driftMs"
    }

    fun reset() {
        lastAtMs = null
    }

    companion object {
        const val TAG = "LiveSync"
        const val DEFAULT_INTERVAL_MS = 10_000L
    }
}
