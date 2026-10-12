package com.example.resonant.unisound

/** Where an error happened, because `UNISOUND_NOT_ALLOWED` reads differently in each place. */
enum class UnisoundErrorContext { JOIN, COMMAND, ADD, ADD_BATCH, HOST_ACTION, GENERAL }

/** Maps stable backend codes (REST `code` or hub `HubException` message) to Spanish UI text. */
object UnisoundErrors {
    const val NOT_AVAILABLE = "UNISOUND_NOT_AVAILABLE"
    const val NOT_MEMBER = "UNISOUND_NOT_MEMBER"
    const val NOT_ALLOWED = "UNISOUND_NOT_ALLOWED"
    const val SESSION_ENDED = "UNISOUND_SESSION_ENDED"
    const val STALE_REVISION = "UNISOUND_STALE_REVISION"
    const val QUEUE_EMPTY = "UNISOUND_QUEUE_EMPTY"
    const val QUEUE_FULL = "UNISOUND_QUEUE_FULL"
    const val SONG_UNAVAILABLE = "UNISOUND_SONG_UNAVAILABLE"
    const val INVALID_REQUEST = "UNISOUND_INVALID_REQUEST"
    const val UNISOUND_RATE_LIMITED = "UNISOUND_RATE_LIMITED"
    const val UNAVAILABLE = "UNISOUND_UNAVAILABLE"
    const val CODE_INVALID = "UNISOUND_CODE_INVALID"
    const val JOIN_CLOSED = "UNISOUND_JOIN_CLOSED"
    const val KICKED = "UNISOUND_KICKED"
    const val SESSION_FULL = "UNISOUND_SESSION_FULL"
    const val ALREADY_IN_SESSION = "UNISOUND_ALREADY_IN_SESSION"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val SOURCE_NOT_FOUND = "UNISOUND_SOURCE_NOT_FOUND"
    const val NOTHING_PLAYED = "UNISOUND_NOTHING_PLAYED"

    const val MESSAGE_CONTROL_DENIED = "El anfitrión controla la reproducción"
    const val MESSAGE_RECONNECTING = "Reconectando…"
    const val MESSAGE_ROOM_ENDED = "La sala ha terminado"
    const val MESSAGE_NOT_AVAILABLE = "Escuchar juntos no está disponible ahora"
    const val MESSAGE_RETRY = "No se ha podido enviar. Inténtalo de nuevo"

    private val CODE_PATTERN = Regex("""\b(UNISOUND_[A-Z_]+|RATE_LIMITED)\b""")

    /**
     * Extracts the code from a hub or REST failure. The Java SignalR client may wrap the
     * server message ("An unexpected error occurred invoking 'Play' on the server.
     * HubException: UNISOUND_NOT_ALLOWED"), so the whole cause chain is scanned.
     */
    fun codeOf(error: Throwable?): String? {
        var current = error
        var depth = 0
        while (current != null && depth < 5) {
            current.message?.let(CODE_PATTERN::find)?.let { return it.value }
            current = current.cause
            depth++
        }
        return null
    }

    fun isRetryable(code: String?): Boolean = code == UNAVAILABLE

    /** Codes after which this client is no longer in a usable room. */
    fun endsRoom(code: String?): Boolean =
        code == NOT_MEMBER || code == SESSION_ENDED || code == NOT_AVAILABLE

    fun message(code: String?, context: UnisoundErrorContext): String = when (code) {
        CODE_INVALID -> "Código no válido"
        JOIN_CLOSED -> "La sala no admite a nadie más ahora"
        KICKED -> "No puedes volver a esta sala"
        SESSION_FULL -> "La sala está llena"
        RATE_LIMITED -> "Demasiados intentos. Espera un momento"
        UNISOUND_RATE_LIMITED -> "Vas demasiado rápido"
        NOT_ALLOWED -> when (context) {
            UnisoundErrorContext.COMMAND -> MESSAGE_CONTROL_DENIED
            UnisoundErrorContext.ADD, UnisoundErrorContext.ADD_BATCH -> "El anfitrión no permite añadir canciones"
            else -> "Solo el anfitrión puede hacerlo"
        }
        SONG_UNAVAILABLE -> if (context == UnisoundErrorContext.ADD_BATCH) {
            "No hay canciones disponibles para añadir"
        } else {
            "Esta canción no está disponible"
        }
        SOURCE_NOT_FOUND -> "No se ha encontrado ese álbum o playlist"
        NOTHING_PLAYED -> "No ha sonado ninguna canción"
        QUEUE_FULL -> "La cola de la sala está llena"
        QUEUE_EMPTY -> "La cola está vacía"
        NOT_MEMBER, SESSION_ENDED -> MESSAGE_ROOM_ENDED
        NOT_AVAILABLE -> MESSAGE_NOT_AVAILABLE
        else -> when (context) {
            UnisoundErrorContext.JOIN -> "No se ha podido unir a la sala. Inténtalo de nuevo"
            else -> MESSAGE_RETRY
        }
    }
}
