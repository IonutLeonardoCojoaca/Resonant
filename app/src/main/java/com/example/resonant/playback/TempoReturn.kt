package com.example.resonant.playback

import kotlin.math.abs

/**
 * Vuelta de B a su tempo original después de una mezcla de Playmix con sincronía ampliada
 * (playmix-extended-beatmatch D6, spec `playmix-tempo-sync`).
 *
 * Durante la mezcla B suena a `bpmA / bpmB` (hasta ±12 %). Al terminar, en lugar de saltar a 1,0, la velocidad baja
 * (o sube) a 1,0 con una curva `smoothstep` durante [TEMPO_RETURN_MS], sin cambiar el tono (Sonic de ExoPlayer).
 * Cualquier acción del usuario o cambio de canción la corta con velocidad 1,0 inmediata ([Driver.cancel]).
 *
 * La parte pura ([speedAt]) y el [Driver] no dependen de Android: el servicio le da el reproductor ([Target]), el
 * temporizador del hilo principal ([Scheduler]) y el reloj (`SystemClock.uptimeMillis`).
 */
object TempoReturn {

    /** Duración de la rampa de vuelta. Se ajusta escuchando (p. ej. Mi Gente → Gasolina). */
    const val TEMPO_RETURN_MS = 6_000L

    /** Cada cuánto se actualiza la velocidad durante la rampa. */
    const val TICK_MS = 50L

    /** Diferencias de velocidad menores que esta no se aplican (evita llamadas a Sonic sin efecto). */
    private const val MIN_SPEED_STEP = 0.0005

    /**
     * Velocidad de B a los [elapsedMs] de empezar la rampa: [fromRate] al inicio, 1,0 al llegar a [durationMs] y
     * después; en medio, `smoothstep` (sin saltos de pendiente al empezar ni al acabar). Monótona.
     */
    fun speedAt(elapsedMs: Long, fromRate: Double, durationMs: Long = TEMPO_RETURN_MS): Double {
        if (durationMs <= 0L || elapsedMs >= durationMs) return 1.0
        if (elapsedMs <= 0L) return fromRate
        val x = elapsedMs.toDouble() / durationMs
        val s = x * x * (3.0 - 2.0 * x)
        return fromRate + (1.0 - fromRate) * s
    }

    /** Reproductor al que se aplica la velocidad (el principal tras la mezcla). */
    interface Target {
        fun setSpeed(speed: Float)
    }

    /** Temporizador del hilo principal (`Handler`). */
    interface Scheduler {
        fun postDelayed(task: Runnable, delayMs: Long)
        fun remove(task: Runnable)
    }

    /**
     * Ejecuta una rampa cada vez. [start] arranca una nueva (cancelando la anterior); [cancel] deja la velocidad a 1,0
     * y detiene el tick; al terminar la rampa queda a 1,0 exacto. Solo se usa desde el hilo principal.
     */
    class Driver(
        private val scheduler: Scheduler,
        private val clock: () -> Long,
        private val log: (String) -> Unit = {}
    ) {
        private var target: Target? = null
        private var fromRate = 1.0
        private var durationMs = TEMPO_RETURN_MS
        private var startedAt = 0L
        private var lastSpeed = 1.0

        /** Identificador de lo que suena durante la rampa (p. ej. el `mediaId` de B); null sin rampa. */
        var songId: String? = null
            private set

        val isActive: Boolean get() = target != null

        private val tick = object : Runnable {
            override fun run() {
                val current = target ?: return
                val elapsed = clock() - startedAt
                val speed = speedAt(elapsed, fromRate, durationMs)
                if (elapsed >= durationMs) {
                    apply(current, 1.0, force = true)
                    log("Vuelta al tempo terminada (${durationMs} ms)")
                    clear()
                    return
                }
                apply(current, speed, force = false)
                scheduler.postDelayed(this, TICK_MS)
            }
        }

        fun start(target: Target, fromRate: Double, songId: String?, durationMs: Long = TEMPO_RETURN_MS) {
            if (isActive) cancel("nueva rampa")
            if (!fromRate.isFinite() || fromRate <= 0.0 || abs(fromRate - 1.0) < MIN_SPEED_STEP || durationMs <= 0L) {
                runCatching { target.setSpeed(1f) }
                return
            }
            this.target = target
            this.fromRate = fromRate
            this.durationMs = durationMs
            this.songId = songId
            startedAt = clock()
            lastSpeed = Double.NaN
            apply(target, fromRate, force = true)
            scheduler.postDelayed(tick, TICK_MS)
        }

        /** Corta la rampa: velocidad 1,0 de inmediato. Sin rampa activa no hace nada. */
        fun cancel(reason: String) {
            val current = target ?: return
            scheduler.remove(tick)
            apply(current, 1.0, force = true)
            log("Vuelta al tempo cancelada: $reason")
            clear()
        }

        private fun apply(target: Target, speed: Double, force: Boolean) {
            if (!force && abs(speed - lastSpeed) < MIN_SPEED_STEP) return
            lastSpeed = speed
            runCatching { target.setSpeed(speed.toFloat()) }
        }

        private fun clear() {
            scheduler.remove(tick)
            target = null
            songId = null
            fromRate = 1.0
        }
    }
}
