package com.example.resonant.playback

import com.example.resonant.data.models.AudioAnalysis
import com.example.resonant.data.network.MixDocumentDTO
import com.example.resonant.data.network.MixFxDTO
import com.example.resonant.data.network.MixLanePointDTO
import com.example.resonant.data.network.MixLoopDTO
import com.example.resonant.data.network.MixPresetRefDTO
import com.example.resonant.data.network.MixSelectionDTO
import com.example.resonant.data.network.MixSideADTO
import com.example.resonant.data.network.MixSideBDTO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Port de `ResonantBackend/.../Service/Studio/MixComposer.cs` (spotify-style-transition-engine D6): mismas formas,
 * misma ventana de Automática y mismo redondeo (al par, como `Math.Round` de C#; los tiempos de los puntos, lejos
 * del cero). Se comprueba con los fixtures JSON de `mix-v2/` que escribe el test del backend: si cambia allí, cambia
 * aquí. Lo usan el modo Automática sin red y el editor (respuesta inmediata); al guardar manda el backend.
 */
object MixComposer {

    /** Lo que el generador necesita de una canción (los campos de `SongAudioAnalysis` del backend). */
    data class Song(
        val durationMs: Int,
        val audioStartMs: Int,
        val audioEndMs: Int,
        val bpm: Double? = null,
        val bpmPrecise: Double? = null,
        val bpmStability: Double? = null,
        val beatsPerBar: Int? = null,
        val firstDownbeatMs: Int? = null,
        val downbeats: List<Int>? = null,
        val mixInMs: Int? = null,
        val mixOutMs: Int? = null,
        val dropMs: Int? = null,
        val keyCamelot: String? = null,
        /** Fase ajustada (tempo estable); sin ella la rejilla de una mezcla sale de su ventana (playmix-local-tempo-grid). */
        val downbeatPhaseMs: Int? = null
    )

    data class Window(val aStartMs: Int, val bStartMs: Int, val bars: Int)

    const val KILL_DB = -40.0
    const val DEFAULT_BARS = 4
    const val LONG_BARS = 8
    const val CLASH_BARS = 2
    const val MAX_SYNC_DELTA_PCT = 6.0
    const val MIN_SYNC_STABILITY = 0.8
    /** Estirado máximo de B (|bpmA/bpmB − 1|, %) en el rango ampliado de Playmix (`MaxExtendedSyncDeltaPct`). */
    const val MAX_EXTENDED_SYNC_DELTA_PCT = 12.0
    const val MAX_DROP_FROM_START_MS = 20_000
    /** B nunca empieza más allá de esto de su inicio de audio (`MaxBStartFromAudioStartMs` del backend). */
    const val MAX_B_START_FROM_AUDIO_START_MS = 12_000
    private const val NOISE_STEP = 0.33
    /** Profundidad inicial de los filtros de entrada de B: paso bajo ≈ 2 kHz, paso alto ≈ 300 Hz: B se oye amortiguado. */
    const val IN_FILTER_DEPTH = 0.45
    private const val ECHO_DELAY_BEATS = 0.75
    const val NONE = "none"
    const val AUTOMATIC_CODE = "automatica"

    /**
     * Canción del reproductor: el análisis del cliente no trae la estabilidad del tempo ni la rejilla completa;
     * con tempo exacto se da por estable (sin red es mejor sincronizar que caer siempre en Fundido).
     */
    fun songOf(analysis: AudioAnalysis): Song = Song(
        durationMs = analysis.durationMs,
        audioStartMs = analysis.audioStartMs,
        audioEndMs = analysis.audioEndMs,
        bpm = analysis.bpm.takeIf { it > 0 } ?: analysis.bpmNormalized.takeIf { it > 0 },
        bpmPrecise = analysis.bpmPrecise,
        bpmStability = if (analysis.bpmPrecise != null) 1.0 else null,
        beatsPerBar = analysis.beatsPerBar,
        firstDownbeatMs = analysis.firstDownbeatMs,
        mixInMs = analysis.mixInMs,
        mixOutMs = analysis.mixOutMs,
        dropMs = analysis.dropMs
    )

    // ─── Automática sin servidor ────────────────────────────────────────────────────────────────

