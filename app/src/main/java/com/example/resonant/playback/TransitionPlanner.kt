package com.example.resonant.playback

import com.example.resonant.data.models.AudioAnalysis
import com.example.resonant.data.network.MixDocumentDTO
import com.example.resonant.data.network.MixLanePointDTO
import com.example.resonant.data.network.MixSideADTO
import com.example.resonant.data.network.MixSideBDTO
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Planificador de las transiciones automáticas (fuera de Playmix y álbum). Lógica pura.
 * Design: openspec/changes/unify-transition-engine.
 *
 * Todos los modos producen un documento de mezcla v1 (el mismo formato que Studio y las recetas)
 * que ejecuta un único motor. Las ganancias usan siempre curvas de potencia constante
 * (`equalPower`): la suma de energía de A y B se mantiene y no hay bajadas a mitad del fundido.
 *
 * - Suave: fundido de la duración elegida que termina donde acaba el audio de A; B entra
 *   donde empieza su audio. Con rejilla, el fundido empieza en un compás de A.
 * - Corte: corte casi instantáneo (empalme de [CUT_SPLICE_MS]) al final de A. Con rejilla en las
 *   dos, el primer downbeat de B cae justo en el compás siguiente de A.
 * - Inteligente: con rejilla en las dos y BPM compatibles, mezcla en compases enteros con sincronía
 *   de tempo y una "unión" suave: B entra sin graves, los medios de A se retiran, los graves se
 *   cruzan en una rampa de un beat que acaba en un downbeat y los agudos de A se van al final; si no,
 *   fundido de [SMART_BLEND_MS] con un traspaso suave de graves. Una receta del servidor tiene prioridad.
 *
 * Regla común ("outro sobre intro"): la mezcla termina donde acaba el audio de A y B empieza en su
 * inicio (primer downbeat), así que no se salta nada de ninguna canción. Los cue points solo limitan
 * cuánto dura la mezcla: el outro de A (desde `mixOut`) y la intro de B (hasta `mixIn`).
 */
object TransitionPlanner {

    enum class Mode { SOFT, CUT, SMART }

    /** Tiempos útiles de una canción, en ms de su propio audio. */
    data class Track(
        val durationMs: Long,
        val audioStartMs: Long,
        val audioEndMs: Long,
        val mixInMs: Long?,
        val mixOutMs: Long?,
        val grid: PhaseAlignment.Grid?,
        /**
         * Estabilidad del tempo (0–1), solo para el rango ampliado de Playmix ([extendedBeatmatchRatio]); null =
         * desconocida. El análisis del cliente no la trae: con tempo exacto se da por estable, como en `MixComposer.songOf`.
         */
        val stability: Double? = null
    )

    /**
     * @param beatSync B debe sonar al tempo de A durante la mezcla (y volver a su tempo al final).
     * @param bpmRatio velocidad de B durante la mezcla (`bpmA / bpmB`); 1 si no hay sincronía.
     */
    data class Plan(
        val doc: MixDocumentDTO,
        val label: String,
        val beatSync: Boolean = false,
        val bpmRatio: Double = 1.0,
        /**
         * playmix-extended-beatmatch: B va a [bpmRatio] (|bpmRatio − 1| > 0,06) durante toda la mezcla, sin la
         * vuelta del 75 %, y al acabar vuelve a 1,0 con [TempoReturn]. Solo lo marcan los planes de Playmix.
         */
        val extendedSync: Boolean = false
    ) {
        val hasEq: Boolean get() = doc.lanes?.keys?.any { it.contains(".eq.") } == true
    }

    const val CUT_SPLICE_MS = 120L
    const val SMART_BLEND_MS = 8_000L
    const val SMART_MIN_BLEND_MS = 6_000L
    const val SMART_MAX_BLEND_MS = 12_000L
    const val MAX_BEATMATCH_MS = 40_000L
    const val BEATMATCH_TOLERANCE = 0.06
    /** Tope del rango ampliado de Playmix (`MaxExtendedSyncDeltaPct` del backend, `EXTENDED_MAX_RATE_DEVIATION` del worker). */
    const val EXTENDED_BEATMATCH_TOLERANCE = 0.12
    /** Estabilidad mínima de las dos canciones en el rango ampliado (`MinSyncStability` del backend). */
    const val EXTENDED_MIN_STABILITY = 0.8
    /**
     * Corte de graves del beatmatch. Se pide más de lo que admite el EQ del dispositivo (suele llegar a
     * −15 dB): `TransitionManager` lo limita a su rango, así que en la práctica es el máximo corte posible.
     */
    const val BASS_KILL_DB = -30.0
    const val BASS_SOFT_DB = -6.0
    /** Medios de A al final de su retirada (antes del intercambio de graves). */
    const val MID_FADE_DB = -12.0
    /** Agudos de A al final de la mezcla: es lo último que se va. */
    const val HIGH_FADE_DB = -12.0

