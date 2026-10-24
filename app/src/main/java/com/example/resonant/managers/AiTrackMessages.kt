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
        "AI_TRACK_MONTHLY_LIMIT" -> "Has agotado el cupo de este motor este mes. Elige otro o cambia el cupo."
        "AI_TRACK_INVALID_REFERENCE" -> "Una de las canciones de referencia ya no existe."
        "AI_TRACK_INVALID" -> forField(error.field)
        "AI_TRACK_NOT_RETRYABLE" -> "Esta pista ya se está generando."
        "AI_TRACK_NOT_FOUND" -> "Esa pista ya no existe."
        "FEATURE_DISABLED" -> "La creación de pistas con IA no está disponible."
        "RATE_LIMITED" -> "Demasiadas peticiones seguidas. Espera un momento."
        else -> "No se pudo completar. Inténtalo de nuevo."
    }

    /** Nombre corto de cada etapa del proceso, en orden. */
    val stageOrder = listOf("queued", "composing", "rendering", "mastering", "cover", "publishing")

    fun forStage(stage: String): String = when (stage) {
        "queued" -> "En cola"
        "composing" -> "Componiendo"
        "rendering" -> "Generando el audio"
        "mastering" -> "Masterizando"
        "cover" -> "Creando la portada"
        "publishing" -> "Publicando"
        "completed" -> "Lista"
        "failed" -> "Ha fallado"
        else -> stage
    }

    /** Explicación de lo que está pasando en cada etapa, para la pantalla de generación. */
    fun detailForStage(stage: String, engine: String): String = when (stage) {
        "queued" -> "Tu pista está en la cola. Se genera una cada vez en el servidor."
        "composing" -> "Preparando la sesión con Lyria a partir de tu estilo, tempo y tonalidad."
        "rendering" -> if (engine == "realtime") {
            "Lyria toca la pista en tiempo real: tarda más o menos lo que dura."
        } else {
            "Lyria está componiendo la pista completa. Suele tardar menos de un minuto."
        }
        "mastering" -> "Ajustando volumen y fundidos, y codificando a alta calidad."
        "cover" -> "Pintando una portada única para tu pista."
        "publishing" -> "Guardándola en tu catálogo para que suene como cualquier otra."
        "completed" -> "¡Lista! Ya puedes escucharla, añadirla a una playlist o usarla en PlayMix."
        else -> ""
    }

    /** "Quedan 7 de 10 este mes", "Sin límite" o "Cupo agotado este mes". */
    fun quotaLine(remaining: Int?, monthlyLimit: Int?): String = when {
        monthlyLimit == null -> "Sin límite"
        remaining == 0 -> "Cupo agotado este mes"
        else -> "Quedan ${remaining ?: monthlyLimit} de $monthlyLimit este mes"
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
