package com.example.resonant.playback

// PlaybackPosition.kt
data class PlaybackPosition(
    val position: Long,
    /** Duración que se muestra (en Playmix, acotada al punto de entrega de la mezcla). */
    val duration: Int,
    /** Duración real del audio; igual a [duration] salvo en una canción de Playmix con límites. */
    val mediaDurationMs: Int = duration
)