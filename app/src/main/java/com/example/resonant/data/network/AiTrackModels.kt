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

/** Petición de generación (`POST api/ai-tracks`). Solo instrumental; `engine`: realtime, clip (30 s) o song. */
data class AiTrackCreateRequest(
    val engine: String,
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
    /** queued, composing, rendering, mastering, cover, publishing, completed o failed. */
    val stage: String = "",
    /** 0-100; con motores de pago no avanza durante `rendering`. */
    val progress: Int = 0,
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

/** Motor de generación con el cupo del usuario; `monthlyLimit`/`remaining` null = sin límite. */
data class AiTrackEngineDTO(
    val engine: String = "",
    val name: String = "",
    val description: String = "",
    val paid: Boolean = false,
    val fixedDurationSec: Int? = null,
    val monthlyLimit: Int? = null,
    val usedThisMonth: Int = 0,
    val remaining: Int? = null,
    val resetsAtUtc: String? = null
) {
    val isExhausted: Boolean get() = remaining == 0

    companion object {
        const val REALTIME = "realtime"
        const val CLIP = "clip"
        const val SONG = "song"
    }
}

data class AiTrackEngineListDTO(val items: List<AiTrackEngineDTO> = emptyList())

/** Cupo mensual por usuario de un motor: 0-500, o null para sin límite (el backend trata el campo ausente como null). */
data class AiTrackEngineLimitRequest(val monthlyLimit: Int?)

data class AiTrackPageDTO(
    val items: List<AiTrackDTO> = emptyList(),
    val page: Int = 1,
    val pageSize: Int = 20,
    val total: Int = 0
)

/** Cuerpo de error de `api/ai-tracks`: `code` estable, `field` del campo inválido y `resetsAtUtc` del cupo mensual. */
data class AiTrackErrorDTO(
    val code: String? = null,
    val message: String? = null,
    val field: String? = null,
    val resetsAtUtc: String? = null
)