    /**
     * Preset que elegiría Automática con el seed de `seed_presets_v2.sql`: con tempos incompatibles, Fundido;
     * con tonalidades compatibles (Camelot ≤ 1), Fusión; si no, Onda. Con [window] y [localTempo], el choque de tempos
     * se mide en la ventana (como `Automatic` del backend).
     */
    fun automaticSelection(
        from: Song, to: Song, extended: Boolean = false, window: Window? = null, localTempo: Boolean = false
    ): MixSelectionDTO {
        val clash = if (window != null) tempoClash(from, to, window, extended, localTempo) else tempoClash(from, to, extended)
        return when {
            clash -> MixSelectionDTO(volume = "smooth-crossfade", eq = "bass-swap-center")
            (camelotDistance(from.keyCamelot, to.keyCamelot) ?: Double.MAX_VALUE) <= 1 -> MixSelectionDTO(volume = "overlap", eq = "three-band-fade")
            else -> MixSelectionDTO(volume = "overlap", eq = "bass-swap-center", filter = "lpf-in-out")
        }
    }

    /** Documento de Automática completo: preset, compases y ventana. */
    fun automatic(from: Song, to: Song): MixDocumentDTO? {
        val window = automaticWindow(from, to) ?: return null
        return compose(automaticSelection(from, to), window, from, to, null, AUTOMATIC_CODE)
    }

    // ─── Ventana ────────────────────────────────────────────────────────────────────────────────

    /**
     * El usuario puede forzar el beatmatching: tempos conocidos y a ≤ 6 % (como `CanSync` del backend). Con
     * [extended] (editor de Playmix con la capacidad activa) también en el rango ampliado.
     */
    fun canSync(from: Song, to: Song, extended: Boolean = false): Boolean = canSync(temposOf(from, to), extended)

    /**
     * Automática no sincroniza (valor por defecto de beatSync): > 6 % o rejilla poco estable. Con [extended], una
     * pareja en [inExtendedRange] no choca.
     */
    fun tempoClash(from: Song, to: Song, extended: Boolean = false): Boolean = tempoClash(temposOf(from, to), extended)

    /**
     * Rango ampliado de Playmix (playmix-extended-beatmatch D2, `InExtendedRange` del backend): choca con la regla
     * del ±6 %, |bpmA/bpmB − 1| (razón redondeada a 6 decimales, sin octavas) en (6 %, 12 %] y las dos estabilidades ≥ 0,8.
     */
    fun inExtendedRange(from: Song, to: Song): Boolean = inExtendedRange(temposOf(from, to))

    /** |bpmA/bpmB − 1| con la razón redondeada a 6 decimales (`DirectStretch` del backend); null sin tempos. */
    fun directStretch(from: Song, to: Song): Double? = directRate(temposOf(from, to))?.let { abs(it - 1.0) }

    // ─── Tempos y rejillas de la ventana (playmix-local-tempo-grid) ─────────────────────────────

    /**
     * Tempos y estabilidades con los que se decide la sincronía: los del análisis completo o, con la rejilla de la
     * ventana, el de cada rejilla local (estable) o ninguno si la canción no tiene rejilla (D4).
     */
    data class Tempos(val a: Double?, val b: Double?, val stabilityA: Double, val stabilityB: Double)

    /** Rejillas de A y B en una ventana ([TempoGrid.at]). */
    data class WindowGrids(val a: TempoGrid.Grid?, val b: TempoGrid.Grid?)

    fun temposOf(from: Song, to: Song): Tempos = Tempos(bpm(from), bpm(to), from.bpmStability ?: 0.0, to.bpmStability ?: 0.0)

    fun temposOf(from: Song, to: Song, grids: WindowGrids, localTempo: Boolean): Tempos =
        if (localTempo) {
            Tempos(
                TempoGrid.tempoOf(grids.a, from), TempoGrid.tempoOf(grids.b, to),
                TempoGrid.stabilityOf(grids.a, from), TempoGrid.stabilityOf(grids.b, to)
            )
        } else {
            temposOf(from, to)
        }

