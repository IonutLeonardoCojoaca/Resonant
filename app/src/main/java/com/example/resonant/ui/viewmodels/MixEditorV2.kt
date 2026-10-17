package com.example.resonant.ui.viewmodels

import com.example.resonant.data.network.MixDocumentDTO
import com.example.resonant.data.network.MixLaneOptionDTO
import com.example.resonant.data.network.MixLaneOptionsDTO
import com.example.resonant.data.network.MixSelectionDTO
import com.example.resonant.data.network.StudioSongDTO
import com.example.resonant.data.network.StudioTransitionDTO
import com.example.resonant.data.network.TransitionPresetDTO
import com.example.resonant.playback.BeatMagnet
import com.example.resonant.playback.MixComposer
import com.example.resonant.playback.WaveformBands

/** Escucha del documento en edición: el servicio usa [document] para esta pareja y empieza A en [startPositionMs]. */
data class MixPreviewRequest(
    val fromSongId: String,
    val toSongId: String,
    val document: MixDocumentDTO,
    val startPositionMs: Int
)

/** Estado del editor de transiciones v2 (spotify-style-transition-engine D9). */
data class MixEditorV2State(
    val songA: MixComposer.Song,
    val songB: MixComposer.Song,
    val infoA: StudioSongDTO,
    val infoB: StudioSongDTO,
    val document: MixDocumentDTO,
    /** Preset activo: código de un preset v2, [MixEditorV2.AUTOMATIC] o [MixEditorV2.CUSTOM]. */
    val presetCode: String,
    val selection: MixSelectionDTO,
    val window: MixComposer.Window,
    val beatSync: Boolean,
    val presets: List<TransitionPresetDTO> = MixEditorV2.DEFAULT_PRESETS,
    val laneOptions: MixLaneOptionsDTO = MixEditorV2.DEFAULT_LANE_OPTIONS,
    val bandsA: WaveformBands? = null,
    val bandsB: WaveformBands? = null
) {
    /** Compás de A en ms (para mover la ventana y dibujar la rejilla). */
    val barMsA: Double? get() = MixComposer.barMs(songA)

    /** Velocidad de B en la ventana (bpmA/bpmB con beatmatching, 1 sin él). */
    val rateB: Double get() = if (document.beatSync == true) MixComposer.rate(songA, songB) else 1.0
}

/**
 * Lógica pura del editor v2: cada acción devuelve el estado nuevo con el documento regenerado por
 * [MixComposer] (el mismo generador que el backend). Elegir un preset conserva la ventana; Automática la recoloca;
 * tocar un carril o arrastrar una canción pasa a Personalizada.
 */
object MixEditorV2 {
    const val AUTOMATIC = MixComposer.AUTOMATIC_CODE
    const val CUSTOM = "personalizada"
    const val LANE_VOLUME = "volume"
    const val LANE_EQ = "eq"
    const val LANE_FILTER = "filter"
    const val LANE_FX = "fx"
    const val LANE_LOOP = "loop"
    val BAR_OPTIONS = listOf(1, 2, 4, 8, 16, 32)

    fun songOf(info: StudioSongDTO): MixComposer.Song = MixComposer.Song(
        durationMs = info.durationMs,
        audioStartMs = info.audioStartMs ?: 0,
        audioEndMs = info.audioEndMs ?: info.durationMs,
        bpm = info.bpm,
        bpmPrecise = info.bpmPrecise,
        bpmStability = info.bpmStability ?: if (info.bpmPrecise != null) 1.0 else null,
        beatsPerBar = info.beatsPerBar,
        firstDownbeatMs = info.firstDownbeatMs,
        downbeats = info.downbeats,
        mixInMs = info.mixInMs,
        mixOutMs = info.mixOutMs,
        dropMs = info.dropMs,
        keyCamelot = info.camelot
    )

