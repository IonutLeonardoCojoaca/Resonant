package com.example.resonant.data.network

/**
 * Documento de mezcla v1 de Resonant Studio (mismo formato que el backend). La línea de tiempo
 * empieza en [MixSideADTO.startMs] de A; B entra en [MixSideBDTO.offsetMs] desde [MixSideBDTO.startMs].
 */
data class MixDocumentDTO(
    val version: Int,
    val durationMs: Int,
    val a: MixSideADTO,
    val b: MixSideBDTO,
    val lanes: Map<String, List<MixLanePointDTO>>? = null
)

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
