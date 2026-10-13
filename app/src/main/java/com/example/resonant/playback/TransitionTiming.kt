package com.example.resonant.playback

/**
 * Lógica pura de tiempos de una transición entre canciones. No depende de Android
 * ni de ExoPlayer para poder probarse con JUnit.
 *
 * Convenciones: todas las posiciones y duraciones están en milisegundos. Un valor
 * `<= 0` significa "desconocido" salvo que se indique lo contrario.
 */
object TransitionTiming {

    /** Fundido mínimo con sentido. Por debajo se espera al final de la saliente. */
    const val MIN_FADE_MS = 1_500L

    /** Tiempo máximo de espera a que la entrante esté lista una vez la saliente ha terminado. */
    const val HANDOFF_TIMEOUT_MS = 15_000L

    /** Punto de entrada máximo que acepta el modo inteligente. */
    const val MAX_INTELLIGENT_ENTRY_MS = 12_000

    /** Diferencia a partir de la cual se hace `seekTo` sobre la precarga. */
    const val PRELOAD_SEEK_TOLERANCE_MS = 250L

    /** Intervalo del tick de supervisión mientras se espera a la entrante. */
    const val SUPERVISION_TICK_MS = 100L

    enum class EntryMode { PLAYMIX, INTELLIGENT, SIMPLE }

    sealed class FadeDecision {
        /** Empezar el fundido ya, con esta duración (puede ser 0 para un corte). */
        data class Start(val durationMs: Long) : FadeDecision()

        /** No queda audio suficiente: dejar que la saliente termine y relevar al final. */
        object WaitForEnd : FadeDecision()
    }

    /**
     * Duración efectiva de la canción: la menor entre la real de ExoPlayer y la del
     * análisis, ignorando las desconocidas. Devuelve -1 si ninguna es válida.
     */
    fun effectiveDurationMs(exoDurationMs: Long, analysisDurationMs: Long): Long {
        val candidates = listOf(exoDurationMs, analysisDurationMs).filter { it > 0 }
        return candidates.minOrNull() ?: -1L
    }

    /**
     * Final del audio útil: `audioEndMs` si es válido y anterior a la duración
     * efectiva; si no, la propia duración efectiva. Devuelve -1 si no hay duración.
     */
    fun audioEndMs(exoDurationMs: Long, analysisDurationMs: Long, audioEndMs: Long): Long {
        val duration = effectiveDurationMs(exoDurationMs, analysisDurationMs)
        if (duration <= 0) return -1L
        return if (audioEndMs > 0 && audioEndMs < duration) audioEndMs else duration
    }

    /**
     * Posición en la que debe dispararse la transición: final del audio menos la
     * duración de la mezcla. Devuelve -1 si no se puede calcular o no es positiva.
     */
    fun computeTriggerPositionMs(
        exoDurationMs: Long,
        analysisDurationMs: Long,
        audioEndMs: Long,
        mixDurationMs: Long
    ): Long {
        if (mixDurationMs <= 0) return -1L
        val end = audioEndMs(exoDurationMs, analysisDurationMs, audioEndMs)
        if (end <= 0) return -1L
        val trigger = end - mixDurationMs
        return if (trigger > 0) trigger else -1L
    }

    /**
     * Decide si empezar el fundido con el audio que le queda a la saliente.
     *
     * - Un fundido deseado de 0 es un corte: se ejecuta siempre.
     * - Si queda al menos [MIN_FADE_MS], se funde durante `min(deseado, restante)`.
     * - Si no, se espera al final de la saliente.
     */
    fun decideFadeStart(remainingAMs: Long, desiredFadeMs: Long): FadeDecision {
        if (desiredFadeMs <= 0) return FadeDecision.Start(0)
        if (remainingAMs < MIN_FADE_MS) return FadeDecision.WaitForEnd
        return FadeDecision.Start(minOf(desiredFadeMs, remainingAMs))
    }

    /**
     * Progreso del fundido según la posición de la saliente, en [0, 1]. Un fundido
     * de duración 0 está siempre completo. Si la saliente retrocede o no avanza, el
     * progreso se congela en 0.
     */
    fun fadeProgress(positionAMs: Long, fadeStartPositionAMs: Long, fadeDurationMs: Long): Float {
        if (fadeDurationMs <= 0) return 1f
        val elapsed = positionAMs - fadeStartPositionAMs
        if (elapsed <= 0) return 0f
        return (elapsed.toFloat() / fadeDurationMs).coerceIn(0f, 1f)
    }

    /**
     * Plazo máximo total de la transición: lo que le queda a la saliente más el
     * tiempo de gracia para que la entrante esté lista.
     */
    fun transitionDeadlineMs(remainingAMs: Long): Long =
        remainingAMs.coerceAtLeast(0) + HANDOFF_TIMEOUT_MS

    /** Acota el punto de entrada del modo inteligente a `[0, MAX_INTELLIGENT_ENTRY_MS]`. */
    fun clampIntelligentEntry(rawMs: Int): Int = rawMs.coerceIn(0, MAX_INTELLIGENT_ENTRY_MS)

    /**
     * Punto de entrada de la canción entrante según el modo:
     * - Playmix: el punto del editor;
     * - inteligente: el punto óptimo, acotado;
     * - simple: el final del silencio inicial.
     * Los valores nulos o negativos se tratan como 0.
     */
    fun resolveEntryPointMs(
        mode: EntryMode,
        playmixEntryMs: Int?,
        intelligentInPointMs: Int?,
        audioStartMs: Int?
    ): Long {
        val raw = when (mode) {
            EntryMode.PLAYMIX -> playmixEntryMs ?: 0
            EntryMode.INTELLIGENT -> clampIntelligentEntry(intelligentInPointMs ?: 0)
            EntryMode.SIMPLE -> audioStartMs ?: 0
        }
        return raw.coerceAtLeast(0).toLong()
    }

    /** `true` si la precarga está lejos del punto de entrada deseado y hay que buscar. */
    fun needsSeek(currentPositionMs: Long, entryMs: Long): Boolean =
        kotlin.math.abs(currentPositionMs - entryMs) > PRELOAD_SEEK_TOLERANCE_MS
}