    /**
     * Estado inicial: el documento v2 guardado tal cual (ventana, opciones y preset), o Automática si la
     * transición aún no tiene documento v2. Null si las canciones no tienen compás.
     */
    fun initial(studio: StudioTransitionDTO): MixEditorV2State? {
        val a = songOf(studio.songA)
        val b = songOf(studio.songB)
        val saved = studio.document.takeIf { it.isV2 }
        if (saved != null) {
            val window = MixComposer.Window(saved.a.startMs, saved.b.startMs, saved.bars ?: MixComposer.DEFAULT_BARS)
            return MixEditorV2State(
                songA = a, songB = b, infoA = studio.songA, infoB = studio.songB,
                document = saved,
                presetCode = saved.preset?.code ?: CUSTOM,
                selection = saved.selection ?: MixSelectionDTO(),
                window = window,
                beatSync = saved.beatSync ?: !MixComposer.tempoClash(a, b)
            )
        }
        val window = MixComposer.automaticWindow(a, b) ?: return null
        val selection = MixComposer.automaticSelection(a, b)
        val document = MixComposer.compose(selection, window, a, b, null, AUTOMATIC) ?: return null
        return MixEditorV2State(a, b, studio.songA, studio.songB, document, AUTOMATIC, selection, window, document.beatSync ?: true)
    }

    fun selectPreset(state: MixEditorV2State, code: String): MixEditorV2State {
        if (code == AUTOMATIC) {
            val window = MixComposer.automaticWindow(state.songA, state.songB) ?: return state
            val selection = MixComposer.automaticSelection(state.songA, state.songB)
            val sync = !MixComposer.tempoClash(state.songA, state.songB)
            return regenerate(state.copy(presetCode = AUTOMATIC, selection = selection, window = window, beatSync = sync))
        }
        val preset = state.presets.firstOrNull { it.code == code } ?: return state
        val selection = preset.selection ?: return state
        return regenerate(state.copy(presetCode = code, selection = selection))
    }

    /** Cambia la opción de un carril ([LANE_VOLUME], [LANE_EQ]…): la mezcla pasa a Personalizada. */
    fun selectLaneOption(state: MixEditorV2State, lane: String, code: String): MixEditorV2State {
        val selection = when (lane) {
            LANE_VOLUME -> state.selection.copy(volume = code)
            LANE_EQ -> state.selection.copy(eq = code)
            LANE_FILTER -> state.selection.copy(filter = code)
            LANE_FX -> state.selection.copy(fx = code)
            LANE_LOOP -> state.selection.copy(loop = code)
            else -> return state
        }
        return regenerate(state.copy(selection = selection, presetCode = presetFor(state.presets, selection)))
    }

    /**
     * Compases de la ventana. En Automática se recoloca como la colocaría el servidor con esos compases; en el
     * resto la ventana crece o encoge alrededor de su centro, así el punto de cruce no se mueve.
     */
    fun setBars(state: MixEditorV2State, bars: Int): MixEditorV2State {
        if (bars !in BAR_OPTIONS) return state
        if (state.presetCode == AUTOMATIC) {
            val window = MixComposer.automaticWindow(state.songA, state.songB, bars) ?: return state
            return regenerate(state.copy(window = window))
        }
        val bar = state.barMsA ?: return regenerate(state.copy(window = state.window.copy(bars = bars)))
        val shift = (state.window.bars - bars) / 2.0 * bar
        val a = (state.window.aStartMs + shift).toInt().coerceIn(0, state.songA.durationMs)
        val b = (state.window.bStartMs + shift * state.rateB).toInt().coerceIn(0, state.songB.durationMs)
        return regenerate(state.copy(window = MixComposer.freeWindow(state.songA, state.songB, a, b, bars)))
    }

    /** Mueve la ventana [deltaBars] compases en las dos canciones a la vez (B a su velocidad en la mezcla). */
    fun moveWindow(state: MixEditorV2State, deltaBars: Int): MixEditorV2State {
        val bar = state.barMsA ?: return state
        val a = state.window.aStartMs + deltaBars * bar
        val b = state.window.bStartMs + deltaBars * bar * state.rateB
        return moveTo(state, a.toInt(), b.toInt())
    }

    /**
     * Arrastre de la onda de A: qué parte de A cae en la ventana. Imán al compás; con [toBeat] (zoom) al beat.
     * Pasa a Personalizada.
     */
    fun dragA(state: MixEditorV2State, aStartMs: Int, toBeat: Boolean = false): MixEditorV2State {
        val snapped = if (toBeat) aStartMs else BeatMagnet.snapToBar(state.songA, aStartMs)
        return moveTo(state, snapped, state.window.bStartMs, custom = true)
    }

    /** Arrastre de la onda de B (mismo imán que A). */
    fun dragB(state: MixEditorV2State, bStartMs: Int, toBeat: Boolean = false): MixEditorV2State {
        val snapped = if (toBeat) bStartMs else BeatMagnet.snapToBar(state.songB, bStartMs)
        return moveTo(state, state.window.aStartMs, snapped, custom = true)
    }

