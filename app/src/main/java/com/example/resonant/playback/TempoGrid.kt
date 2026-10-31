package com.example.resonant.playback

import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sqrt

/** De dónde sale la rejilla de una canción en una ventana (playmix-local-tempo-grid). */
enum class GridKind {
    /** La de toda la canción (`bpmPrecise` + primer downbeat), como antes. */
    GLOBAL,
    /** Ajustada a los downbeats detectados en la zona de la mezcla. */
    LOCAL,
    /** Sin rejilla fiable en la ventana: sin sincronía, sin fase y centro en el medio exacto. */
    NONE
}

/**
 * Port literal de `ResonantBackend/.../Service/Studio/TempoGrid.cs` (playmix-local-tempo-grid D2): rejilla de compases
 * de una canción dentro de la ventana de una mezcla. Las canciones con fase ajustada (`downbeatPhaseMs`) usan siempre
 * su rejilla global; las demás, solo si sus downbeats detectados en la ventana caen en ella; si no, una recta ajustada
 * a esos downbeats que crece hacia fuera mientras cuadren. Mismas constantes, mismo orden de operaciones y mismo
 * redondeo que el backend: se comprueba con los fixtures `mix-v2/local-tempo-*`. Si cambia allí, cambia aquí.
 */
object TempoGrid {

    /** Rejilla de compases: primer downbeat (null sin fase) y compás; con [kind] LOCAL, su [bpm] (3 decimales). */
    data class Grid(val firstMs: Double?, val barMs: Double, val kind: GridKind = GridKind.GLOBAL, val bpm: Double? = null) {
        /** Desfase (−½ compás, ½ compás] entre [ms] y el downbeat más cercano. */
        fun phaseAt(ms: Int): Double {
            val first = firstMs ?: return 0.0
            return first + round((ms - first) / barMs) * barMs - ms
        }
    }

    const val ON_GRID_MS = 40.0
    const val GLOBAL_AGREEMENT = 0.8
    const val OFF_GRID_FRACTION = 0.25
    const val MAX_GROW_BARS = 4
    const val MIN_DOWNBEATS = 6
    const val MAX_RMS_MS = 25.0

    /**
     * Rejilla de toda la canción (`MixGenerator.Grid.Of` del backend): compás de `bpmPrecise` si está a ±3 % de la
     * mediana de los downbeats (si no, la mediana) y primer downbeat. Null sin compás.
     */
    fun global(song: MixComposer.Song): Grid? {
        val median = MixComposer.barMs(song)
        val beatsPerBar = (song.beatsPerBar ?: 4).coerceIn(1, 16)
        val fitted = song.bpmPrecise?.takeIf { it > 0 }?.let { 60000.0 / it * beatsPerBar }
        val bar = if (fitted != null && (median == null || abs(fitted / median - 1) <= 0.03)) fitted else median
        if (bar == null || bar <= 0) return null
        val first = song.firstDownbeatMs?.toDouble() ?: song.downbeats?.firstOrNull()?.toDouble()
        return Grid(first, bar)
    }

    /**
     * Rejilla de [song] en la ventana [[fromMs], [toMs]] (ms de la canción). Con [localEnabled] = false, exactamente
     * [global]. Null si la canción no tiene compás.
     */
    fun at(song: MixComposer.Song, fromMs: Double, toMs: Double, localEnabled: Boolean): Grid? {
        val global = global(song)
        if (!localEnabled || global == null || song.downbeatPhaseMs != null) return global

        val downbeats = song.downbeats.orEmpty().sorted()
        val bar0 = medianGap(downbeats, fromMs - global.barMs, toMs) ?: global.barMs
        val first = global.firstMs
        if (first != null && agreesWith(downbeats, fromMs, toMs, first, global.barMs)) return global
        val beatsPerBar = (song.beatsPerBar ?: 4).coerceIn(1, 16)
        return fit(downbeats, fromMs, toMs, bar0, beatsPerBar) ?: Grid(null, bar0, GridKind.NONE)
    }

    /** Tempo para las reglas de sincronía: el de la rejilla local o, si no, el del análisis; null sin rejilla. */
    fun tempoOf(grid: Grid?, song: MixComposer.Song): Double? = when (grid?.kind) {
        GridKind.LOCAL -> grid.bpm
        GridKind.NONE -> null
        else -> song.bpmPrecise ?: song.bpm
    }

    /** Estabilidad para las reglas de sincronía: la del análisis con rejilla global, 1 con local y 0 sin rejilla. */
    fun stabilityOf(grid: Grid?, song: MixComposer.Song): Double = when (grid?.kind) {
        GridKind.LOCAL -> 1.0
        GridKind.NONE -> 0.0
        else -> song.bpmStability ?: 0.0
    }

