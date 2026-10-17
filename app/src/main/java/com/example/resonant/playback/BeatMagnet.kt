package com.example.resonant.playback

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * "Imán" a la rejilla de beats del editor de Playmix (Fase 1 de transiciones profesionales).
 *
 * Lógica pura: dado un instante y una rejilla ordenada de beats en ms, devuelve el beat al que
 * debe engancharse el marcador. El umbral de captura es una fracción del intervalo local entre
 * beats, así funciona igual a 70 que a 170 BPM. Fuera del umbral (o fuera de la rejilla) el
 * instante se devuelve tal cual, para que el dedo nunca se quede "pegado".
 *
 * Con downbeats y frases (Fase 4, `add-transition-cue-points`), [snapLevels] prueba primero frases,
 * después compases y por último beats.
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

    // ── Niveles: frase > compás > beat ───────────────────────────────────────

    enum class Level { PHRASE, BAR, BEAT, NONE }

    data class LevelSnap(val ms: Int, val level: Level)

    /** Fracción de compás dentro de la cual se engancha a un inicio de frase. */
    const val PHRASE_CAPTURE_BARS = 0.5f

    /** Fracción de compás dentro de la cual se engancha a un downbeat. */
    const val BAR_CAPTURE_BARS = 1f / 3f

    /**
     * Engancha [ms] al inicio de frase, al downbeat o al beat más cercano, en ese orden de prioridad,
     * usando el primer nivel cuyo candidato esté dentro de su umbral. Sin downbeats ni frases se
     * comporta como [snap].
     */
    fun snapLevels(ms: Int, beats: List<Int>, downbeats: List<Int>, phraseStarts: List<Int>): LevelSnap {
        val bar = barLengthMs(ms, beats, downbeats)
        if (bar > 0) {
            nearestWithin(ms, phraseStarts, (bar * PHRASE_CAPTURE_BARS).toInt())?.let { return LevelSnap(it, Level.PHRASE) }
            nearestWithin(ms, downbeats, (bar * BAR_CAPTURE_BARS).toInt())?.let { return LevelSnap(it, Level.BAR) }
        }
        val beat = snap(ms, beats)
        return if (beat.snapped) LevelSnap(beat.ms, Level.BEAT) else LevelSnap(ms, Level.NONE)
    }

    /** Duración del compás alrededor de [ms]: entre downbeats vecinos o, sin ellos, cuatro beats. 0 si no se sabe. */
    fun barLengthMs(ms: Int, beats: List<Int>, downbeats: List<Int>): Int {
        val index = nearestIndex(ms, downbeats)
        val local = localIntervalMs(index, downbeats)
        if (local > 0) return local
        val beatIndex = nearestIndex(ms, beats)
        val beat = localIntervalMs(beatIndex, beats)
        return if (beat > 0) beat * 4 else 0
    }

    private fun nearestWithin(ms: Int, grid: List<Int>, thresholdMs: Int): Int? {
        val index = nearestIndex(ms, grid)
        if (index < 0) return null
        return grid[index].takeIf { abs(it - ms) <= thresholdMs }
    }

    // ── Editor v2: compás de un [MixComposer.Song] y beats coincidentes ───────

    /** Tolerancia para considerar que un beat de A y uno de B suenan a la vez. */
    const val COINCIDENT_TOLERANCE_MS = 25.0

    /**
     * Editor v2 (spotify-style-transition-engine D9): [ms] llevado al downbeat más cercano de [song]; sin
     * rejilla, sin cambios. Con zoom el editor ajusta al beat con [MixComposer.freeWindow].
     */
    fun snapToBar(song: MixComposer.Song, ms: Int): Int {
        val before = MixComposer.downbeatAtOrBefore(song, ms.toDouble())
        val after = MixComposer.downbeatAtOrAfter(song, ms.toDouble())
        val snapped = when {
            before == null -> after
            after == null -> before
            ms - before <= after - ms -> before
            else -> after
        } ?: return ms
        return snapped.coerceIn(0, song.durationMs)
    }

    /** Beats de [song] entre [fromMs] y [toMs] (ms de la canción). */
    fun beats(song: MixComposer.Song, fromMs: Double, toMs: Double): List<Double> {
        val bar = MixComposer.barMs(song) ?: return emptyList()
        val beat = bar / (song.beatsPerBar ?: 4).coerceIn(1, 16)
        val first = (song.firstDownbeatMs ?: song.downbeats?.firstOrNull())?.toDouble() ?: return emptyList()
        val result = ArrayList<Double>()
        var k = ceil((fromMs - first) / beat)
        while (true) {
            val t = first + k * beat
            if (t > toMs) break
            if (t >= 0) result += t
            k += 1
        }
        return result
    }

    /**
     * Posiciones de la ventana (ms desde su inicio) donde un beat de A coincide con uno de B. B entra en
     * [bOffsetMs] y avanza a [rateB] veces su velocidad (beatmatching).
     */
    fun coincidentBeats(
        a: MixComposer.Song, b: MixComposer.Song,
        aStartMs: Int, bStartMs: Int, durationMs: Int, rateB: Double, bOffsetMs: Int = 0
    ): List<Double> {
        if (durationMs <= 0 || rateB <= 0) return emptyList()
        val beatsA = beats(a, aStartMs.toDouble(), aStartMs + durationMs.toDouble()).map { it - aStartMs }
        val beatsB = beats(b, bStartMs.toDouble(), bStartMs + (durationMs - bOffsetMs) * rateB).map { (it - bStartMs) / rateB + bOffsetMs }
        if (beatsA.isEmpty() || beatsB.isEmpty()) return emptyList()
        val result = ArrayList<Double>()
        var j = 0
        for (t in beatsA) {
            while (j < beatsB.size - 1 && beatsB[j + 1] <= t) j++
            val near = min(abs(beatsB[j] - t), if (j + 1 < beatsB.size) abs(beatsB[j + 1] - t) else Double.MAX_VALUE)
            if (near <= COINCIDENT_TOLERANCE_MS) result += max(0.0, t)
        }
        return result
    }
}