    fun trackOf(analysis: AudioAnalysis?, exoDurationMs: Long): Track? {
        val analysisDuration = (analysis?.durationMs ?: 0).toLong()
        val duration = TransitionTiming.effectiveDurationMs(exoDurationMs, analysisDuration)
        if (duration <= 0) return null
        val end = TransitionTiming.audioEndMs(exoDurationMs, analysisDuration, (analysis?.audioEndMs ?: 0).toLong())
        val start = (analysis?.audioStartMs ?: 0).toLong().coerceIn(0L, (end - 1).coerceAtLeast(0L))
        fun inside(v: Int?): Long? = v?.toLong()?.takeIf { it in (start + 1) until end }
        return Track(
            durationMs = duration,
            audioStartMs = start,
            audioEndMs = end,
            // Cue points v2; sin ellos (canciones aún sin análisis v2), los puntos óptimos v1.
            mixInMs = inside(analysis?.mixInMs) ?: inside(analysis?.optimalStartPointMs),
            mixOutMs = inside(analysis?.mixOutMs) ?: inside(analysis?.optimalExitPointMs),
            grid = PhaseAlignment.gridOf(analysis?.bpmPrecise, analysis?.firstDownbeatMs, analysis?.beatsPerBar),
            stability = if (analysis?.bpmPrecise != null) 1.0 else null
        )
    }

    /** Plan para la pareja, o null si no se puede (fundido 0 = sin transición, sin audio suficiente). */
    fun plan(mode: Mode, a: Track, b: Track, fadeMs: Long): Plan? = when (mode) {
        Mode.SOFT -> soft(a, b, fadeMs)
        Mode.CUT -> cut(a, b)
        Mode.SMART -> beatmatch(a, b) ?: blend(a, b)
    }

    // ── Suave ──────────────────────────────────────────────────────────────────────────────

    private fun soft(a: Track, b: Track, fadeMs: Long): Plan? {
        if (fadeMs <= 0) return null
        val fade = fadeMs.coerceAtMost(a.audioEndMs - a.audioStartMs)
        if (fade < TransitionTiming.MIN_FADE_MS) return null
        var start = a.audioEndMs - fade
        a.grid?.let { grid ->
            val bar = downbeatAtOrBefore(grid, start.toDouble()).toLong()
            if (bar >= 0 && start - bar <= fade / 4) start = bar
        }
        val duration = a.audioEndMs - start
        return Plan(
            doc = document(start, b.audioStartMs, duration, crossfadeLanes(duration)),
            label = "soft"
        )
    }

    // ── Corte ──────────────────────────────────────────────────────────────────────────────

    private fun cut(a: Track, b: Track): Plan? {
        val half = CUT_SPLICE_MS / 2
        var cutAt = a.audioEndMs
        var label = "cut"
        val gridA = a.grid
        val gridB = b.grid
        if (gridA != null && gridB != null) {
            val firstDownbeatB = downbeatAtOrAfter(gridB, b.audioStartMs.toDouble())
            val lead = firstDownbeatB - b.audioStartMs
            if (lead >= 0 && lead <= PhaseAlignment.barMs(gridB)) {
                // El downbeat de B cae en el compás de A más cercano al final de su audio.
                val target = nearestDownbeat(gridA, a.audioEndMs.toDouble())
                val candidate = (target - lead).roundToInt().toLong()
                if (candidate > a.audioStartMs + half && candidate <= a.durationMs - half) {
                    cutAt = candidate
                    label = "cut-beat"
                }
            }
        }
        if (cutAt - half < 0) return null
        val bStart = (b.audioStartMs - half).coerceAtLeast(0L)
        return Plan(
            doc = document(cutAt - half, bStart, CUT_SPLICE_MS, crossfadeLanes(CUT_SPLICE_MS)),
            label = label
        )
    }

    // ── Inteligente ────────────────────────────────────────────────────────────────────────

    /** Velocidad de B para igualar el tempo de A, o null si la diferencia no es corregible. */
    fun beatmatchRatio(a: Track, b: Track): Double? {
        val gridA = a.grid ?: return null
        val gridB = b.grid ?: return null
        val ratio = gridA.bpm / gridB.bpm
        return ratio.takeIf { abs(it - 1.0) <= BEATMATCH_TOLERANCE }
    }