    /**
     * Rejilla de A en [aStart, aStart + compases] y de B en lo que avanza durante la ventana, medidos con el compás y
     * la velocidad del análisis completo (primer paso de D3). Sin [localTempo], las rejillas de siempre.
     */
    fun gridsAt(from: Song, to: Song, aStartMs: Int, bStartMs: Int, bars: Int, localTempo: Boolean): WindowGrids {
        val globalA = TempoGrid.global(from)
        if (!localTempo || globalA == null) return WindowGrids(globalA, TempoGrid.global(to))
        val span = bars * globalA.barMs
        return WindowGrids(
            TempoGrid.at(from, aStartMs.toDouble(), aStartMs + span, localEnabled = true),
            TempoGrid.at(to, bStartMs.toDouble(), bStartMs + span * rate(from, to), localEnabled = true)
        )
    }

    /** `TempoClash` del backend con los tempos de la ventana si [localTempo]; con false, el de siempre. */
    fun tempoClash(from: Song, to: Song, window: Window, extended: Boolean, localTempo: Boolean): Boolean =
        tempoClash(temposOf(from, to, gridsAt(from, to, window.aStartMs, window.bStartMs, window.bars, localTempo), localTempo), extended)

    /** [canSync] con los tempos de la ventana si [localTempo]. */
    fun canSync(from: Song, to: Song, window: Window, extended: Boolean, localTempo: Boolean): Boolean =
        canSync(temposOf(from, to, gridsAt(from, to, window.aStartMs, window.bStartMs, window.bars, localTempo), localTempo), extended)

    fun tempoClash(t: Tempos, extended: Boolean = false): Boolean {
        val delta = bpmDeltaPct(t.a, t.b)
        val clash = delta == null || delta > MAX_SYNC_DELTA_PCT || min(t.stabilityA, t.stabilityB) < MIN_SYNC_STABILITY
        return clash && !(extended && inExtendedRange(t))
    }

    fun canSync(t: Tempos, extended: Boolean = false): Boolean {
        val delta = bpmDeltaPct(t.a, t.b)
        return (delta != null && delta <= MAX_SYNC_DELTA_PCT) || (extended && inExtendedRange(t))
    }

    fun inExtendedRange(t: Tempos): Boolean {
        if (!tempoClash(t)) return false
        val stretch = directRate(t)?.let { abs(it - 1.0) } ?: return false
        if (t.stabilityA < MIN_SYNC_STABILITY || t.stabilityB < MIN_SYNC_STABILITY) return false
        return stretch > MAX_SYNC_DELTA_PCT / 100 + 1e-9 && stretch <= MAX_EXTENDED_SYNC_DELTA_PCT / 100 + 1e-9
    }

    private fun directRate(t: Tempos): Double? {
        val a = t.a ?: return null
        val b = t.b ?: return null
        if (a <= 0 || b <= 0) return null
        return round(a / b * 1e6) / 1e6
    }

    /** Tempos que guarda el documento (D5): solo si las dos tienen rejilla y alguna es local. */
    private fun windowBpms(from: Song, to: Song, grids: WindowGrids): Pair<Double, Double>? {
        val a = grids.a?.takeIf { it.kind != GridKind.NONE } ?: return null
        val b = grids.b?.takeIf { it.kind != GridKind.NONE } ?: return null
        if (a.kind != GridKind.LOCAL && b.kind != GridKind.LOCAL) return null
        val bpmA = TempoGrid.tempoOf(a, from) ?: return null
        val bpmB = TempoGrid.tempoOf(b, to) ?: return null
        return Pair(round3(bpmA), round3(bpmB))
    }

    /**
     * Ventana de Automática. Con [localTempo] (playmix-local-tempo-grid D3) se recalcula una vez con el compás de A y
     * los tempos de la ventana elegida con la regla de siempre.
     */
    fun automaticWindow(
        from: Song, to: Song, requestedBars: Int? = null, extended: Boolean = false, localTempo: Boolean = false
    ): Window? {
        val gridA = TempoGrid.global(from) ?: return null
        val window = automaticWindow(from, to, requestedBars, extended, gridA.barMs, temposOf(from, to))
        if (!localTempo || window == null) return window
        val grids = gridsAt(from, to, window.aStartMs, window.bStartMs, window.bars, localTempo = true)
        return automaticWindow(from, to, requestedBars, extended, grids.a!!.barMs, temposOf(from, to, grids, localTempo = true)) ?: window
    }

