package com.example.resonant.playback.dsp

import com.example.resonant.data.network.MixDocumentDTO
import com.example.resonant.data.network.MixLanePointDTO
import com.example.resonant.playback.MixLanes
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * DSP de un documento de mezcla (spotify-style-transition-engine D2). Port del render del servidor
 * (`Resonant.AI/resonant_ai/audio/mix_render.py`, `mix_v2` y `mix`): mismo orden de procesado, mismos bloques
 * de coeficientes y mismo suavizado, comprobado con los fixtures de `tests/fixtures/mix_v2_dsp`. Si cambia
 * allí, cambia aquí.
 *
 * Trabaja sobre la línea de tiempo del documento (frame 0 = `a.startMs` de A) con audio estéreo intercalado
 * en float. [process] admite bloques de cualquier tamaño y en orden: el estado (filtros, suavizado, eco,
 * bucle) pasa de un bloque al siguiente. Lógica pura: sin Android ni hilos.
 *
 * - v2: volumen con taper de potencia constante, compensación de solape, EQ, filtro bipolar, ruido, bucle y eco.
 * - v1: ganancia lineal sin compensación y EQ (como `mix` del render).
 */
class MixDsp(private val doc: MixDocumentDTO, noiseSeed: Long = 0L) {

    val totalFrames: Int = frames(doc.durationMs.toDouble())
    private val bOffsetFrames = frames(doc.b.offsetMs.toDouble())
    private val isV2 = doc.isV2

    private val eqA = EqChain('a', 0)
    private val eqB = EqChain('b', bOffsetFrames)
    private val filterA = BipolarFilter()
    private val filterB = BipolarFilter()
    private val noise = if (lane("a.noise").isNotEmpty()) NoiseRiser(noiseSeed) else null
    private val echoes = doc.fx.orEmpty().filter { it.type == "echo" }.map { EchoLine(it.fromMs, it.toMs, it.delayMs) }
    private val loop = doc.loop?.let { LoopState(frames(it.atMs.toDouble()), frames(it.lengthMs.toDouble())) }

    private val volumeA = Smoother(value("a.volume", 0.0))
    private val volumeB = Smoother(value("b.volume", 0.0))
    private val filterValueA = Smoother(value("a.filter", 0.0))
    private val filterValueB = Smoother(value("b.filter", 0.0))
    private val noiseValue = Smoother(value("a.noise", 0.0))

    /** Valores suavizados del bloque de [FILTER_BLOCK] frames en curso (se calculan al entrar en él). */
    private var smoothedBlock = -1L
    private val blockVolumeA = DoubleArray(FILTER_BLOCK)
    private val blockVolumeB = DoubleArray(FILTER_BLOCK)
    private val blockFilterA = DoubleArray(FILTER_BLOCK)
    private val blockFilterB = DoubleArray(FILTER_BLOCK)
    private val blockNoise = DoubleArray(FILTER_BLOCK)

