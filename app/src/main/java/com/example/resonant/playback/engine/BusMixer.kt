package com.example.resonant.playback.engine

import com.example.resonant.playback.dsp.MixDsp
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Mezcla de un bloque del motor (lógica pura, sin Android): suma las fuentes que suenan y, si hay una
 * [MixSession], aplica el documento con [MixDsp] a la muestra exacta. El [MixOutputEngine] la llama en su hilo
 * de audio; los tests la usan con fuentes falsas.
 */
class BusMixer {
    val sources = CopyOnWriteArrayList<BusSource>()

    @Volatile var session: MixSession? = null

    @Volatile var underruns: Long = 0
        private set

    private val sourceBuffer = FloatArray(2 * BLOCK_FRAMES)
    private val aBuffer = FloatArray(2 * BLOCK_FRAMES)
    private val bBuffer = FloatArray(2 * BLOCK_FRAMES)
    private val dspOut = FloatArray(2 * BLOCK_FRAMES)
    private var limiterGain = 1.0
    private var mixBuffer = FloatArray(0)
    private var writtenFrames = 0L

    /** Mezcla [BLOCK_FRAMES] frames en [out]; [engineFrame] es el frame de salida del primero. */
    fun render(out: FloatArray, engineFrame: Long) {
        mixBuffer = out
        writtenFrames = engineFrame
        renderBlock()
        limit(out, BLOCK_FRAMES)
    }

    fun anyPlaying(): Boolean = sources.any { it.isPlaying && !it.retired }

    /** Un bloque de [BLOCK_FRAMES] frames en [mixBuffer]: fuentes normales y, si hay, la sesión de mezcla. */
    private fun renderBlock() {
        mixBuffer.fill(0f)
        val active = session?.takeIf { !it.cancelled && it.state != MixSession.State.DONE && it.state != MixSession.State.FAILED }
        for (source in sources) {
            if (!source.isPlaying || source.retired) continue
            if (active != null && (source === active.a || source === active.b)) continue
            val read = source.readInto(sourceBuffer, BLOCK_FRAMES, writtenFrames)
            if (read < BLOCK_FRAMES) noteUnderrun(source)
            addScaled(sourceBuffer, mixBuffer, 0, BLOCK_FRAMES, source.nextVolume(BLOCK_FRAMES))
        }
        if (active != null) renderSession(active)
    }

    private fun renderSession(s: MixSession) {
        var frame = 0
        while (frame < BLOCK_FRAMES) {
            val remaining = BLOCK_FRAMES - frame
            when (s.state) {
                MixSession.State.WAITING -> {
                    val untilStart = s.framesUntilStart(SAMPLE_RATE)
                    if (s.incoherent) {
                        s.fail("posición de A incoherente")
                        session = null
                        frame += playNormal(s.a, frame, remaining)
                    } else if (untilStart == null && s.a.ended) {
                        // A se acabó antes del inicio (p. ej. la espera por B lo empujó más allá de su final): sin
                        // esto la sesión esperaba para siempre y el servicio, con la transición abierta, no avanzaba.
                        s.fail("A terminó antes de empezar la mezcla")
                        session = null
                        frame += playNormal(s.a, frame, remaining)
                    } else if (untilStart == null) {
                        // A aún no tiene audio: suena lo que haya (silencio) y se vuelve a mirar en el bloque siguiente.
                        frame += playNormal(s.a, frame, remaining)
                        frame = BLOCK_FRAMES
                    } else if (untilStart >= remaining) {
                        frame += playNormal(s.a, frame, remaining)
                        s.waitedFrames += remaining
                    } else if (untilStart > 0) {
                        frame += playNormal(s.a, frame, untilStart.toInt())
                        s.waitedFrames += untilStart
                    } else if (!s.bReady() && !s.postpone(SAMPLE_RATE)) {
                        s.fail("B no tuvo audio a tiempo")
                        session = null
                        frame += playNormal(s.a, frame, remaining)
                    } else if (s.bReady()) {
                        s.start(writtenFrames + frame)
                    }
                }
                MixSession.State.RUNNING -> {
                    val n = min(remaining.toLong(), s.dsp.totalFrames - s.timelineFrame).toInt().coerceAtLeast(0)
                    if (n == 0) {
                        s.finish()
                        session = null
                        continue
                    }
                    mixTimeline(s, frame, n)
                    frame += n
                    s.reportAudibleIfNeeded()
                }
                else -> {
                    // Terminada en este bloque: B sigue como fuente normal.
                    frame += playNormal(s.b, frame, remaining)
                }
            }
        }
    }

