package com.example.resonant.playback

import android.os.Bundle

/**
 * Contrato interno de las operaciones de reproducción del servicio.
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