    private fun automaticWindow(from: Song, to: Song, requestedBars: Int?, extended: Boolean, bar: Double, tempos: Tempos): Window? {
        val clash = tempoClash(tempos, extended)
        val bars = requestedBars ?: when {
            clash -> CLASH_BARS
            introBMs(to) >= LONG_BARS / 2 * bar && outroAMs(from) >= LONG_BARS / 2 * bar -> LONG_BARS
            else -> DEFAULT_BARS
        }

        // La mezcla termina en el último downbeat de A con audio (A suena toda la ventana) y el punto fuerte de B cae
        // en el centro, donde se intercambian los graves (igual que el backend).
        val audioEndA = audioEnd(from)
        val endA = downbeatAtOrBefore(from, audioEndA.toDouble()) ?: audioEndA
        val strongB = strongPointB(to)
        val rate = if (clash) 1.0 else if (extended && inExtendedRange(tempos)) directRate(tempos) ?: rate(tempos.a, tempos.b) else rate(tempos.a, tempos.b)
        val length = bars * bar

        var aStart = endA - length
        var bStart = strongB - length / 2 * rate
        // B sin intro suficiente: B empieza en su primer downbeat con audio y A no se mueve.
        val audioStartB = max(0, to.audioStartMs)
        if (bStart < audioStartB) bStart = (downbeatAtOrAfter(to, audioStartB.toDouble()) ?: audioStartB).toDouble()
        // Intro larga: B entra como mucho en el último downbeat antes del tope (con el mix-in lejos sonaba desde el 30 s).
        val maxBStart = audioStartB + MAX_B_START_FROM_AUDIO_START_MS
        if (bStart > maxBStart) bStart = (downbeatAtOrBefore(to, maxBStart.toDouble()) ?: maxBStart).toDouble()
        if (aStart < 0) {
            val shiftBars = ceil(-aStart / bar)
            aStart += shiftBars * bar
            bStart += shiftBars * bar * rate
        }

        val aStartMs = roundEven(aStart)
        val bStartMs = roundEven(bStart)
        if (aStartMs > from.durationMs || bStartMs > to.durationMs) return null
        return Window(aStartMs, bStartMs, bars)
    }

    fun freeWindow(from: Song, to: Song, aStartMs: Int, bStartMs: Int, bars: Int): Window =
        Window(snapBeat(TempoGrid.global(from), from, aStartMs), snapBeat(TempoGrid.global(to), to, bStartMs), bars)

    /**
     * Ventana elegida por el usuario con la rejilla de la ventana (playmix-local-tempo-grid D6): al beat de cada rejilla
     * solo si el documento va a salir sincronizado ([beatSync] como en [compose]); si no, al milisegundo. Sin
     * [localTempo], la de siempre.
     */
    fun freeWindow(
        from: Song, to: Song, aStartMs: Int, bStartMs: Int, bars: Int, beatSync: Boolean?, extended: Boolean, localTempo: Boolean
    ): Window {
        if (!localTempo) return freeWindow(from, to, aStartMs, bStartMs, bars)
        val grids = gridsAt(from, to, aStartMs, bStartMs, bars, localTempo = true)
        if (!sync(beatSync, temposOf(from, to, grids, localTempo = true), extended)) return Window(aStartMs, bStartMs, bars)
        return Window(snapBeat(grids.a, from, aStartMs), snapBeat(grids.b, to, bStartMs), bars)
    }

    /** null = Automática decide; true = forzado (si se puede); false = sin sincronizar (igual que el backend). */
    private fun sync(beatSync: Boolean?, tempos: Tempos, extended: Boolean): Boolean = when (beatSync) {
        true -> canSync(tempos, extended)
        false -> false
        null -> !tempoClash(tempos, extended)
    }

    // ─── Documento ──────────────────────────────────────────────────────────────────────────────

