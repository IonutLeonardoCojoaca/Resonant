package com.example.resonant.playback

import kotlin.math.abs

/**
 * "Imán" a la rejilla de beats del editor de Playmix (Fase 1 de transiciones profesionales).
 *
 * Lógica pura: dado un instante y una rejilla ordenada de beats en ms, devuelve el beat al que
 * debe engancharse el marcador. El umbral de captura es una fracción del intervalo local entre
 * beats, así funciona igual a 70 que a 170 BPM. Fuera del umbral (o fuera de la rejilla) el
 * instante se devuelve tal cual, para que el dedo nunca se quede "pegado".
 *
 * Cuando existan downbeats y frases (Fase 4) se añadirán niveles con prioridad sobre el beat.
 */
object BeatMagnet {

    /** Fracción del intervalo local entre beats dentro de la cual el marcador se engancha. */
    const val CAPTURE_FRACTION = 0.35f

    /** Umbral de captura cuando no se puede medir el intervalo local (rejilla de un solo beat). */
    const val FALLBACK_CAPTURE_MS = 150

    /** Resultado de [snap]: el instante final y si se ha enganchado a un beat. */
    data class Snap(val ms: Int, val snapped: Boolean)

    /** Índice del beat más cercano a [ms] en una rejilla ordenada, o -1 si está vacía. */
    fun nearestIndex(ms: Int, grid: List<Int>): Int {
        if (grid.isEmpty()) return -1
        var lo = 0
        var hi = grid.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (grid[mid] < ms) lo = mid + 1 else hi = mid
        }
        // lo es el primer beat >= ms; comparar con el anterior.
        return if (lo > 0 && abs(grid[lo - 1] - ms) <= abs(grid[lo] - ms)) lo - 1 else lo
    }

    /** Beat más cercano a [ms], o [ms] si la rejilla está vacía. */
    fun nearestBeat(ms: Int, grid: List<Int>): Int {
        val index = nearestIndex(ms, grid)
        return if (index < 0) ms else grid[index]
    }

    /**
     * Intervalo local entre beats alrededor del índice [index]: el menor de los dos
     * intervalos vecinos, para no capturar de más donde la rejilla se acelera.
     * Devuelve 0 si no se puede medir.
     */
    fun localIntervalMs(index: Int, grid: List<Int>): Int {
        if (index < 0 || grid.size < 2) return 0
        val before = if (index > 0) grid[index] - grid[index - 1] else Int.MAX_VALUE
        val after = if (index + 1 < grid.size) grid[index + 1] - grid[index] else Int.MAX_VALUE
        val local = minOf(before, after)
        return if (local == Int.MAX_VALUE || local <= 0) 0 else local
    }

    /** Umbral de captura en ms para el beat en [index]. */
    fun captureThresholdMs(index: Int, grid: List<Int>): Int {
        val interval = localIntervalMs(index, grid)
        return if (interval > 0) (interval * CAPTURE_FRACTION).toInt().coerceAtLeast(1) else FALLBACK_CAPTURE_MS
    }

    /**
     * Engancha [ms] al beat más cercano si está dentro del umbral de captura local.
     * Pensado para llamarse en cada evento de arrastre: la posición "real" del dedo la conserva
     * el llamante y aquí solo se decide qué se muestra y qué se guarda.
     */
    fun snap(ms: Int, grid: List<Int>): Snap {
        val index = nearestIndex(ms, grid)
        if (index < 0) return Snap(ms, false)
        val beat = grid[index]
        return if (abs(beat - ms) <= captureThresholdMs(index, grid)) Snap(beat, true) else Snap(ms, false)
    }
}
