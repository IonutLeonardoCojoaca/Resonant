package com.example.resonant.managers

/** Textos para el usuario de los códigos estables de `api/ai-tracks` (sin I/O, testeable). */
object AiTrackMessages {

    /** Por qué falló una generación (`errorCode` de la generación). */
    fun forGenerationError(code: String?): String = when (code) {
        "LYRIA_PROMPT_FILTERED" -> "El estilo no se ha aceptado: descríbelo con otras palabras."
        "LYRIA_QUOTA_EXCEEDED" -> "Se ha agotado la cuota del generador por hoy. Inténtalo más tarde."
        "LYRIA_AUTH_FAILED" -> "El generador no está disponible ahora mismo."
        "LYRIA_TIMEOUT" -> "Ha tardado demasiado. Reinténtalo."
        "LYRIA_EMPTY_AUDIO" -> "La pista salió vacía. Reinténtalo."
        "IMPORT_FAILED" -> "No se pudo guardar la pista. Reinténtalo."
        else -> "No se pudo generar. Reinténtalo."
    }

    /** Error de una petición (crear, sugerir, reintentar). */
    fun forRequestError(error: AiTrackException): String = when (error.code) {
        "AI_TRACK_DAILY_LIMIT" -> "Has llegado al límite de pistas de hoy. Vuelve mañana."
        "AI_TRACK_INVALID_REFERENCE" -> "Una de las canciones de referencia ya no existe."
        "AI_TRACK_INVALID" -> forField(error.field)
        "AI_TRACK_NOT_RETRYABLE" -> "Esta pista ya se está generando."
        "AI_TRACK_NOT_FOUND" -> "Esa pista ya no existe."
        "FEATURE_DISABLED" -> "La creación de pistas con IA no está disponible."
        "RATE_LIMITED" -> "Demasiadas peticiones seguidas. Espera un momento."
        else -> "No se pudo completar. Inténtalo de nuevo."
    }

    /** Texto del estado de una generación en la lista. */
    fun forStatus(status: String): String = when (status) {
        "queued" -> "En cola"
        "generating" -> "Generando…"
        "importing" -> "Guardando…"
        "completed" -> "Lista"
        "failed" -> "Ha fallado"
        else -> status
    }

    private fun forField(field: String?): String = when (field) {
        "title" -> "El título debe tener entre 1 y 80 caracteres."
        "style" -> "El estilo debe tener entre 1 y 200 caracteres."
        "description" -> "Describe la pista con entre 3 y 300 caracteres."
        "durationSec" -> "La duración debe estar entre 1 y 4 minutos."
        "bpm" -> "El BPM debe estar entre 60 y 200."
        "key" -> "No se reconoce la tonalidad (p. ej. \"A minor\" u \"8A\")."
        "referenceSongIds" -> "Puedes elegir como mucho 3 canciones de referencia."
        else -> "Revisa los datos de la pista."
    }
}