    /** Beats de A y B que coinciden en la ventana (para resaltarlos en la regla). */
    fun coincidentBeats(state: MixEditorV2State): List<Double> = BeatMagnet.coincidentBeats(
        state.songA, state.songB, state.document.a.startMs, state.document.b.startMs, state.document.durationMs, state.rateB,
        state.document.b.offsetMs
    )

    /** Compases de A que suenan antes de la ventana al escuchar la mezcla. */
    const val PREVIEW_LEAD_BARS = 2

    /** Escucha del documento sin guardar, desde [PREVIEW_LEAD_BARS] compases antes de la ventana. */
    fun preview(state: MixEditorV2State): MixPreviewRequest {
        val lead = (state.barMsA ?: FALLBACK_LEAD_MS) * PREVIEW_LEAD_BARS
        val start = (state.document.a.startMs - lead).toInt().coerceAtLeast(0)
        return MixPreviewRequest(state.infoA.songId, state.infoB.songId, state.document, start)
    }

    private const val FALLBACK_LEAD_MS = 2000.0

    fun setBeatSync(state: MixEditorV2State, enabled: Boolean): MixEditorV2State = regenerate(state.copy(beatSync = enabled))

    private fun moveTo(state: MixEditorV2State, aStartMs: Int, bStartMs: Int, custom: Boolean = false): MixEditorV2State {
        val a = aStartMs.coerceIn(0, state.songA.durationMs)
        val b = bStartMs.coerceIn(0, state.songB.durationMs)
        val window = MixComposer.freeWindow(state.songA, state.songB, a, b, state.window.bars)
        val code = if (custom || state.presetCode == AUTOMATIC) CUSTOM else state.presetCode
        return regenerate(state.copy(window = window, presetCode = code))
    }

    private fun regenerate(state: MixEditorV2State): MixEditorV2State {
        val document = MixComposer.compose(state.selection, state.window, state.songA, state.songB, state.beatSync, state.presetCode)
            ?: return state
        return state.copy(document = document)
    }

    /** Opción elegida en un carril. */
    fun optionOf(selection: MixSelectionDTO, lane: String): String = when (lane) {
        LANE_VOLUME -> selection.volume
        LANE_EQ -> selection.eq
        LANE_FILTER -> selection.filter
        LANE_FX -> selection.fx
        else -> selection.loop
    }

    /**
     * Estado de la mezcla para el editor: sin guardar, o guardada y su clip del servidor (que es lo que suena en
     * la Playmix; mientras se renderiza suena la mezcla en directo del documento).
     */
    fun statusLabel(dirty: Boolean, renderStatus: String?): String = when {
        dirty -> "● Cambios sin guardar"
        renderStatus == "ready" -> "✓ Guardada · mezcla renderizada"
        renderStatus == "pending" || renderStatus == "rendering" -> "◌ Guardada · renderizando la mezcla…"
        renderStatus == "failed" -> "Guardada · el render falló, suena en directo"
        else -> "Guardada · suena en directo"
    }

    /** Descripción del preset (la del servidor) o, sin ella, las opciones de los carriles que no son "Ninguno". */
    fun describe(state: MixEditorV2State): String {
        state.presets.firstOrNull { it.code == state.presetCode }?.description?.takeIf { it.isNotBlank() }?.let { return it }
        if (state.presetCode == AUTOMATIC) return "Elegida para esta pareja: la intro de la segunda canción entra bajo el final de la primera."
        val options = state.laneOptions
        val names = listOf(
            options.volume to state.selection.volume, options.eq to state.selection.eq,
            options.filter to state.selection.filter, options.fx to state.selection.fx, options.loop to state.selection.loop
        ).filter { it.second != MixComposer.NONE }
            .map { (list, code) -> list.firstOrNull { it.code == code }?.name ?: code }
        return if (names.isEmpty()) "Sin transición: la segunda canción entra al acabar la primera." else names.joinToString(" · ")
    }

    /** Preset cuyas opciones son exactamente estas, o Personalizada. */
    private fun presetFor(presets: List<TransitionPresetDTO>, selection: MixSelectionDTO): String =
        presets.firstOrNull { it.selection == selection }?.code ?: CUSTOM