    /**
     * Mezcla [frameCount] frames que empiezan en [startFrame] de la línea de tiempo. [a] y [b] son las
     * entradas (estéreo intercalado; B ya alineada: ceros antes de `b.offsetMs`) y [out] la salida.
     */
    fun process(a: FloatArray, b: FloatArray, out: FloatArray, frameCount: Int, startFrame: Long) {
        for (k in 0 until frameCount) {
            val i = startFrame + k
            val base = 2 * k
            if (i >= totalFrames) {
                out[base] = b[base]
                out[base + 1] = b[base + 1]
                continue
            }
            ensureBlock(i)
            val slot = (i % FILTER_BLOCK).toInt()
            var aL = a[base]
            var aR = a[base + 1]
            if (loop != null) {
                loop.next(i, aL, aR)
                aL = loop.l
                aR = loop.r
            }
            // EQ y filtro en double y vuelta a float, como el render.
            eqA.process(i, aL.toDouble(), aR.toDouble())
            eqB.process(i, b[base].toDouble(), b[base + 1].toDouble())
            var outAL: Float
            var outAR: Float
            val outBL: Float
            val outBR: Float
            if (isV2) {
                filterA.process(i, blockFilterA, eqA.l.toFloat().toDouble(), eqA.r.toFloat().toDouble())
                filterB.process(i, blockFilterB, eqB.l.toFloat().toDouble(), eqB.r.toFloat().toDouble())
                outAL = filterA.l.toFloat(); outAR = filterA.r.toFloat()
                outBL = filterB.l.toFloat(); outBR = filterB.r.toFloat()
            } else {
                outAL = eqA.l.toFloat(); outAR = eqA.r.toFloat()
                outBL = eqB.l.toFloat(); outBR = eqB.r.toFloat()
            }
            if (noise != null) {
                noise.next(i, blockNoise[slot])
                outAL += noise.l
                outAR += noise.r
            }

            val gainA: Float
            val gainB: Float
            if (isV2) {
                val gA = volumeGain(blockVolumeA[slot])
                val gB = volumeGain(blockVolumeB[slot])
                val compensation = overlapCompensation(gA, gB)
                gainA = (gA * compensation).toFloat()
                gainB = (gB * compensation).toFloat()
            } else {
                val t = i * 1000.0 / SAMPLE_RATE
                gainA = MixLanes.evaluate(lane("a.gain"), t, 1.0).toFloat()
                gainB = MixLanes.evaluate(lane("b.gain"), t, 1.0).toFloat()
            }
            var mixAL = outAL * gainA
            var mixAR = outAR * gainA
            for (e in echoes.indices) {
                val echo = echoes[e]
                echo.process(i, mixAL, mixAR)
                mixAL += echo.l
                mixAR += echo.r
            }
            out[base] = softLimit(mixAL + outBL * gainB)
            out[base + 1] = softLimit(mixAR + outBR * gainB)
        }
    }

    /** Ganancia del documento para la interfaz o los registros: v2 con taper y compensación; v1 lineal. */
    fun gainAt(side: Char, timelineMs: Double): Double {
        if (!isV2) return MixLanes.evaluate(lane("$side.gain"), timelineMs, 1.0)
        val gA = volumeGain(value("a.volume", timelineMs))
        val gB = volumeGain(value("b.volume", timelineMs))
        return (if (side == 'a') gA else gB) * overlapCompensation(gA, gB)
    }

    private fun ensureBlock(frame: Long) {
        val block = frame / FILTER_BLOCK
        if (block == smoothedBlock) return
        // Bloques consecutivos: el suavizado avanza muestra a muestra igual que en el render.
        var b = if (smoothedBlock < 0) 0L else smoothedBlock + 1
        while (b <= block) {
            for (s in 0 until FILTER_BLOCK) {
                val t = timelineMs(b * FILTER_BLOCK + s)
                blockVolumeA[s] = volumeA.next(value("a.volume", t))
                blockVolumeB[s] = volumeB.next(value("b.volume", t))
                blockFilterA[s] = filterValueA.next(value("a.filter", t))
                blockFilterB[s] = filterValueB.next(value("b.filter", t))
                blockNoise[s] = noiseValue.next(value("a.noise", t))
            }
            b++
        }
        smoothedBlock = block
    }

    /** Fuera de la línea de tiempo los carriles valen lo de sus extremos. */
    private fun timelineMs(frame: Long): Double =
        (frame * 1000.0 / SAMPLE_RATE).coerceIn(0.0, doc.durationMs.toDouble())

    private fun lane(name: String): List<MixLanePointDTO> = doc.lanes?.get(name).orEmpty()

    private fun value(name: String, t: Double): Double = MixLanes.evaluate(lane(name), t, MixLanes.defaultValue(name))

    // ─── EQ: lowshelf 250 Hz, peaking 1 kHz, highshelf 4 kHz por bloques de 512 frames ──────────

    private inner class EqChain(side: Char, private val start: Int) {
        private val bands = BANDS.mapNotNull { (band, spec) ->
            val points = lane("$side.eq.$band")
            if (points.isEmpty()) null else EqBand(spec, points)
        }
        private var block = -1L
        var l = 0.0
        var r = 0.0

        /** Deja la salida en [l] y [r]. */
        fun process(frame: Long, left: Double, right: Double) {
            l = left
            r = right
            if (bands.isEmpty() || frame < start || frame >= totalFrames) return
            val blockIndex = (frame - start) / EQ_BLOCK
            if (blockIndex != block) {
                block = blockIndex
                val blockStart = start + blockIndex * EQ_BLOCK
                val blockEnd = min(totalFrames.toLong(), blockStart + EQ_BLOCK)
                val centerMs = ((blockStart + blockEnd) / 2.0) * 1000.0 / SAMPLE_RATE
                for (band in bands) band.filter.setCoefficients(band.coefficients(centerMs))
            }
            for (k in bands.indices) {
                val filter = bands[k].filter
                l = filter.left(l)
                r = filter.right(r)
            }
        }
    }