    /**
     * Velocidad de B en el rango ampliado de Playmix (playmix-extended-beatmatch): |bpmA/bpmB − 1| en (0,06, 0,12],
     * sin doblar ni dividir a la mitad, con las dos estabilidades conocidas y ≥ 0,8. Null fuera de ese rango (el
     * estándar lo cubre [beatmatchRatio]). Se compara con la razón redondeada a 6 decimales, como el mix worker.
     */
    fun extendedBeatmatchRatio(a: Track, b: Track): Double? {
        val gridA = a.grid ?: return null
        val gridB = b.grid ?: return null
        if (gridA.bpm <= 0.0 || gridB.bpm <= 0.0) return null
        if ((a.stability ?: return null) < EXTENDED_MIN_STABILITY || (b.stability ?: return null) < EXTENDED_MIN_STABILITY) return null
        val ratio = gridA.bpm / gridB.bpm
        val stretch = abs(Math.round(ratio * 1e6) / 1e6 - 1.0)
        return ratio.takeIf { stretch > BEATMATCH_TOLERANCE + 1e-9 && stretch <= EXTENDED_BEATMATCH_TOLERANCE + 1e-9 }
    }

    /**
     * Plan de una transición de Playmix con `extendedSync` (playmix-extended-beatmatch D6): con la capacidad activa y
     * el documento marcado, la velocidad es `beatmatchRatio ?: extendedBeatmatchRatio` y el plan queda ampliado si
     * |ratio − 1| > 0,06. Sin capacidad o sin la marca, el plan de siempre (`beatmatchRatio`).
     */
    fun playmixPlan(doc: MixDocumentDTO, label: String, a: Track?, b: Track?, extendedEnabled: Boolean): Plan {
        val extendedDoc = extendedEnabled && doc.extendedSync == true
        val syncAllowed = !doc.isV2 || doc.beatSync == true
        val ratio = if (a != null && b != null) {
            beatmatchRatio(a, b) ?: if (extendedDoc && syncAllowed) extendedBeatmatchRatio(a, b) else null
        } else null
        val sync = ratio != null && syncAllowed
        val extended = sync && extendedDoc && abs(ratio!! - 1.0) > BEATMATCH_TOLERANCE
        return Plan(doc, label, beatSync = sync, bpmRatio = ratio ?: 1.0, extendedSync = extended)
    }

    private fun beatmatch(a: Track, b: Track): Plan? {
        val ratio = beatmatchRatio(a, b) ?: return null
        val gridA = a.grid!!
        val gridB = b.grid!!
        val barA = PhaseAlignment.barMs(gridA)
        val bStart = downbeatAtOrAfter(gridB, b.audioStartMs.toDouble())

        // Cuánto cabe: el outro de A y la intro de B (B suena `ratio` veces más rápida durante la mezcla).
        var room: Double? = null
        a.mixOutMs?.let { room = (a.audioEndMs - it).toDouble() }
        b.mixInMs?.takeIf { it > bStart }?.let { introMs ->
            val intro = (introMs - bStart) / ratio
            room = room?.let { minOf(it, intro) } ?: intro
        }
        var bars = if (room != null) 16 else 8
        while (bars > 4 && (bars * barA > (room ?: Double.MAX_VALUE) || bars * barA > MAX_BEATMATCH_MS)) bars /= 2
        val blend = bars * barA

        val aStart = downbeatAtOrBefore(gridA, a.audioEndMs - blend)
        if (aStart < a.audioStartMs || aStart + blend > a.durationMs) return null

        val duration = blend.roundToInt().toLong()
        val end = duration.toInt()
        val beat = (barA / gridA.beatsPerBar).roundToInt()
        // Intercambio de graves en un compás de A: el del centro o, si cae dentro, el del mixIn de B
        // (donde termina su intro), pasado a tiempo de la mezcla (B suena `ratio` veces más rápida).
        val swapBars = b.mixInMs
            ?.let { (it - bStart) / ratio }
            ?.takeIf { it >= barA && it <= blend - barA }
            ?.let { Math.round(it / barA).toInt().coerceIn(1, bars - 1) }
            ?: (bars / 2)
        val swap = (swapBars * barA).roundToInt()
        val swapFrom = (swap - beat).coerceAtLeast(0)
        // Unión suave: ganancias de potencia constante en toda la mezcla; B entra sin graves y con sus medios;
        // los medios de A se retiran antes del intercambio, los graves se cruzan en una rampa de un beat que
        // acaba en el downbeat y los agudos de A son lo último que se va.
        val lanes = mapOf(
            "a.gain" to listOf(point(0, 1.0, "equalPower"), point(end, 0.0)),
            "b.gain" to listOf(point(0, 0.0, "equalPower"), point(end, 1.0)),
            "a.eq.low" to lane(point(0, 0.0, "linear"), point(swapFrom, 0.0, "smooth"), point(swap, BASS_KILL_DB)),
            "b.eq.low" to lane(point(0, BASS_KILL_DB, "linear"), point(swapFrom, BASS_KILL_DB, "smooth"), point(swap, 0.0)),
            "a.eq.mid" to lane(point(0, 0.0, "smooth"), point(swapFrom, MID_FADE_DB)),
            "a.eq.high" to lane(point(0, 0.0, "linear"), point(swap, 0.0, "smooth"), point(end, HIGH_FADE_DB))
        )
        return Plan(
            doc = document(aStart.roundToInt().toLong(), bStart.roundToInt().toLong().coerceAtLeast(0L), duration, lanes),
            label = "smart-beatmatch-${bars}bars",
            beatSync = true,
            bpmRatio = ratio
        )
    }