    /**
     * Documento v2. Con [localTempo] (playmix-local-tempo-grid), compases, fase y sincronía salen de la rejilla de cada
     * canción en la ventana y el documento guarda sus tempos si alguna es local; con false, el de siempre.
     */
    fun compose(
        selection: MixSelectionDTO, window: Window, from: Song, to: Song, beatSync: Boolean?, presetCode: String,
        extended: Boolean = false, localTempo: Boolean = false
    ): MixDocumentDTO? {
        val grids = gridsAt(from, to, window.aStartMs, window.bStartMs, window.bars, localTempo)
        val tempos = temposOf(from, to, grids, localTempo)
        val sync = sync(beatSync, tempos, extended)
        if (selection.volume == NONE) return noTransition(from, to, sync, presetCode)
        val gridA = grids.a ?: return null

        val bar = gridA.barMs
        val beat = bar / (from.beatsPerBar ?: 4).coerceIn(1, 16)
        val duration = roundEven(window.bars * bar)
        val t = Timeline(gridA.phaseAt(window.aStartMs), bar, beat, duration, window.bars)
        val bpms = if (localTempo) windowBpms(from, to, grids) else null

        val lanes = LinkedHashMap<String, List<MixLanePointDTO>>()
        addVolume(lanes, selection.volume, t)
        addEq(lanes, selection.eq, t)
        addFilter(lanes, selection.filter, t)

        return MixDocumentDTO(
            version = MixDocumentDTO.VERSION_V2,
            durationMs = duration,
            a = MixSideADTO(window.aStartMs, bpms?.first),
            b = MixSideBDTO(window.bStartMs, 0, bpms?.second),
            lanes = lanes,
            preset = MixPresetRefDTO(presetCode, MixDocumentDTO.VERSION_V2),
            bars = window.bars,
            beatSync = sync,
            selection = selection.copy(),
            loop = loop(selection.loop, t),
            fx = if (selection.fx == "echo-out") listOf(echo(t)) else null,
            extendedSync = if (sync && extended && inExtendedRange(tempos)) true else null
        )
    }

    private fun noTransition(from: Song, to: Song, sync: Boolean, presetCode: String): MixDocumentDTO = MixDocumentDTO(
        version = MixDocumentDTO.VERSION_V2,
        durationMs = 0,
        a = MixSideADTO(audioEnd(from)),
        b = MixSideBDTO(downbeatAtOrAfter(to, to.audioStartMs.toDouble()) ?: to.audioStartMs, 0),
        lanes = emptyMap(),
        preset = MixPresetRefDTO(presetCode, MixDocumentDTO.VERSION_V2),
        beatSync = sync,
        selection = MixSelectionDTO()
    )

    // ─── Volumen ────────────────────────────────────────────────────────────────────────────────

    private fun addVolume(lanes: MutableMap<String, List<MixLanePointDTO>>, option: String, t: Timeline) {
        val d = t.duration.toDouble()
        val h = t.half
        val swap = t.swap
        val bIn = Shape(0.0, 0.0).smooth(swap, 1.0)
        val aCutEnd = Shape(0.0, 1.0).hold(d - swap).smooth(d, 0.0)
        val bRise = Shape(0.0, 0.0).line(h, 1.0)
        val aFall = Shape(0.0, 1.0).hold(h).line(d, 0.0)
        val (a, b) = when (option) {
            "overlap" -> aCutEnd to bIn
            "cut-center" -> Shape(0.0, 1.0).hold(h - t.bar / 8).smooth(h, 0.0) to Shape(0.0, 0.0).hold(h - t.bar / 8).smooth(h, 1.0)
            "fade-in-cut-out" -> aCutEnd to bRise
            "cut-in-fade-out" -> aFall to bIn
            "fade-in-fast-out" -> Shape(0.0, 1.0).hold(max(h, d - t.bar)).line(d, 0.0) to bRise
            "fade-in-fade-out" -> aFall to bRise
            "smooth-crossfade" -> Shape(0.0, 1.0).smooth(d, 0.0) to Shape(0.0, 0.0).smooth(d, 1.0)
            else -> aCutEnd to bIn
        }
        lanes["a.volume"] = a.build(t)
        lanes["b.volume"] = b.build(t)
    }

    // ─── EQ ─────────────────────────────────────────────────────────────────────────────────────