    private class EqBand(val spec: EqSpec, val points: List<MixLanePointDTO>) {
        val filter = StereoBiquad()
        fun coefficients(centerMs: Double): DoubleArray =
            shelfOrPeak(spec, MixLanes.evaluate(points, centerMs, 0.0))
    }

    // ─── Filtro bipolar: dos biquads (Butterworth de 4.º orden), coeficientes cada 32 frames ────

    private class BipolarFilter {
        private var kind = Kind.OFF
        private var stages = Array(BUTTERWORTH_Q.size) { StereoBiquad() }
        private var block = -1L
        var l = 0.0
        var r = 0.0

        /** Deja la salida en [l] y [r]. */
        fun process(frame: Long, values: DoubleArray, left: Double, right: Double) {
            l = left
            r = right
            val blockIndex = frame / FILTER_BLOCK
            if (blockIndex != block) {
                block = blockIndex
                val (newKind, freq) = cutoff(values[(FILTER_BLOCK - 1) / 2])
                if (newKind != kind) {
                    stages = Array(BUTTERWORTH_Q.size) { StereoBiquad() }
                    kind = newKind
                }
                if (kind != Kind.OFF) stages.forEachIndexed { index, stage -> stage.setCoefficients(pass(kind, freq, BUTTERWORTH_Q[index])) }
            }
            if (kind == Kind.OFF) return
            for (k in stages.indices) {
                l = stages[k].left(l)
                r = stages[k].right(r)
            }
        }
    }

    // ─── Ruido ──────────────────────────────────────────────────────────────────────────────────

    private class NoiseRiser(seed: Long) {
        private val random = Random(seed)
        private val filter = StereoBiquad()
        private var block = -1L
        private var level = 0.0
        var l = 0f
        var r = 0f

        /** Deja el ruido de este frame en [l] y [r]. */
        fun next(frame: Long, smoothedLevel: Double) {
            val blockIndex = frame / FILTER_BLOCK
            if (blockIndex != block) {
                block = blockIndex
                level = smoothedLevel
                filter.setCoefficients(pass(Kind.HIGHPASS, NOISE_HPF_HZ * NOISE_HPF_SPAN.pow(level), BUTTERWORTH_Q[0]))
            }
            val gain = smoothedLevel * NOISE_LEVEL
            l = (filter.left(random.nextGaussian() * 0.25) * gain).toFloat()
            r = (filter.right(random.nextGaussian() * 0.25) * gain).toFloat()
        }
    }

    // ─── Eco: y[n] = x[n] + fb·y[n − D]; la salida húmeda se suma tras la ganancia de A ─────────

    private class EchoLine(fromMs: Int, toMs: Int, delayMs: Int) {
        private val delay = max(1, frames(delayMs.toDouble()))
        private val openFrom = frames(fromMs.toDouble())
        private val openTo = frames(max(fromMs.toDouble(), toMs - ECHO_CLOSE_BEATS * delayMs / 0.75))
        private val left = DoubleArray(delay)
        private val right = DoubleArray(delay)
        private var pos = 0
        var l = 0f
        var r = 0f

        /** Deja la salida húmeda de este frame en [l] y [r]. */
        fun process(frame: Long, dryL: Float, dryR: Float) {
            val open = frame in openFrom until openTo
            val feedL = if (open) dryL.toDouble() else 0.0
            val feedR = if (open) dryR.toDouble() else 0.0
            val delayedL = left[pos]
            val delayedR = right[pos]
            left[pos] = feedL + ECHO_FEEDBACK * delayedL
            right[pos] = feedR + ECHO_FEEDBACK * delayedR
            pos = (pos + 1) % delay
            l = (delayedL * ECHO_WET).toFloat()
            r = (delayedR * ECHO_WET).toFloat()
        }
    }

    // ─── Bucle: desde `start`, A repite `length` frames con un fundido corto en cada vuelta ──────

    private class LoopState(private val start: Int, private val length: Int) {
        private val seam = min(frames(LOOP_SEAM_MS), length / 2)
        private val captured = FloatArray(2 * (length + seam))
        var l = 0f
        var r = 0f