    private fun playNormal(source: BusSource, offset: Int, frames: Int): Int {
        if (frames <= 0) return 0
        val read = source.readInto(sourceBuffer, frames, writtenFrames + offset)
        if (read < frames && !source.ended) noteUnderrun(source)
        addScaled(sourceBuffer, mixBuffer, offset, frames, source.nextVolume(frames))
        return frames
    }

    private fun mixTimeline(s: MixSession, offset: Int, frames: Int) {
        val engineFrame = writtenFrames + offset
        val readA = s.a.readInto(aBuffer, frames, engineFrame)
        if (readA < frames && !s.a.ended) noteUnderrun(s.a)
        scale(aBuffer, frames, s.a.nextVolume(frames))

        bBuffer.fill(0f, 0, 2 * frames)
        val bFrom = max(0L, s.bStartFrames - s.timelineFrame).toInt()
        if (bFrom < frames) {
            val readB = s.b.readInto(sourceBuffer, frames - bFrom, engineFrame + bFrom)
            if (readB < frames - bFrom && !s.b.ended) noteUnderrun(s.b)
            sourceBuffer.copyInto(bBuffer, 2 * bFrom, 0, 2 * (frames - bFrom))
            scale(bBuffer, frames, s.b.nextVolume(frames - bFrom), from = bFrom)
        }

        s.dsp.process(aBuffer, bBuffer, dspOut, frames, s.timelineFrame)
        for (k in 0 until 2 * frames) mixBuffer[2 * offset + k] += dspOut[k]
        s.timelineFrame += frames
    }

    private fun noteUnderrun(source: BusSource) {
        underruns++
        source.notifyUnderrun()
    }

    /** Limitador de pico sobre la suma: techo −1 dBFS, ataque 1 ms y liberación 50 ms. */
    private fun limit(buffer: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            val peak = max(abs(buffer[2 * i]), abs(buffer[2 * i + 1])).toDouble()
            val target = if (peak * limiterGain > CEILING) CEILING / peak else 1.0
            limiterGain += (target - limiterGain) * (if (target < limiterGain) ATTACK_COEF else RELEASE_COEF)
            val g = min(limiterGain, if (peak > CEILING) CEILING / peak else 1.0).toFloat()
            buffer[2 * i] *= g
            buffer[2 * i + 1] *= g
        }
    }

    companion object {
        /** ~10 ms por bloque (múltiplo de 32 para que los bloques del DSP sean regulares). */
        const val BLOCK_FRAMES = 448
        const val SAMPLE_RATE = MixDsp.SAMPLE_RATE
        /** Espera máxima a que B tenga audio al empezar la mezcla (8 s). */
        const val MAX_WAIT_FRAMES = SAMPLE_RATE * 8L
        private val CEILING = Math.pow(10.0, -1.0 / 20)
        private val ATTACK_COEF = 1 - exp(-1.0 / (0.001 * SAMPLE_RATE))
        private val RELEASE_COEF = 1 - exp(-1.0 / (0.050 * SAMPLE_RATE))

        internal fun addScaled(src: FloatArray, dst: FloatArray, offset: Int, frames: Int, gains: FloatArray) {
            for (i in 0 until frames) {
                dst[2 * (offset + i)] += src[2 * i] * gains[i]
                dst[2 * (offset + i) + 1] += src[2 * i + 1] * gains[i]
            }
        }

        internal fun scale(buffer: FloatArray, frames: Int, gains: FloatArray, from: Int = 0) {
            for (i in from until frames) {
                val g = gains[i - from]
                buffer[2 * i] *= g
                buffer[2 * i + 1] *= g
            }
        }
    }
}
