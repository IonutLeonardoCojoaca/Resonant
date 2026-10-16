package com.example.resonant.recap

import com.example.resonant.data.network.RecapSummaryDTO
import java.time.Instant

/** Decide si Explorar muestra el banner "Tu {mes} ya está aquí". Puro. */
object RecapBannerPolicy {
    const val MAX_AGE_MS = 31L * 24 * 60 * 60 * 1000

    /**
     * El resumen más reciente si no está visto, se generó hace [MAX_AGE_MS] o menos y no se ha descartado
     * en este dispositivo; si no, null.
     */
    fun bannerFor(recaps: List<RecapSummaryDTO>, nowMs: Long, dismissed: Set<String>): RecapSummaryDTO? {
        val latest = latest(recaps) ?: return null
        if (latest.seen || latest.periodKey in dismissed) return null
        val generatedMs = latest.generatedAt
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return null
        return latest.takeIf { nowMs - generatedMs <= MAX_AGE_MS }
    }

    /** El resumen más reciente con un `periodKey` válido (visto o no); null si no hay ninguno. */
    fun latest(recaps: List<RecapSummaryDTO>): RecapSummaryDTO? =
        recaps.filter { RecapTexts.parsePeriod(it.periodKey) != null }.maxByOrNull { it.periodKey }
}
