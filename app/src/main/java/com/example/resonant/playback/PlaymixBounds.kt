package com.example.resonant.playback

/**
 * Límites de una canción de Playmix con mezcla de salida (playmix-mix-bounded-seekbar). Lógica pura.
 *
 * - [handoffMs]: punto de entrega, la posición de A en la que la interfaz pasa a la canción siguiente.
 *   Es la duración que se muestra: la barra llega a "2:45 / 2:45" justo cuando entra B (D1).
 * - [seekLimitMs]: hasta dónde se puede hacer seek, [SEEK_GUARD_MS] antes de que se dispare la
 *   preparación de la mezcla, para que siempre suene completa (D2).
 * - [mediaDurationMs]: duración real del audio (letras sin sincronizar, persistencia).
 *
 * Todas las posiciones están en milisegundos.
 */
data class PlaymixBounds(
    val handoffMs: Long,
    val seekLimitMs: Long,
    val mediaDurationMs: Long
) {
    /** Duración que se muestra en todas las superficies. */
    val displayDuration: Long get() = handoffMs

    /** Posición que se muestra: nunca supera la duración mostrada. */
    fun displayPosition(positionMs: Long): Long = positionMs.coerceIn(0L, handoffMs)

    /** Destino de un seek: por debajo del límite se aplica tal cual; por encima, se queda en el límite. */
    fun clampSeek(positionMs: Long): Long = if (positionMs > seekLimitMs) seekLimitMs else positionMs

    /** Vía de la transición que va a sonar, con los datos que fijan su punto de entrega. */
    sealed interface Route {
        /** Clip renderizado de Studio: B se oye en `aStartMs + audibleAtClipMs`. */
        data class Clip(val plan: RenderedTransition.Plan) : Route

        /** Documento de mezcla (Studio, escucha del editor o Automática): B entra en `a.startMs + b.offsetMs`. */
        data class Document(val aStartMs: Long, val bOffsetMs: Long) : Route

        /** Transición clásica de Playmix: la interfaz cambia en `exitPointMs`. */
        data class Classic(val exitPointMs: Long) : Route

        /** "Sin transición": B entra al acabar el audio de A (`a.startMs`). */
        data class NoMix(val aStartMs: Long) : Route
    }

    companion object {
        /** Margen entre el límite de seek y el arranque de la preparación de la mezcla. */
        const val SEEK_GUARD_MS = 500L

        /** Interruptor interno: `false` deja la barra y el seek como antes de este cambio. */
        const val PLAYMIX_BOUNDED_PROGRESS = true

        /** Punto de entrega de la vía elegida. */
        fun handoffFor(route: Route): Long = when (route) {
            is Route.Clip -> route.plan.aStartMs + route.plan.audibleAtClipMs
            is Route.Document -> route.aStartMs + route.bOffsetMs.coerceAtLeast(0L)
            is Route.Classic -> route.exitPointMs
            is Route.NoMix -> route.aStartMs
        }

        /**
         * Límites de la canción, o `null` si no se acota: entrega fuera de (0, duración), duración
         * desconocida o arranque de la mezcla desconocido (o tan pronto que no deja margen).
         *
         * @param triggerMs posición en la que se dispara la preparación de la mezcla.
         */
        fun of(handoffMs: Long, triggerMs: Long, mediaDurationMs: Long): PlaymixBounds? {
            if (mediaDurationMs <= 0L) return null
            if (handoffMs <= 0L || handoffMs >= mediaDurationMs) return null
            if (triggerMs <= SEEK_GUARD_MS) return null
            val limit = (triggerMs - SEEK_GUARD_MS).coerceAtMost(handoffMs)
            return PlaymixBounds(handoffMs, limit, mediaDurationMs)
        }

        /**
         * Posición publicada para la canción actual: con límites, la duración mostrada es la entrega y la
         * posición no la supera; sin límites, idéntica a la de antes. [mediaDurationMs] siempre es la real.
         */
        fun positionFor(positionMs: Long, mediaDurationMs: Int, bounds: PlaymixBounds?): PlaybackPosition {
            if (bounds == null) return PlaybackPosition(positionMs, mediaDurationMs)
            return PlaybackPosition(
                position = bounds.displayPosition(positionMs),
                duration = bounds.displayDuration.toInt(),
                mediaDurationMs = mediaDurationMs
            )
        }
    }
}
