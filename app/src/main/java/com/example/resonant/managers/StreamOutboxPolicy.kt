package com.example.resonant.managers

import com.example.resonant.data.network.AddStreamDTO

/** Escucha pendiente de envío: el cuerpo exacto que se reintenta (mismo `clientEventId`) y cuándo terminó. */
data class PendingStream(
    val clientEventId: String,
    val userId: String,
    val songId: String,
    val listenDurationInSeconds: Int,
    val wasSkipped: Boolean,
    val playSource: String,
    val playedAtUtc: String,
    val playedAtMs: Long,
    /** Opcional; las entradas guardadas por versiones anteriores lo leen como `null`. */
    val recommendationRequestId: String? = null
) {
    fun toDto(): AddStreamDTO = AddStreamDTO(
        songId = songId,
        userId = userId,
        listenDurationInSeconds = listenDurationInSeconds,
        wasSkipped = wasSkipped,
        playSource = playSource,
        clientEventId = clientEventId,
        playedAtUtc = playedAtUtc,
        recommendationRequestId = recommendationRequestId
    )
}

/** Qué hacer con una escucha tras un intento de envío. */
enum class StreamSendOutcome {
    /** `2xx`: registrada (o reintento ya registrado). */
    SENT,
    /** Red, timeout, `5xx`, `408` o `429`: se guarda y se reintenta con el mismo `clientEventId`. */
    RETRY,
    /** Resto de `4xx` (canción borrada, cuerpo inválido…): se descarta, reintentar no la arreglaría. */
    DROP
}

/** Reglas puras de la bandeja de escuchas pendientes: tamaño, caducidad, orden y clasificación de respuestas. */
object StreamOutboxPolicy {
    const val MAX_ITEMS = 200

    /** Coincide con la ventana de `playedAtUtc` que acepta el servidor. */
    const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** [httpCode] `null` = sin respuesta (sin red, timeout, error de E/S). */
    fun classify(httpCode: Int?): StreamSendOutcome = when {
        httpCode == null -> StreamSendOutcome.RETRY
        httpCode in 200..299 -> StreamSendOutcome.SENT
        httpCode == 408 || httpCode == 429 -> StreamSendOutcome.RETRY
        httpCode in 400..499 -> StreamSendOutcome.DROP
        else -> StreamSendOutcome.RETRY
    }

    /** Añade [item] (sin duplicar por `clientEventId`) y aplica caducidad y tope. */
    fun enqueue(items: List<PendingStream>, item: PendingStream, nowMs: Long): List<PendingStream> =
        prune(items.filterNot { it.clientEventId == item.clientEventId } + item, nowMs)

    /**
     * Descarta las caducadas (más de 7 días) y, por encima de [MAX_ITEMS], las más antiguas.
     * Devuelve la bandeja de la más antigua a la más reciente (orden de envío).
     */
    fun prune(items: List<PendingStream>, nowMs: Long): List<PendingStream> =
        items
            .filter { nowMs - it.playedAtMs <= TTL_MS }
            .sortedBy { it.playedAtMs }
            .takeLast(MAX_ITEMS)

    /** Pendientes de [userId] que aún se pueden enviar, en orden de envío. */
    fun due(items: List<PendingStream>, userId: String, nowMs: Long): List<PendingStream> =
        prune(items, nowMs).filter { it.userId == userId }
}

/** Resultado de un envío de escucha: qué hacer con ella y el evento de racha si lo hubo. */
data class StreamSendResult(
    val outcome: StreamSendOutcome,
    val streakEvent: com.example.resonant.streak.StreakEvent? = null
)