    private fun addEq(lanes: MutableMap<String, List<MixLanePointDTO>>, option: String, t: Timeline) {
        val d = t.duration.toDouble()
        val h = t.half
        when (option) {
            "bass-swap-center" -> addBassSwap(lanes, t, h)
            "bass-swap-start" -> addBassSwap(lanes, t, t.swap)
            "bass-swap-end" -> addBassSwap(lanes, t, d)
            "three-band-fade" -> {
                addBassSwap(lanes, t, h)
                for (band in listOf("mid", "high")) {
                    lanes["a.eq.$band"] = Shape(0.0, 0.0).hold(t.snapBeat(d * 0.15)).smooth(t.snapBeat(d * 0.75), KILL_DB).build(t)
                    lanes["b.eq.$band"] = Shape(0.0, KILL_DB).smooth(t.snapBeat(d * 0.625), 0.0).build(t)
                }
            }
            "bass-cut-fast" -> lanes["a.eq.low"] = Shape(0.0, 0.0).hold(h - t.beat).smooth(h, KILL_DB).build(t)
            "bass-cut-slow" -> lanes["a.eq.low"] = Shape(0.0, 0.0).hold(h).line(d, KILL_DB).build(t)
            "start-fade-out" -> lanes["a.eq.low"] = Shape(0.0, 0.0).smooth(h, KILL_DB).build(t)
            "bass-fade-out" -> lanes["a.eq.low"] = Shape(0.0, 0.0).hold(t.snapBar(d * 0.125)).smooth(h, KILL_DB).build(t)
        }
    }

    private fun addBassSwap(lanes: MutableMap<String, List<MixLanePointDTO>>, t: Timeline, at: Double) {
        val start = max(0.0, at - t.swap)
        lanes["a.eq.low"] = Shape(0.0, 0.0).hold(start).smooth(at, KILL_DB).build(t)
        lanes["b.eq.low"] = Shape(0.0, KILL_DB).hold(start).smooth(at, 0.0).build(t)
    }

    // ─── Filtro y ruido ─────────────────────────────────────────────────────────────────────────

    private fun addFilter(lanes: MutableMap<String, List<MixLanePointDTO>>, option: String, t: Timeline) {
        val d = t.duration.toDouble()
        val h = t.half
        fun out(to: Double) = Shape(0.0, 0.0).hold(h).line(d, to)
        // B entra filtrado pero audible (igual que el backend: InFilterDepth).
        fun into(from: Double) = Shape(0.0, from * IN_FILTER_DEPTH).line(h, 0.0)
        val (a, b) = when (option) {
            "lpf-out" -> out(-1.0) to null
            "lpf-in" -> null to into(-1.0)
            "lpf-in-out" -> out(-1.0) to into(-1.0)
            "lpf-in-hpf-out" -> out(1.0) to into(-1.0)
            "hpf-out" -> out(1.0) to null
            "hpf-in" -> null to into(1.0)
            "hpf-in-out" -> out(1.0) to into(1.0)
            "hpf-in-lpf-out" -> out(-1.0) to into(1.0)
            "half-hpf-out" -> out(0.5) to null
            else -> null to null
        }
        a?.let { lanes["a.filter"] = it.build(t) }
        b?.let { lanes["b.filter"] = it.build(t) }
        if (option == "noise-riser-end") {
            lanes["a.noise"] = Shape(0.0, 0.0).hold(h).smooth(min(d, h + t.beat), NOISE_STEP).line(d, 1.0).build(t)
        }
    }

    // ─── Bucle y eco ────────────────────────────────────────────────────────────────────────────

    private fun loop(option: String, t: Timeline): MixLoopDTO? {
        val beats = option.toIntOrNull() ?: return null
        val length = roundEven(beats * t.beat)
        if (length <= 0 || length > t.duration) return null
        return MixLoopDTO("a", beats, roundEven(max(0.0, t.half - length)), length)
    }

    private fun echo(t: Timeline) = MixFxDTO("echo", "a", roundEven(t.half), t.duration, max(1, roundEven(ECHO_DELAY_BEATS * t.beat)))

    // ─── Utilidades ─────────────────────────────────────────────────────────────────────────────

    private fun bpm(song: Song): Double? = song.bpmPrecise ?: song.bpm

    /** Diferencia relativa de tempo en %, con la menor de las relaciones ×1, ×2 y ×½ (`RecipeEngine.BpmDeltaPct`). */
    fun bpmDeltaPct(a: Double?, b: Double?): Double? {
        if (a == null || b == null || a <= 0 || b <= 0) return null
        return listOf(b, b * 2, b / 2).minOf { abs(it - a) / a * 100 }
    }

