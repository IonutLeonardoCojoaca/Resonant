package com.example.resonant.playback

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Rejilla de compases de una canción para el editor de transiciones. Lógica pura.
 *
 * Con BPM preciso y primer downbeat ajustado ([PhaseAlignment.Grid]) la rejilla es exacta: el compás k
 * está en `firstDownbeatMs + k · barMs`, sin el cuantizado de 20 ms de la rejilla en bruto ni la deriva
 * que acumula. Es la misma rejilla que usa la reproducción (beatSync y corrección de fase), así que lo
 * que se ve alineado en el editor suena alineado.
 *
 * Sin rejilla exacta se usan los downbeats en bruto del análisis y, si tampoco los hay, uno de cada
 * `beatsPerBar` beats (solo para dibujar: [hasRealDownbeats] es false y el editor no bloquea a ellos).
 *
 * Los instantes son ms del audio de la canción. Solo se devuelven instantes dentro de [0, duración].
 */
class BarGrid private constructor(
    /** Rejilla exacta, o null si se trabaja con la rejilla en bruto. */
    val exact: PhaseAlignment.Grid?,
    private val rawBeats: List<Int>,
    private val rawDownbeats: List<Int>,
    /** true si los downbeats vienen del análisis (exactos o en bruto); false si se deducen de los beats. */
    val hasRealDownbeats: Boolean,
    /** Inicios de frase; con rejilla exacta, enganchados a su downbeat exacto. */
    val phraseStarts: List<Int>,
    val beatsPerBar: Int,
    private val durationMs: Int
) {

    val isExact: Boolean get() = exact != null

    /** Hay compases que dibujar (reales o deducidos de los beats). */
    val hasBars: Boolean get() = exact != null || rawDownbeats.isNotEmpty()

    // ── Duraciones ───────────────────────────────────────────────────────────

    /** Duración del compás alrededor de [t]; 0 si no se sabe. */
    fun barMsAt(t: Double): Double {
        exact?.let { return PhaseAlignment.barMs(it) }
        val local = localInterval(t, rawDownbeats)
        if (local > 0) return local.toDouble()
        val beat = localInterval(t, rawBeats)
        return if (beat > 0) beat.toDouble() * beatsPerBar else 0.0
    }

    /** Duración del beat alrededor de [t]; 0 si no se sabe. */
    fun beatMsAt(t: Double): Double {
        exact?.let { return 60_000.0 / it.bpm }
        val beat = localInterval(t, rawBeats)
        if (beat > 0) return beat.toDouble()
        val bar = barMsAt(t)
        return if (bar > 0) bar / beatsPerBar else 0.0
    }

    // ── Downbeats ────────────────────────────────────────────────────────────

    /** Último downbeat en [t] o antes (con [TOLERANCE_MS] de redondeo), o null si no hay. */
    fun downbeatAtOrBefore(t: Double): Double? {
        exact?.let { grid ->
            val bar = PhaseAlignment.barMs(grid)
            val k = floor((t + TOLERANCE_MS - grid.firstDownbeatMs) / bar)
            return (grid.firstDownbeatMs + k * bar).takeIf { inside(it) }
        }
        val index = lastIndexAtOrBefore(rawDownbeats, t + TOLERANCE_MS)
        return if (index >= 0) rawDownbeats[index].toDouble() else null
    }

    /** Primer downbeat en [t] o después (con [TOLERANCE_MS] de redondeo), o null si no hay. */
    fun downbeatAtOrAfter(t: Double): Double? {
        exact?.let { grid ->
            val bar = PhaseAlignment.barMs(grid)
            val k = ceil((t - TOLERANCE_MS - grid.firstDownbeatMs) / bar)
            return (grid.firstDownbeatMs + k * bar).takeIf { inside(it) }
        }
        val index = lastIndexAtOrBefore(rawDownbeats, t - TOLERANCE_MS) + 1
        return if (index in rawDownbeats.indices) rawDownbeats[index].toDouble() else null
    }

    /** Downbeat más cercano a [t], o null si no hay. */
    fun nearestDownbeat(t: Double): Double? {
        val before = downbeatAtOrBefore(t)
        val after = downbeatAtOrAfter(t)
        return when {
            before == null -> after
            after == null -> before
            t - before <= after - t -> before
            else -> after
        }
    }

    /** Downbeats en [fromMs, toMs]: índice del compás (estable al desplazarse) e instante. */
    fun forEachDownbeat(fromMs: Double, toMs: Double, action: (index: Int, ms: Double) -> Unit) {
        val from = maxOf(fromMs, 0.0)
        val to = if (durationMs > 0) minOf(toMs, durationMs.toDouble()) else toMs
        if (to < from) return
        exact?.let { grid ->
            val bar = PhaseAlignment.barMs(grid)
            var k = ceil((from - grid.firstDownbeatMs) / bar).toInt()
            while (true) {
                val ms = grid.firstDownbeatMs + k * bar
                if (ms > to) break
                action(k, ms)
                k++
            }
            return
        }
        var index = lastIndexAtOrBefore(rawDownbeats, from - 0.001) + 1
        while (index < rawDownbeats.size && rawDownbeats[index] <= to) {
            action(index, rawDownbeats[index].toDouble())
            index++
        }
    }

    fun downbeatsBetween(fromMs: Double, toMs: Double): List<Double> {
        val out = ArrayList<Double>()
        forEachDownbeat(fromMs, toMs) { _, ms -> out += ms }
        return out
    }

    // ── Beats ────────────────────────────────────────────────────────────────

    /** Beats en [fromMs, toMs]; con [includeDownbeats] = false se omiten los que caen en un compás. */
    fun forEachBeat(fromMs: Double, toMs: Double, includeDownbeats: Boolean, action: (ms: Double) -> Unit) {
        val from = maxOf(fromMs, 0.0)
        val to = if (durationMs > 0) minOf(toMs, durationMs.toDouble()) else toMs
        if (to < from) return
        exact?.let { grid ->
            val beat = 60_000.0 / grid.bpm
            var k = ceil((from - grid.firstDownbeatMs) / beat).toInt()
            while (true) {
                val ms = grid.firstDownbeatMs + k * beat
                if (ms > to) break
                if (includeDownbeats || k.mod(grid.beatsPerBar) != 0) action(ms)
                k++
            }
            return
        }
        var index = lastIndexAtOrBefore(rawBeats, from - 0.001) + 1
        while (index < rawBeats.size && rawBeats[index] <= to) {
            val ms = rawBeats[index]
            if (includeDownbeats || !isOnDownbeat(index, ms)) action(ms.toDouble())
            index++
        }
    }

    fun beatsBetween(fromMs: Double, toMs: Double, includeDownbeats: Boolean = true): List<Double> {
        val out = ArrayList<Double>()
        forEachBeat(fromMs, toMs, includeDownbeats) { out += it }
        return out
    }

    // ── Fase y enganche ──────────────────────────────────────────────────────

    /** Posición de [t] dentro de su compás, en [0, 1); null si no hay compases alrededor. */
    fun barPhase(t: Double): Double? {
        exact?.let { return PhaseAlignment.barPhase(Math.round(t), it) }
        if (!hasRealDownbeats) return null
        val index = lastIndexAtOrBefore(rawDownbeats, t)
        if (index < 0 || index + 1 >= rawDownbeats.size) return null
        val start = rawDownbeats[index]
        val span = rawDownbeats[index + 1] - start
        return if (span > 0) ((t - start) / span).coerceIn(0.0, 1.0 - 1e-9) else null
    }

    /**
     * Engancha [ms] a un compás: el inicio de frase si está a menos de [BeatMagnet.PHRASE_CAPTURE_BARS]
     * compases, y si no el downbeat más cercano, sin umbral (el marcador siempre queda en un compás).
     * Sin downbeats reales devuelve [ms] con nivel NONE: el llamante decide (p. ej. imán a beats).
     */
    fun snapToBar(ms: Int): BeatMagnet.LevelSnap {
        if (!hasRealDownbeats) return BeatMagnet.LevelSnap(ms, BeatMagnet.Level.NONE)
        val t = ms.toDouble()
        val bar = barMsAt(t)
        if (bar > 0 && phraseStarts.isNotEmpty()) {
            val phrase = phraseStarts[BeatMagnet.nearestIndex(ms, phraseStarts)]
            if (abs(phrase - ms) <= bar * BeatMagnet.PHRASE_CAPTURE_BARS) {
                return BeatMagnet.LevelSnap(phrase, BeatMagnet.Level.PHRASE)
            }
        }
        val downbeat = nearestDownbeat(t) ?: return BeatMagnet.LevelSnap(ms, BeatMagnet.Level.NONE)
        return BeatMagnet.LevelSnap(downbeat.roundToInt(), BeatMagnet.Level.BAR)
    }

    // ── Internos ─────────────────────────────────────────────────────────────

    private fun inside(t: Double): Boolean =
        t >= -TOLERANCE_MS && (durationMs <= 0 || t <= durationMs + TOLERANCE_MS)

    /** ¿El beat [index] de la rejilla en bruto cae en un downbeat? */
    private fun isOnDownbeat(index: Int, ms: Int): Boolean {
        if (!hasRealDownbeats) return index % beatsPerBar == 0
        val nearest = BeatMagnet.nearestIndex(ms, rawDownbeats)
        if (nearest < 0) return false
        val beat = localInterval(ms.toDouble(), rawBeats).takeIf { it > 0 } ?: 400
        return abs(rawDownbeats[nearest] - ms) <= beat / 4
    }

    private fun localInterval(t: Double, grid: List<Int>): Int {
        val index = BeatMagnet.nearestIndex(t.roundToInt(), grid)
        return BeatMagnet.localIntervalMs(index, grid)
    }

    companion object {
        /** Margen de redondeo: las posiciones guardadas son ms enteros de un downbeat exacto. */
        const val TOLERANCE_MS = 1.0

        val EMPTY = BarGrid(null, emptyList(), emptyList(), false, emptyList(), 4, 0)

        /**
         * @param exact rejilla exacta (`PhaseAlignment.gridOf(bpmPrecise, firstDownbeatMs, beatsPerBar)`) o null.
         * @param beats rejilla de beats en bruto; @param downbeats downbeats en bruto; @param phraseStarts
         * inicios de frase. Las listas pueden venir desordenadas o con nulos (se ordenan).
         */
        fun of(
            exact: PhaseAlignment.Grid?,
            beats: List<Int>?,
            downbeats: List<Int>?,
            phraseStarts: List<Int>?,
            beatsPerBar: Int?,
            durationMs: Int
        ): BarGrid {
            val bpb = exact?.beatsPerBar ?: beatsPerBar?.takeIf { it in 1..16 } ?: 4
            val rawBeats = beats.orEmpty().sorted()
            val realDownbeats = downbeats.orEmpty().sorted()
            val hasReal = exact != null || realDownbeats.isNotEmpty()
            val rawDownbeats = when {
                realDownbeats.isNotEmpty() -> realDownbeats
                else -> rawBeats.filterIndexed { index, _ -> index % bpb == 0 }
            }
            val partial = BarGrid(exact, rawBeats, rawDownbeats, hasReal, emptyList(), bpb, durationMs)
            val phrases = phraseStarts.orEmpty().sorted().let { starts ->
                if (exact == null) starts
                else starts.mapNotNull { partial.nearestDownbeat(it.toDouble())?.roundToInt() }.distinct()
            }
            return BarGrid(exact, rawBeats, rawDownbeats, hasReal, phrases, bpb, durationMs)
        }

        /**
         * Velocidad a la que suena B durante la mezcla (`bpmA / bpmB`), o null si no hay rejillas exactas o los
         * tempos no son compatibles. Misma regla que [TransitionPlanner.beatmatchRatio] (la de la reproducción).
         */
        fun tempoLockRatio(a: BarGrid, b: BarGrid): Double? {
            val gridA = a.exact ?: return null
            val gridB = b.exact ?: return null
            val ratio = gridA.bpm / gridB.bpm
            return ratio.takeIf { abs(it - 1.0) <= TransitionPlanner.BEATMATCH_TOLERANCE }
        }

        /** Índice del último elemento ≤ [t] en una lista ordenada, o -1. */
        private fun lastIndexAtOrBefore(grid: List<Int>, t: Double): Int {
            var lo = 0
            var hi = grid.size // primer índice con valor > t
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (grid[mid] <= t) lo = mid + 1 else hi = mid
            }
            return lo - 1
        }
    }
}