    /** Presets v2 del servidor si los hay; si no (sin red o caché antigua), los del seed. */
    fun presetsOrDefault(server: List<TransitionPresetDTO>): List<TransitionPresetDTO> {
        val v2 = server.filter { it.category == "mix-v2" }
        return if (v2.any { it.selection != null }) v2 else DEFAULT_PRESETS
    }

    private fun preset(code: String, name: String, order: Int, selection: MixSelectionDTO?) =
        TransitionPresetDTO(code, code, name, "", "", "mix-v2", true, order, selection)

    /** Copia de `seed_presets_v2.sql` para cuando no hay respuesta del servidor. */
    val DEFAULT_PRESETS: List<TransitionPresetDTO> = listOf(
        preset(AUTOMATIC, "Automática", 100, null),
        preset("fundido", "Fundido", 101, MixSelectionDTO(volume = "smooth-crossfade", eq = "bass-swap-center")),
        preset("crescendo", "Crescendo", 102, MixSelectionDTO(volume = "overlap", eq = "bass-swap-end", filter = "lpf-in-hpf-out")),
        preset("fusion", "Fusión", 103, MixSelectionDTO(volume = "overlap", eq = "three-band-fade")),
        preset("onda", "Onda", 104, MixSelectionDTO(volume = "overlap", eq = "bass-swap-center", filter = "lpf-in-out")),
        preset("crossover", "Crossover", 105, MixSelectionDTO(volume = "fade-in-fade-out", eq = "bass-swap-center", filter = "hpf-in-out")),
        preset("corte", "Corte", 106, MixSelectionDTO(volume = "cut-center")),
        preset("disolucion", "Disolución", 107, MixSelectionDTO(volume = "fade-in-cut-out", eq = "bass-swap-end", filter = "half-hpf-out")),
        preset("impulso", "Impulso", 108, MixSelectionDTO(volume = "overlap", eq = "bass-swap-end", filter = "noise-riser-end")),
        preset("halo", "Halo", 109, MixSelectionDTO(volume = "smooth-crossfade", eq = "bass-swap-center")),
        preset("sin-transicion", "Sin transición", 110, MixSelectionDTO())
    )

    private fun options(vararg pairs: Pair<String, String>) = pairs.map { MixLaneOptionDTO(it.first, it.second) }

    /** Copia del catálogo del backend (`MixCatalog.cs`) para cuando no hay respuesta del servidor. */
    val DEFAULT_LANE_OPTIONS = MixLaneOptionsDTO(
        volume = options(
            "overlap" to "Superposición", "cut-center" to "Corte central",
            "fade-in-cut-out" to "Entrada gradual y corte de salida", "cut-in-fade-out" to "Corte de entrada y salida gradual",
            "fade-in-fast-out" to "Entrada gradual y salida rápida", "fade-in-fade-out" to "Entrada gradual y salida gradual",
            "smooth-crossfade" to "Crossfade suave", "none" to "Ninguno"
        ),
        eq = options(
            "bass-swap-center" to "Intercambio de graves central", "bass-swap-end" to "Intercambio de graves final",
            "bass-swap-start" to "Intercambio de graves inicial", "three-band-fade" to "Fundido a 3 bandas",
            "bass-cut-fast" to "Corte de graves rápido", "bass-cut-slow" to "Corte de graves lento",
            "start-fade-out" to "Iniciar salida gradual", "bass-fade-out" to "Salida gradual de graves", "none" to "Ninguno"
        ),
        filter = options(
            "lpf-out" to "Filtro paso bajo de salida", "lpf-in" to "Filtro paso bajo de entrada",
            "lpf-in-out" to "Filtro paso bajo de entrada y salida",
            "lpf-in-hpf-out" to "Filtro paso bajo de entrada y filtro paso alto de salida",
            "hpf-out" to "Filtro paso alto de salida", "hpf-in" to "Filtro paso alto de entrada",
            "hpf-in-out" to "Filtro paso alto de entrada y salida",
            "hpf-in-lpf-out" to "Filtro paso alto de entrada y filtro paso bajo de salida",
            "half-hpf-out" to "Medio filtro paso alto de salida", "noise-riser-end" to "Ruido progresivo final", "none" to "Ninguno"
        ),
        fx = options("echo-out" to "Eco de salida", "none" to "Ninguno"),
        loop = options(
            "1" to "Loop de 1 beat", "2" to "Loop de 2 beats", "4" to "Loop de 4 beats",
            "8" to "Loop de 8 beats", "16" to "Loop de 16 beats", "none" to "Ninguno"
        )
    )
}