    /** Pasos en la rueda Camelot: mismo código 0, vecino o relativo 1 (`RecipeEngine.CamelotDistance`). */
    fun camelotDistance(a: String?, b: String?): Double? {
        val (na, la) = parseCamelot(a) ?: return null
        val (nb, lb) = parseCamelot(b) ?: return null
        val diff = abs(na - nb)
        val circular = min(diff, 12 - diff)
        return (if (la == lb) circular else circular + 1).toDouble()
    }

    private fun parseCamelot(code: String?): Pair<Int, Char>? {
        if (code.isNullOrBlank() || code.length < 2) return null
        val letter = code.last().uppercaseChar()
        val number = code.dropLast(1).toIntOrNull() ?: return null
        return if ((letter == 'A' || letter == 'B') && number in 1..12) number to letter else null
    }

    /** Velocidad de B para ir al tempo de A (bpmA/bpmB, contando ×2 y ×½); 1 si falta algún tempo. */
    fun rate(from: Song, to: Song): Double = rate(bpm(from), bpm(to))

    /** [rate] con dos tempos cualesquiera (p. ej. los de la ventana). */
    fun rate(bpmA: Double?, bpmB: Double?): Double {
        val a = bpmA ?: return 1.0
        val b = bpmB ?: return 1.0
        if (a <= 0 || b <= 0) return 1.0
        val nearest = listOf(b, b * 2, b / 2).minByOrNull { abs(it - a) }!!
        return a / nearest
    }

    private fun introBMs(to: Song): Int = to.mixInMs?.let { it - max(0, to.audioStartMs) } ?: 0

    private fun outroAMs(from: Song): Int {
        val end = audioEnd(from)
        return from.mixOutMs?.let { if (end > it) end - it else 0 } ?: 0
    }

    private fun audioEnd(song: Song): Int =
        if (song.audioEndMs > 0 && song.audioEndMs <= song.durationMs) song.audioEndMs else song.durationMs

    private fun strongPointB(to: Song): Int {
        val start = max(0, to.audioStartMs)
        val drop = to.dropMs
        if (drop != null && drop > start && drop - start <= MAX_DROP_FROM_START_MS)
            return downbeatAtOrBefore(to, drop.toDouble()) ?: drop
        val mixIn = to.mixInMs
        if (mixIn != null && mixIn > start) return mixIn
        return downbeatAtOrAfter(to, start.toDouble()) ?: start
    }

    private fun snapBeat(grid: TempoGrid.Grid?, song: Song, ms: Int): Int {
        grid ?: return ms
        val first = grid.firstMs ?: return ms
        val beat = grid.barMs / (song.beatsPerBar ?: 4).coerceIn(1, 16)
        return roundEven(first + roundEven((ms - first) / beat).toDouble() * beat).coerceIn(0, song.durationMs)
    }

    // ─── Rejilla (TransitionPresetCalculator.BarMs y DownbeatAt*, MixGenerator.Grid) ─────────────

    /** Compás: mediana entre downbeats o, sin ellos, BPM × compases. */
    fun barMs(song: Song): Double? {
        val downbeats = song.downbeats
        if (downbeats != null && downbeats.size >= 2) {
            val gaps = downbeats.zipWithNext { a, b -> (b - a).toDouble() }.filter { it > 0 }.sorted()
            if (gaps.isNotEmpty()) return gaps[gaps.size / 2]
        }
        val bpm = song.bpmPrecise ?: song.bpm
        return if (bpm != null && bpm > 0) 60000.0 / bpm * (song.beatsPerBar ?: 4) else null
    }

    fun downbeatAtOrBefore(song: Song, t: Double): Int? {
        song.downbeats?.takeIf { it.isNotEmpty() }?.let { grid ->
            var best: Int? = null
            for (d in grid) {
                if (d <= t) best = d else break
            }
            if (best != null) return best
        }
        val first = song.firstDownbeatMs
        val bar = barMs(song)
        if (first != null && bar != null && t >= first) return roundEven(first + floor((t - first) / bar) * bar)
        return null
    }

    fun downbeatAtOrAfter(song: Song, t: Double): Int? {
        song.downbeats?.takeIf { it.isNotEmpty() }?.let { grid ->
            for (d in grid) if (d >= t) return d
        }
        val first = song.firstDownbeatMs
        val bar = barMs(song)
        if (first != null && bar != null) return if (t <= first) first else roundEven(first + ceil((t - first) / bar) * bar)
        return null
    }

