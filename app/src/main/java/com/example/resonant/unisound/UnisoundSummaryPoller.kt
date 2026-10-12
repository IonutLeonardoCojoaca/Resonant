package com.example.resonant.unisound

import com.example.resonant.data.network.UnisoundSessionSummaryDTO
import kotlinx.coroutines.delay

/**
 * Aria writes the summary line after the room ends. While it is `pending` the summary is asked
 * again at 5 s and 15 s; any failure just leaves the summary without the line.
 */
class UnisoundSummaryPoller(
    private val fetch: suspend () -> UnisoundSessionSummaryDTO?,
    private val delaysMs: List<Long> = listOf(5_000L, 10_000L)
) {
    suspend fun poll(initial: UnisoundSessionSummaryDTO, onUpdate: (UnisoundSessionSummaryDTO) -> Unit) {
        if (initial.ariaLineStatus != STATUS_PENDING) return
        for (wait in delaysMs) {
            delay(wait)
            val latest = runCatching { fetch() }.getOrNull() ?: continue
            if (latest.ariaLineStatus != STATUS_PENDING) {
                onUpdate(latest)
                return
            }
        }
    }

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_READY = "ready"
    }
}
