package com.example.resonant.playback.dsp

import com.example.resonant.data.network.MixDocumentDTO
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Paridad con el render del servidor: `mix_v2_dsp/` son salidas de `Resonant.AI/resonant_ai/audio/mix_dsp_fixtures.py`
 * (mismos documentos y acordes). Se regeneran allí y se copian aquí a la vez.
 */
class MixDspTest {

    private val gson = Gson()

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("mix_v2_dsp/$name")) { "falta $name" }.readBytes()

    private fun chord(spec: List<List<Double>>, frames: Int): FloatArray {
        val out = FloatArray(2 * frames)
        for (i in 0 until frames) {
            val t = i.toDouble() / MixDsp.SAMPLE_RATE
            var v = 0.0
            for ((freq, amp) in spec.map { it[0] to it[1] }) v += amp * sin(2 * PI * freq * t)
            out[2 * i] = v.toFloat()
            out[2 * i + 1] = v.toFloat()
        }
        return out
    }

    private fun render(doc: MixDocumentDTO, a: FloatArray, b: FloatArray, blockFrames: Int): FloatArray {
        val dsp = MixDsp(doc)
        val frames = a.size / 2
        val out = FloatArray(a.size)
        var start = 0
        // Bloques irregulares: el resultado no puede depender de cómo trocee el motor.
        while (start < frames) {
            val n = minOf(blockFrames, frames - start)
            val inA = a.copyOfRange(2 * start, 2 * (start + n))
            val inB = b.copyOfRange(2 * start, 2 * (start + n))
            val block = FloatArray(2 * n)
            dsp.process(inA, inB, block, n, start.toLong())
            block.copyInto(out, 2 * start)
            start += n
        }
        return out
    }

    private fun parityDb(name: String, blockFrames: Int): Double {
        val meta = gson.fromJson(String(resource("$name.json")), JsonObject::class.java)
        val doc = gson.fromJson(meta.get("document"), MixDocumentDTO::class.java)
        val chordA = gson.fromJson(meta.get("chordA"), Array<DoubleArray>::class.java).map { it.toList() }
        val chordB = gson.fromJson(meta.get("chordB"), Array<DoubleArray>::class.java).map { it.toList() }
        val reference = ByteBuffer.wrap(resource("$name.f32")).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val frames = reference.remaining()
        val out = render(doc, chord(chordA, frames), chord(chordB, frames), blockFrames)

        var diff = 0.0
        var ref = 0.0
        for (i in 0 until frames) {
            val r = reference.get(i).toDouble()
            val d = out[2 * i] - r
            diff += d * d
            ref += r * r
        }
        return 20 * log10(sqrt(diff / frames) / sqrt(ref / frames))
    }

    @Test
    fun volumeAndEqMatchTheServerRender() = assertParity("volume-eq")

    @Test
    fun filtersMatchTheServerRender() = assertParity("filters")

    @Test
    fun loopAndEchoMatchTheServerRender() = assertParity("loop-echo")

    private fun assertParity(name: String) {
        for (block in listOf(441, 1024, 37)) {
            val db = parityDb(name, block)
            assertTrue("$name con bloques de $block: diferencia $db dB", db < -40)
        }
    }

    @Test
    fun taperAndCompensationFollowTheContract() {
        assertEquals(sqrt(0.5), MixDsp.volumeGain(0.5), 1e-9)
        assertEquals(1 / sqrt(2.0), MixDsp.overlapCompensation(1.0, 1.0), 1e-9)
        assertEquals(MixDsp.Kind.OFF, MixDsp.cutoff(0.0).first)
        assertEquals(120.0, MixDsp.cutoff(-1.0).second, 1e-6)
    }

    /** El limitador del documento (como el render) no toca nada por debajo de −1 dBFS y nunca llega a 0 dBFS. */
    @Test
    fun documentLimiterNeverReachesFullScale() {
        val ceiling = MixDsp.LIMIT_THRESHOLD
        for (x in listOf(0.5f, 0.89f, 0.95f, 1.5f, -2.0f, 4.0f)) {
            val y = MixDsp.softLimit(x)
            assertTrue(abs(y) < 1f)
            if (abs(x) <= ceiling) assertEquals(x, y, 0f)
        }
    }
}