    /** Rejilla de A en la línea de tiempo (t = 0 en a.startMs). */
    private class Timeline(val phase: Double, val bar: Double, val beat: Double, val duration: Int, val bars: Int) {
        val half: Double get() = if (bars >= 2) clamp(snapBar(duration / 2.0)) else clamp(snapBeat(duration / 2.0))
        val swap: Double get() = beat / 4
        val fillet: Double get() = beat / 8
        fun snapBar(t: Double): Double = phase + round((t - phase) / bar) * bar
        fun snapBeat(t: Double): Double = phase + round((t - phase) / beat) * beat
        fun clamp(t: Double): Double = t.coerceIn(0.0, duration.toDouble())
    }

    /** Forma de un carril como tramos encadenados; ver `MixComposer.Shape` del backend. */
    private class Shape(t: Double, v: Double) {
        private val points = mutableListOf(Point(t, v, LINEAR))

        fun hold(t: Double) = add(t, points.last().v, HOLD)
        fun line(t: Double, v: Double) = add(t, v, LINEAR)
        fun smooth(t: Double, v: Double) = add(t, v, SMOOTH)

        private fun add(t: Double, v: Double, curve: String): Shape {
            val last = points.last()
            if (t <= last.t) {
                points[points.lastIndex] = Point(last.t, v, last.curve)
                return this
            }
            points[points.lastIndex] = Point(last.t, last.v, curve)
            points += Point(t, v, LINEAR)
            return this
        }

        fun build(t: Timeline): List<MixLanePointDTO> {
            val clamped = points.map { Point(t.clamp(it.t), it.v, it.curve) }
            val rounded = mutableListOf(clamped[0])
            for (i in 1 until clamped.size - 1) {
                val prev = clamped[i - 1]
                val cur = clamped[i]
                val next = clamped[i + 1]
                val straightIn = prev.curve == LINEAR || prev.curve == HOLD
                val straightOut = cur.curve == LINEAR || cur.curve == HOLD
                val r = min(t.fillet, min(cur.t - prev.t, next.t - cur.t) / 2)
                if (straightIn && straightOut && r >= 1 && slope(prev, cur) != slope(cur, next)) {
                    rounded[rounded.lastIndex] = rounded.last().copy(curve = prev.curve)
                    rounded += Point(cur.t - r, at(prev, cur, cur.t - r), SMOOTH)
                    rounded += Point(cur.t + r, at(cur, next, cur.t + r), cur.curve)
                } else {
                    rounded += cur
                }
            }
            if (clamped.size > 1) rounded += clamped.last()

            val result = mutableListOf<MutablePoint>()
            for (p in rounded) {
                val ms = roundAwayFromZero(p.t)
                val value = round4(p.v)
                if (result.isNotEmpty() && ms <= result.last().t) {
                    result.last().v = value
                    result.last().curve = p.curve
                    continue
                }
                result += MutablePoint(ms, value, p.curve)
            }
            for (i in result.indices) {
                if (i == result.lastIndex) result[i].curve = LINEAR
                else if (result[i].v == result[i + 1].v) result[i].curve = HOLD
            }
            return result.map { MixLanePointDTO(it.t, it.v, it.curve) }
        }

        private fun slope(a: Point, b: Point): Double = if (b.t > a.t) roundTo((b.v - a.v) / (b.t - a.t), 1e9) else 0.0

        private fun at(a: Point, b: Point, t: Double): Double = if (b.t > a.t) a.v + (b.v - a.v) * (t - a.t) / (b.t - a.t) else b.v
    }

    private data class Point(val t: Double, val v: Double, val curve: String)
    private class MutablePoint(val t: Int, var v: Double, var curve: String)

    private const val LINEAR = "linear"
    private const val HOLD = "hold"
    private const val SMOOTH = "smooth"

    /** `(int)Math.Round(x)` de C#: al par. */
    private fun roundEven(x: Double): Int = round(x).toInt()

    private fun roundAwayFromZero(x: Double): Int = (if (x >= 0) floor(x + 0.5) else ceil(x - 0.5)).toInt()

    private fun round4(x: Double): Double = roundTo(x, 1e4)

    private fun round3(x: Double): Double = roundTo(x, 1e3)

    private fun roundTo(x: Double, scale: Double): Double = round(x * scale) / scale
}
