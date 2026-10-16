package com.example.resonant.recap

/**
 * Estado de las historias: tarjeta actual, progreso (0..1) y pausa. Puro; el fragment lo anima con un
 * `ValueAnimator` y lo guarda el ViewModel, así girar la pantalla o ir a segundo plano no reinicia nada.
 */
class RecapStoryState(val count: Int) {
    var index: Int = 0
        private set
    var progress: Float = 0f
        private set
    var paused: Boolean = false
        private set

    val isLast: Boolean get() = index >= count - 1

    /** Milisegundos que le quedan a la tarjeta actual. */
    val remainingMs: Long get() = ((1f - progress) * CARD_DURATION_MS).toLong().coerceAtLeast(0L)

    /** Toque a la derecha: siguiente tarjeta; en la última no hace nada. */
    fun next(): Boolean {
        if (isLast) return false
        index++
        progress = 0f
        return true
    }

    /** Toque a la izquierda: tarjeta anterior; en la primera vuelve a empezarla. */
    fun previous() {
        if (index > 0) index--
        progress = 0f
    }

    /** Fin del temporizador: avanza salvo en la última, que se queda completa. */
    fun onTimerFinished() {
        if (!next()) progress = 1f
    }

    fun updateProgress(value: Float) {
        progress = value.coerceIn(0f, 1f)
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    /** El temporizador corre si hay tarjetas, no está en pausa y la tarjeta no ha terminado. */
    val shouldRun: Boolean get() = count > 0 && !paused && progress < 1f

    companion object {
        const val CARD_DURATION_MS = 6_000L
    }
}
