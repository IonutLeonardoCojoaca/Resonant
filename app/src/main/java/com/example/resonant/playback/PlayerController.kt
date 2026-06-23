package com.example.resonant.playback

import android.os.Bundle

/**
 * Define un contrato para controlar el reproductor de música.
 * Permite que componentes como MediaSessionManager den órdenes
 * sin saber nada sobre ExoPlayer.
 */
interface PlayerController {
    fun resume()
    fun pause()
    fun playNext()
    fun playPrevious()
    fun stop()
    fun seekTo(position: Long)
    fun playFromSearch(query: String?, extras: Bundle?) {}
}
