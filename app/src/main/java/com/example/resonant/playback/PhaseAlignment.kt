package com.example.resonant.playback

/**
 * Alineación de fase por compases para el beatmatch del modo inteligente (Fase 4,
 * `add-transition-cue-points` D6). Lógica pura.
 *
 * Con el BPM preciso y el primer downbeat de cada canción se conoce en qué punto de su compás está
 * cada una. La corrección es el desplazamiento mínimo de B (entre −medio y +medio compás) para que
 * B esté en el mismo punto de su compás que A, de modo que los downbeats suenen a la vez.
 */
object PhaseAlignment {

    data class Grid(val bpm: Double, val firstDownbeatMs: Int, val beatsPerBar: Int)

    fun barMs(grid: Grid): Double = 60_000.0 / grid.bpm * grid.beatsPerBar

    /** Rejilla utilizable o null si faltan datos o no tienen sentido. */
    fun gridOf(bpm: Double?, firstDownbeatMs: Int?, beatsPerBar: Int?): Grid? {
        if (bpm == null || bpm <= 0.0 || firstDownbeatMs == null || firstDownbeatMs < 0) return null
        val bpb = beatsPerBar?.takeIf { it in 1..16 } ?: 4
        return Grid(bpm, firstDownbeatMs, bpb)
    }

    /** Posición dentro del compás, en fracción [0, 1). */
    fun barPhase(positionMs: Long, grid: Grid): Double {
        val bar = barMs(grid)
        val raw = ((positionMs - grid.firstDownbeatMs) % bar) / bar
        return if (raw < 0) raw + 1.0 else raw
    }

    /** Milisegundos que hay que sumar a la posición de B para igualar su fase de compás con la de A. */
    fun entryCorrectionMs(positionAMs: Long, gridA: Grid, positionBMs: Long, gridB: Grid): Long {
        var delta = barPhase(positionAMs, gridA) - barPhase(positionBMs, gridB)
        if (delta >= 0.5) delta -= 1.0
        if (delta < -0.5) delta += 1.0
        return Math.round(delta * barMs(gridB))
    }
}
