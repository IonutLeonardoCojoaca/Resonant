package com.example.resonant.data.network

/** Petición de sugerencia de estilo (`POST api/ai-tracks/suggestions`). */
data class AiTrackSuggestionRequest(
    val description: String,
    val referenceSongIds: List<String> = emptyList()
)

/** Estilo sugerido; `source` es `aria` o `fallback` si Aria no respondió. */
data class AiTrackSuggestionDTO(
    val title: String = "",
    val style: String = "",
    val bpm: Int? = null,
    val key: String? = null,
    val source: String = ""
)

/** Petición de generación (`POST api/ai-tracks`). Solo instrumental. */
data class AiTrackCreateRequest(
    val title: String,
    val style: String,
    val durationSec: Int,
    val bpm: Int? = null,
    val key: String? = null,
    val description: String? = null
)

/**
 * Generación de una pista con IA. `status`: queued, generating, importing, completed o failed.
 * `songId`, `durationMs` e `imageUrl` llegan al publicarse la canción.
 */
data class AiTrackDTO(
    val id: String = "",
    val status: String = "",
    val errorCode: String? = null,
    val engine: String = "",
    val title: String = "",
    val style: String = "",
    val durationSec: Int = 0,
    val bpm: Int? = null,
    val key: String? = null,
    val attempt: Int = 1,
    val createdAt: String? = null,
    val completedAt: String? = null,
    val songId: String? = null,
    val durationMs: Int? = null,
    val imageUrl: String? = null
) {
    val isOpen: Boolean get() = status == STATUS_QUEUED || status == STATUS_GENERATING || status == STATUS_IMPORTING
    val isFailed: Boolean get() = status == STATUS_FAILED

    companion object {
        const val STATUS_QUEUED = "queued"
        const val STATUS_GENERATING = "generating"
        const val STATUS_IMPORTING = "importing"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
    }
}

data class AiTrackPageDTO(
    val items: List<AiTrackDTO> = emptyList(),
    val page: Int = 1,
    val pageSize: Int = 20,
    val total: Int = 0
)

/** Cuerpo de error de `api/ai-tracks`: `code` estable, `field` del campo inválido y `resetsAtUtc` del límite diario. */
data class AiTrackErrorDTO(
    val code: String? = null,
    val message: String? = null,
    val field: String? = null,
    val resetsAtUtc: String? = null
)