    private fun blend(a: Track, b: Track): Plan? {
        val end = a.audioEndMs
        val outroLength = a.mixOutMs?.let { end - it }
        val duration = outroLength?.takeIf { it in SMART_MIN_BLEND_MS..SMART_MAX_BLEND_MS } ?: SMART_BLEND_MS
        var start = end - duration
        if (start < a.audioStartMs) return soft(a, b, end - a.audioStartMs)
        a.grid?.let { grid ->
            val bar = downbeatAtOrBefore(grid, start.toDouble()).toLong()
            if (bar >= a.audioStartMs && start - bar <= duration / 4) start = bar
        }
        val length = end - start
        val bStart = b.audioStartMs
        val bassIn = (length * 0.6).roundToInt()
        val lanes = crossfadeLanes(length) + mapOf(
            "a.eq.low" to listOf(point(0, 0.0, "smooth"), point(length.toInt(), BASS_SOFT_DB)),
            "b.eq.low" to listOf(point(0, BASS_SOFT_DB, "smooth"), point(bassIn, 0.0))
        )
        return Plan(doc = document(start, bStart, length, lanes), label = "smart-blend")
    }

    // ── Utilidades ─────────────────────────────────────────────────────────────────────────

    private fun crossfadeLanes(durationMs: Long): Map<String, List<MixLanePointDTO>> = mapOf(
        "a.gain" to listOf(point(0, 1.0, "equalPower"), point(durationMs.toInt(), 0.0)),
        "b.gain" to listOf(point(0, 0.0, "equalPower"), point(durationMs.toInt(), 1.0))
    )

    private fun document(aStartMs: Long, bStartMs: Long, durationMs: Long, lanes: Map<String, List<MixLanePointDTO>>) =
        MixDocumentDTO(
            version = 1,
            durationMs = durationMs.toInt(),
            a = MixSideADTO(startMs = aStartMs.toInt()),
            b = MixSideBDTO(startMs = bStartMs.toInt(), offsetMs = 0),
            lanes = lanes
        )

    private fun point(t: Int, v: Double, curve: String? = null) = MixLanePointDTO(t = t, v = v, curve = curve)

    /** Carril sin puntos repetidos en el mismo instante (se queda el último de cada instante). */
    private fun lane(vararg points: MixLanePointDTO): List<MixLanePointDTO> =
        points.filterIndexed { i, p -> i == points.lastIndex || points[i + 1].t > p.t }

    fun downbeatAtOrBefore(grid: PhaseAlignment.Grid, t: Double): Double {
        val bar = PhaseAlignment.barMs(grid)
        return grid.firstDownbeatMs + floor((t - grid.firstDownbeatMs) / bar) * bar
    }

    fun downbeatAtOrAfter(grid: PhaseAlignment.Grid, t: Double): Double {
        val before = downbeatAtOrBefore(grid, t)
        return if (before >= t - 0.5) before else before + PhaseAlignment.barMs(grid)
    }

    fun nearestDownbeat(grid: PhaseAlignment.Grid, t: Double): Double {
        val before = downbeatAtOrBefore(grid, t)
        val after = before + PhaseAlignment.barMs(grid)
        return if (t - before <= after - t) before else after
    }
}