    /** Mediana de los intervalos entre downbeats consecutivos que empiezan en [lo, hi]; null sin intervalos. */
    private fun medianGap(downbeats: List<Int>, lo: Double, hi: Double): Double? {
        val gaps = ArrayList<Double>()
        for (i in 0 until downbeats.size - 1) {
            if (downbeats[i] < lo || downbeats[i] > hi) continue
            val gap = downbeats[i + 1] - downbeats[i]
            if (gap > 0) gaps += gap.toDouble()
        }
        if (gaps.isEmpty()) return null
        gaps.sort()
        return gaps[gaps.size / 2]
    }

    /** True si al menos el 80 % de los downbeats de la ventana (y alguno) caen en la rejilla global. */
    private fun agreesWith(downbeats: List<Int>, fromMs: Double, toMs: Double, first: Double, bar: Double): Boolean {
        var total = 0
        var onGrid = 0
        for (d in downbeats) {
            if (d < fromMs || d > toMs) continue
            total++
            val nearest = first + round((d - first) / bar) * bar
            if (abs(d - nearest) <= ON_GRID_MS) onGrid++
        }
        return total > 0 && onGrid >= GLOBAL_AGREEMENT * total
    }

    /**
     * Ajuste local: semilla con los downbeats de la ventana (±½ compás) indexados en cadena, un recorte del peor
     * residuo, crecimiento hacia fuera mientras cada downbeat quede en la recta y comprobación de validez.
     */
    private fun fit(downbeats: List<Int>, fromMs: Double, toMs: Double, bar0: Double, beatsPerBar: Int): Grid? {
        val ks = ArrayList<Int>()
        val ts = ArrayList<Double>()
        var k = 0
        var last: Double? = null
        for (d in downbeats) {
            if (d < fromMs - bar0 / 2 || d > toMs + bar0 / 2) continue
            val previous = last
            if (previous != null) {
                val x = (d - previous) / bar0
                val r = round(x)
                if (r < 1 || abs(x - r) > OFF_GRID_FRACTION) continue
                k += r.toInt()
            }
            ks += k
            ts += d.toDouble()
            last = d.toDouble()
        }
        var (a, p) = leastSquares(ks, ts) ?: return null

        if (ks.size >= 3) {
            var worst = 0
            for (i in 1 until ks.size) {
                if (abs(ts[i] - (a + p * ks[i])) > abs(ts[worst] - (a + p * ks[worst]))) worst = i
            }
            if (abs(ts[worst] - (a + p * ks[worst])) > ON_GRID_MS) {
                ks.removeAt(worst)
                ts.removeAt(worst)
                val trimmed = leastSquares(ks, ts) ?: return null
                a = trimmed.first
                p = trimmed.second
            }
        }

        val after = downbeats.filter { it > toMs + bar0 / 2 && it <= toMs + bar0 * (MAX_GROW_BARS + 0.5) }
        val before = downbeats.filter { it < fromMs - bar0 / 2 && it >= fromMs - bar0 * (MAX_GROW_BARS + 0.5) }.reversed()
        for (side in listOf(after, before)) {
            for (d in side) {
                val index = round((d - a) / p).toInt()
                if (abs(d - (a + p * index)) > ON_GRID_MS) break
                ks += index
                ts += d.toDouble()
                val grown = leastSquares(ks, ts) ?: return null
                a = grown.first
                p = grown.second
            }
        }

        if (ks.size < MIN_DOWNBEATS || p <= 0) return null
        var squares = 0.0
        var startSupported = false
        var endSupported = false
        for (i in ks.indices) {
            val residual = ts[i] - (a + p * ks[i])
            squares += residual * residual
            if (abs(residual) > ON_GRID_MS) continue
            if (abs(ts[i] - fromMs) <= p) startSupported = true
            if (abs(ts[i] - toMs) <= p) endSupported = true
        }
        if (sqrt(squares / ks.size) > MAX_RMS_MS || !startSupported || !endSupported) return null

        val bpm = round(60000.0 * beatsPerBar / p * 1000) / 1000
        val first = a + p * round((fromMs - a) / p)
        return Grid(first, 60000.0 * beatsPerBar / bpm, GridKind.LOCAL, bpm)
    }

    /** Recta t = a + p·k por mínimos cuadrados; null con menos de 2 puntos o índices iguales. */
    private fun leastSquares(ks: List<Int>, ts: List<Double>): Pair<Double, Double>? {
        val n = ks.size
        if (n < 2) return null
        var sumK = 0.0
        var sumT = 0.0
        for (i in 0 until n) {
            sumK += ks[i]
            sumT += ts[i]
        }
        val mk = sumK / n
        val mt = sumT / n
        var skk = 0.0
        var skt = 0.0
        for (i in 0 until n) {
            skk += (ks[i] - mk) * (ks[i] - mk)
            skt += (ks[i] - mk) * (ts[i] - mt)
        }
        if (skk <= 0) return null
        val p = skt / skk
        return Pair(mt - p * mk, p)
    }
}
