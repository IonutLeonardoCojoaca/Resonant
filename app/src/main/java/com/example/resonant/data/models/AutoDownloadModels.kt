package com.example.resonant.data.models

/**
 * Contrato de `api/me/auto-download/…` (descargas automáticas de lanzamientos nuevos).
 * Gson ignora los valores por defecto de Kotlin: los campos que pueden faltar son nullables.
 */
data class AutoDownloadSubscriptionDTO(
    val artistId: String? = null,
    val name: String? = null,
    val imageUrl: String? = null,
    val createdAtUtc: String? = null
)

data class AutoDownloadSubscriptionListDTO(
    val maxSubscriptions: Int = 0,
    val items: List<AutoDownloadSubscriptionDTO>? = null
)

data class AutoDownloadPlanDTO(
    val planId: String? = null,
    val songId: String? = null,
    val reasonArtistId: String? = null,
    val reasonArtistName: String? = null,
    val batchId: String? = null,
    /** `pending`, `completed` o `skipped`. */
    val status: String? = null,
    val resultCode: String? = null,
    val createdAtUtc: String? = null,
    /** Bytes del asset progresivo; null si el servidor no lo conoce. */
    val contentLength: Long? = null,
    /** Misma forma que `GET api/songs/{id}`. */
    val song: Song? = null
)

data class AutoDownloadPlanListDTO(
    val items: List<AutoDownloadPlanDTO>? = null,
    val hasMore: Boolean = false
)

data class AutoDownloadClaimRequest(val installationId: String)

data class AutoDownloadClaimResponseDTO(
    val planId: String? = null,
    val claimedUntilUtc: String? = null,
    val attempt: Int = 0
)

data class AutoDownloadResultRequest(
    val installationId: String,
    val result: String
)

/** Valores de `result` en `POST …/plans/{id}/result`. */
object AutoDownloadResults {
    const val COMPLETED = "completed"
    const val ALREADY_DOWNLOADED = "already_downloaded"
    const val NO_SPACE = "no_space"
    const val UNAVAILABLE = "unavailable"
    const val FAILED = "failed"
}