        /** Deja en [l] y [r] el frame de A que suena (el natural o el del bucle). */
        fun next(frame: Long, left: Float, right: Float) {
            l = left
            r = right
            if (length <= 0 || frame < start) return
            val offset = (frame - start).toInt()
            if (offset < length + seam) {
                captured[2 * offset] = left
                captured[2 * offset + 1] = right
            }
            if (offset < length) return
            val phase = offset % length
            l = captured[2 * phase]
            r = captured[2 * phase + 1]
            if (phase < seam) {
                val fade = phase.toFloat() / seam
                l = l * fade + captured[2 * (length + phase)] * (1 - fade)
                r = r * fade + captured[2 * (length + phase) + 1] * (1 - fade)
            }
        }
    }

    // ─── Suavizado de un polo (10 ms) ───────────────────────────────────────────────────────────

    private class Smoother(initial: Double) {
        private var state = initial
        fun next(target: Double): Double {
            state = (1 - SMOOTH_COEF) * target + SMOOTH_COEF * state
            return state
        }
    }

    enum class Kind { OFF, LOWPASS, HIGHPASS }

    data class EqSpec(val kind: String, val freq: Double, val q: Double?)

    companion object {
        const val SAMPLE_RATE = 44_100
        const val EQ_BLOCK = 512
        const val FILTER_BLOCK = 32
        const val SMOOTH_MS = 10.0
        val SMOOTH_COEF = exp(-1000.0 / (SMOOTH_MS * SAMPLE_RATE))
        const val LPF_MAX_HZ = 20_000.0
        const val LPF_MIN_HZ = 120.0
        const val HPF_MIN_HZ = 20.0
        const val HPF_MAX_HZ = 8_000.0
        val BUTTERWORTH_Q = doubleArrayOf(0.5412, 1.3066)
        const val FILTER_OFF = 1e-3
        val NOISE_LEVEL = 10.0.pow(-12.0 / 20)
        const val NOISE_HPF_HZ = 500.0
        const val NOISE_HPF_SPAN = 16.0
        const val ECHO_FEEDBACK = 0.45
        const val ECHO_WET = 0.35
        const val ECHO_CLOSE_BEATS = 4
        const val LOOP_SEAM_MS = 5.0
        val LIMIT_THRESHOLD = 10.0.pow(-1.0 / 20)

        val BANDS = listOf(
            "low" to EqSpec("lowshelf", 250.0, null),
            "mid" to EqSpec("peaking", 1000.0, 0.7),
            "high" to EqSpec("highshelf", 4000.0, null)
        )

        /** Frames de una duración en ms; redondeo al par como `round` de Python. */
        fun frames(ms: Double): Int = round(ms * SAMPLE_RATE / 1000.0).toInt()

        /** Taper de potencia constante: ganancia = sen(π·v/2). */
        fun volumeGain(fader: Double): Double = sin(fader.coerceIn(0.0, 1.0) * PI / 2)

        /** 1 / sqrt(max(1, gA² + gB²)): con las dos al 100 % baja cada una 3 dB. */
        fun overlapCompensation(gainA: Double, gainB: Double): Double = 1.0 / sqrt(max(1.0, gainA * gainA + gainB * gainB))

        /** Tipo y corte del filtro bipolar: v < 0 paso bajo, v > 0 paso alto, en escala logarítmica. */
        fun cutoff(value: Double): Pair<Kind, Double> = when {
            value <= -FILTER_OFF -> Kind.LOWPASS to LPF_MAX_HZ * (LPF_MIN_HZ / LPF_MAX_HZ).pow(min(1.0, -value))
            value >= FILTER_OFF -> Kind.HIGHPASS to HPF_MIN_HZ * (HPF_MAX_HZ / HPF_MIN_HZ).pow(min(1.0, value))
            else -> Kind.OFF to 0.0
        }

        /** Por debajo de −1 dBFS no cambia nada; por encima comprime con tanh sin llegar a 0 dBFS. */
        fun softLimit(x: Float): Float {
            val magnitude = abs(x.toDouble())
            if (magnitude <= LIMIT_THRESHOLD) return x
            val headroom = 1.0 - LIMIT_THRESHOLD
            return (sign(x.toDouble()) * (LIMIT_THRESHOLD + headroom * tanh((magnitude - LIMIT_THRESHOLD) / headroom))).toFloat()
        }

        /** Paso bajo o paso alto RBJ normalizado: [b0, b1, b2, a1, a2]. */
        fun pass(kind: Kind, freq: Double, q: Double): DoubleArray {
            val w0 = 2 * PI * min(freq, SAMPLE_RATE * 0.49) / SAMPLE_RATE
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2 * q)
            val b = if (kind == Kind.LOWPASS) doubleArrayOf((1 - cosW0) / 2, 1 - cosW0, (1 - cosW0) / 2)
            else doubleArrayOf((1 + cosW0) / 2, -(1 + cosW0), (1 + cosW0) / 2)
            val a0 = 1 + alpha
            return doubleArrayOf(b[0] / a0, b[1] / a0, b[2] / a0, -2 * cosW0 / a0, (1 - alpha) / a0)
        }

