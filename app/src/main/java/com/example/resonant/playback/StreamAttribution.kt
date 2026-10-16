package com.example.resonant.playback

/**
 * Atribución de una entrada de la cola a la superficie de recomendación desde la que empezó a sonar.
 *
 * - [requestId]: `requestId` de la respuesta del servidor (Home "Canciones para ti", Explorar, relacionadas,
 *   lote de radio, elecciones de Aria, canción del día). Se envía como `recommendationRequestId` en
 *   `POST api/analytics/stream`. Nunca se persiste: una sesión restaurada se reporta sin él.
 * - [playSource]: valor canónico de `playSource` (ver [PlaySourceMapper]).
 */
data class StreamAttribution(
    val requestId: String?,
    val playSource: String
)
