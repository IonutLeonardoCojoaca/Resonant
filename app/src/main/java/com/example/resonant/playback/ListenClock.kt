package com.example.resonant.playback

/**
 * Tiempo realmente escuchado de la canción actual para el stream que se reporta:
 * descuenta el tiempo en pausa y nunca supera la duración de la canción.
 */
class ListenClock {
    private var pausedTotalMs = 0L
    /** Inicio de la pausa en curso; null = sonando. */
    private var pausedSinceMs: Long? = null

    /** La canción actual empieza a contar en [nowMs]; si no está sonando, arranca en pausa. */
    fun restart(nowMs: Long, playing: Boolean) {
        pausedTotalMs = 0L
        pausedSinceMs = if (playing) null else nowMs
    }

    fun onPlayingChanged(playing: Boolean, nowMs: Long) {
        val pausedSince = pausedSinceMs
        if (playing) {
            if (pausedSince != null) {
                pausedTotalMs += (nowMs - pausedSince).coerceAtLeast(0L)
                pausedSinceMs = null
            }
        } else if (pausedSince == null) {
            pausedSinceMs = nowMs
        }
    }

    /** Segundos escuchados desde [startMs]; [songDurationSeconds] <= 0 = duración desconocida. */
    fun listenedSeconds(startMs: Long, nowMs: Long, songDurationSeconds: Int): Int {
        val pausedSince = pausedSinceMs
        val openPauseMs = if (pausedSince != null) (nowMs - pausedSince).coerceAtLeast(0L) else 0L
        val listenedMs = (nowMs - startMs - pausedTotalMs - openPauseMs).coerceAtLeast(0L)
        val seconds = (listenedMs / 1000).toInt()
        return if (songDurationSeconds > 0) seconds.coerceAtMost(songDurationSeconds) else seconds
    }
}
