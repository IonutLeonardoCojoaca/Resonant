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
 * La curva se aplica a escalones, uno por compás de B en su downbeat (sin rejilla, cada [FALLBACK_STEP_MS]): cada
 * cambio de `playbackParameters` hace que `DefaultAudioSink` vacíe y reinicie Sonic, y deja una pequeña costura en la
 * onda. Un cambio cada tick (~120 en la rampa) sonaba a petardeo; con la rampa larga son ~6 escalones de ≤2 % sobre
 * el golpe más fuerte, que no se oyen (ni el escalón de tempo ni la costura).
 *
 * La parte pura ([speedAt]) y el [Driver] no dependen de Android: el servicio le da el reproductor ([Target]), el
 * temporizador del hilo principal ([Scheduler]) y el reloj (`SystemClock.uptimeMillis`).
 */
object TempoReturn {

    /** Duración de la rampa de vuelta. Larga: menos escalones por cambio de tempo (p. ej. Mi Gente → Gasolina). */
    const val TEMPO_RETURN_MS = 16_000L

    /** Cada cuánto se comprueba si toca un escalón (el tick no cambia la velocidad por sí solo). */
    const val TICK_MS = 50L

    /** Sin rejilla de tempo de B: un escalón cada este tiempo. */
    const val FALLBACK_STEP_MS = 2_000L

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

        /**
         * Posición de medio de B (ms) en la que se aplicaría ahora un cambio de velocidad (lo que se está escribiendo,
         * por delante de lo que suena), para que la costura caiga en un beat; null si no se conoce.
         */
        fun positionMs(): Long? = null
    }

    /** Índice del beat de [grid] en el que está [positionMs]. */
    fun beatIndex(positionMs: Long, grid: PhaseAlignment.Grid): Long =
        kotlin.math.floor((positionMs - grid.firstDownbeatMs) / (60_000.0 / grid.bpm)).toLong()

    /** Índice del compás de [grid] en el que está [positionMs] (los escalones se dan al cambiar, en el downbeat). */
    fun barIndex(positionMs: Long, grid: PhaseAlignment.Grid): Long =
        Math.floorDiv(beatIndex(positionMs, grid), grid.beatsPerBar.toLong())

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
        private var grid: PhaseAlignment.Grid? = null
        /** Último compás (o hueco de [FALLBACK_STEP_MS]) en el que se dio un escalón. */
        private var lastStep = 0L

        /** Identificador de lo que suena durante la rampa (p. ej. el `mediaId` de B); null sin rampa. */
        var songId: String? = null
            private set

        val isActive: Boolean get() = target != null

        private val tick = object : Runnable {
            override fun run() {
                val current = target ?: return
                val elapsed = clock() - startedAt
                val stepDue = stepDue(current, elapsed)
                // El 1,0 final también espera a un downbeat (como mucho un compás más, por si la posición no avanza).
                val finishBy = durationMs + (grid?.let { PhaseAlignment.barMs(it).toLong() } ?: 0L)
                if (elapsed >= durationMs && (stepDue || elapsed >= finishBy)) {
                    apply(current, 1.0, force = true)
                    log("Vuelta al tempo terminada (${durationMs} ms)")
                    clear()
                    return
                }
                if (stepDue && elapsed < durationMs) {
                    val speed = speedAt(elapsed, fromRate, durationMs)
                    apply(current, speed, force = false)
                    log("Escalón ${"%.4f".format(speed)} a ${elapsed} ms (compás $lastStep)")
                }
                scheduler.postDelayed(this, TICK_MS)
            }
        }

        fun start(
            target: Target,
            fromRate: Double,
            songId: String?,
            durationMs: Long = TEMPO_RETURN_MS,
            grid: PhaseAlignment.Grid? = null
        ) {
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
            this.grid = grid
            lastStep = stepKey(target, 0L)
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

        /** Compás de B en curso, o el hueco de [FALLBACK_STEP_MS] sin rejilla o sin posición. */
        private fun stepKey(target: Target, elapsed: Long): Long {
            val g = grid
            val position = if (g != null) runCatching { target.positionMs() }.getOrNull() else null
            return if (g != null && position != null) barIndex(position, g) else elapsed / FALLBACK_STEP_MS
        }

        /** `true` (y lo anota) si desde el último escalón se ha cruzado un downbeat. */
        private fun stepDue(target: Target, elapsed: Long): Boolean {
            val key = stepKey(target, elapsed)
            if (key == lastStep) return false
            lastStep = key
            return true
        }

        private fun clear() {
            scheduler.remove(tick)
            grid = null
            target = null
            songId = null
            fromRate = 1.0
        }
    }
}
