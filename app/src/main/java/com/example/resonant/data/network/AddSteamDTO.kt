package com.example.resonant.data.network

/** Cuerpo de `POST api/analytics/stream`. Gson omite los campos opcionales cuando son null. */
data class AddStreamDTO(
    val songId: String,
    val userId: String,
    val listenDurationInSeconds: Int,
    val wasSkipped: Boolean,
    val playSource: String,
    /** UUID generado una vez por escucha y reutilizado en cada reintento (el servidor deduplica). */
    val clientEventId: String? = null,
    /** Instante UTC ISO-8601 en que terminó la escucha (`2026-10-15T18:42:10Z`). */
    val playedAtUtc: String? = null,
    /** `requestId` de la superficie de recomendación desde la que empezó la reproducción (opcional). */
    val recommendationRequestId: String? = null
)
