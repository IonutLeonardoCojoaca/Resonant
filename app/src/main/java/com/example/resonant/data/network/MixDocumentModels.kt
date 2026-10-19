package com.example.resonant.data.network

/**
 * Documento de mezcla de Resonant Studio, versión 1 o 2 (mismo formato que el backend). La línea de tiempo
 * empieza en [MixSideADTO.startMs] de A; B entra en [MixSideBDTO.offsetMs] desde [MixSideBDTO.startMs].
 * La versión 2 (spotify-style-transition-engine) usa los carriles a|b.volume, a|b.eq.*, a|b.filter y a.noise
 * y añade [bars], [beatSync], [selection], [loop] y [fx].
 */
data class MixDocumentDTO(
    val version: Int,
    val durationMs: Int,
    val a: MixSideADTO,
    val b: MixSideBDTO,
    val lanes: Map<String, List<MixLanePointDTO>>? = null,
    /** Estilo del que salió el documento (código del preset), si lo hubo. */
    val preset: MixPresetRefDTO? = null,
    val bars: Int? = null,
    val beatSync: Boolean? = null,
    val selection: MixSelectionDTO? = null,
    val loop: MixLoopDTO? = null,
    val fx: List<MixFxDTO>? = null,
    /**
     * v2 (playmix-extended-beatmatch): sincronía en el rango ampliado de Playmix (B estirada más de un 6 % y como
     * mucho un 12 %). Solo cuenta con la capacidad `playmixExtendedSync`; si no, se trata como ausente.
     */
    val extendedSync: Boolean? = null
) {
    val isV2: Boolean get() = version == VERSION_V2

    companion object {
        const val VERSION_V2 = 2
    }
}

/** Opción elegida en cada carril (códigos de `GET api/v1/transition-presets/lane-options`). */
data class MixSelectionDTO(
    val volume: String = "none",
    val eq: String = "none",
    val filter: String = "none",
    val fx: String = "none",
    val loop: String = "none"
)

/** Desde [atMs] de la línea de tiempo, A repite los [lengthMs] que empiezan en a.startMs + atMs. */
data class MixLoopDTO(val track: String = "a", val beats: Int, val atMs: Int, val lengthMs: Int)

/** Eco de A entre [fromMs] y [toMs] con retardo [delayMs] (¾ de beat). */
data class MixFxDTO(val type: String = "echo", val track: String = "a", val fromMs: Int, val toMs: Int, val delayMs: Int)

data class MixPresetRefDTO(val code: String, val version: Int? = null)

/** Catálogo de opciones por carril (`GET api/v1/transition-presets/lane-options`). */
data class MixLaneOptionsDTO(
    val volume: List<MixLaneOptionDTO> = emptyList(),
    val eq: List<MixLaneOptionDTO> = emptyList(),
    val filter: List<MixLaneOptionDTO> = emptyList(),
    val fx: List<MixLaneOptionDTO> = emptyList(),
    val loop: List<MixLaneOptionDTO> = emptyList()
)

data class MixLaneOptionDTO(val code: String, val name: String)

data class MixSideADTO(val startMs: Int)

data class MixSideBDTO(val startMs: Int, val offsetMs: Int = 0)

data class MixLanePointDTO(val t: Int, val v: Double, val curve: String? = null)

// ─── Presets v2: sugerencias (POST /api/transitions/suggest) ───

data class TransitionSuggestRequest(
    val fromSongId: String,
    val toSongId: String,
    val limit: Int = 1
)

data class TransitionSuggestionsDTO(
    val suggestions: List<TransitionSuggestionDTO> = emptyList()
)

data class TransitionSuggestionDTO(
    val code: String,
    val name: String,
    val score: Int,
    val document: MixDocumentDTO
)
