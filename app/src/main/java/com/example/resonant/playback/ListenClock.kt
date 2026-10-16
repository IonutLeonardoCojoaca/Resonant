package com.example.resonant.playback

/**
 * Tiempo realmente escuchado de la canción actual para el stream que se reporta:
 * descuenta el tiempo en pausa y nunca supera la duración de la canción.
 */
class ListenClock {
    private var pausedTotalMs = 0L
    private var pausedSinceMs = 0L

    /** La canción actual empieza a contar en [nowMs]; si no está sonando, arranca en pausa. */
    fun restart(nowMs: Long, playing: Boolean) {
        pausedTotalMs = 0L
        pausedSinceMs = if (playing) 0L else nowMs
    }

    fun onPlayingChanged(playing: Boolean, nowMs: Long) {
        if (playing) {
            if (pausedSinceMs > 0L) {
                pausedTotalMs += (nowMs - pausedSinceMs).coerceAtLeast(0L)
                pausedSinceMs = 0L
            }
        } else if (pausedSinceMs == 0L) {
            pausedSinceMs = nowMs
        }
    }

    /** Segundos escuchados desde [startMs]; [songDurationSeconds] <= 0 = duración desconocida. */
    fun listenedSeconds(startMs: Long, nowMs: Long, songDurationSeconds: Int): Int {
        val openPauseMs = if (pausedSinceMs > 0L) (nowMs - pausedSinceMs).coerceAtLeast(0L) else 0L
        val listenedMs = (nowMs - startMs - pausedTotalMs - openPauseMs).coerceAtLeast(0L)
        val seconds = (listenedMs / 1000).toInt()
        return if (songDurationSeconds > 0) seconds.coerceAtMost(songDurationSeconds) else seconds
    }
}