        /** Lowshelf, peaking o highshelf RBJ como los `BiquadFilterNode` de Web Audio: [b0, b1, b2, a1, a2]. */
        fun shelfOrPeak(spec: EqSpec, gainDb: Double): DoubleArray {
            val bigA = 10.0.pow(gainDb / 40.0)
            val w0 = 2 * PI * spec.freq / SAMPLE_RATE
            val cosW0 = cos(w0)
            val b: DoubleArray
            val a: DoubleArray
            if (spec.kind == "peaking") {
                val alpha = sin(w0) / (2 * spec.q!!)
                b = doubleArrayOf(1 + alpha * bigA, -2 * cosW0, 1 - alpha * bigA)
                a = doubleArrayOf(1 + alpha / bigA, -2 * cosW0, 1 - alpha / bigA)
            } else {
                val alpha = sin(w0) / 2 * sqrt(2.0)
                val twoSqrtAAlpha = 2 * sqrt(bigA) * alpha
                if (spec.kind == "lowshelf") {
                    b = doubleArrayOf(
                        bigA * ((bigA + 1) - (bigA - 1) * cosW0 + twoSqrtAAlpha),
                        2 * bigA * ((bigA - 1) - (bigA + 1) * cosW0),
                        bigA * ((bigA + 1) - (bigA - 1) * cosW0 - twoSqrtAAlpha))
                    a = doubleArrayOf(
                        (bigA + 1) + (bigA - 1) * cosW0 + twoSqrtAAlpha,
                        -2 * ((bigA - 1) + (bigA + 1) * cosW0),
                        (bigA + 1) + (bigA - 1) * cosW0 - twoSqrtAAlpha)
                } else {
                    b = doubleArrayOf(
                        bigA * ((bigA + 1) + (bigA - 1) * cosW0 + twoSqrtAAlpha),
                        -2 * bigA * ((bigA - 1) + (bigA + 1) * cosW0),
                        bigA * ((bigA + 1) + (bigA - 1) * cosW0 - twoSqrtAAlpha))
                    a = doubleArrayOf(
                        (bigA + 1) - (bigA - 1) * cosW0 + twoSqrtAAlpha,
                        2 * ((bigA - 1) - (bigA + 1) * cosW0),
                        (bigA + 1) - (bigA - 1) * cosW0 - twoSqrtAAlpha)
                }
            }
            return doubleArrayOf(b[0] / a[0], b[1] / a[0], b[2] / a[0], a[1] / a[0], a[2] / a[0])
        }
    }
}

/** Biquad estéreo en forma directa II transpuesta (la de `scipy.signal.lfilter`), con estado por canal. */
class StereoBiquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1L = 0.0
    private var z2L = 0.0
    private var z1R = 0.0
    private var z2R = 0.0

    /** [b0, b1, b2, a1, a2] normalizados; el estado se conserva. */
    fun setCoefficients(c: DoubleArray) {
        b0 = c[0]; b1 = c[1]; b2 = c[2]; a1 = c[3]; a2 = c[4]
    }

    fun left(x: Double): Double {
        val y = b0 * x + z1L
        z1L = b1 * x - a1 * y + z2L
        z2L = b2 * x - a2 * y
        return y
    }

    fun right(x: Double): Double {
        val y = b0 * x + z1R
        z1R = b1 * x - a1 * y + z2R
        z2R = b2 * x - a2 * y
        return y
    }
}
