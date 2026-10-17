package com.example.resonant.playback.engine

import com.example.resonant.data.network.MixDocumentDTO
import com.example.resonant.data.network.MixLanePointDTO
import com.example.resonant.data.network.MixSideADTO
import com.example.resonant.data.network.MixSideBDTO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeHost : BusHost {
    override var playedFrames: Long = 0
    var removed = 0
    override fun wake() = Unit
    override fun remove(source: BusSource) { removed++ }
}

class BusSourceTest {
    private val host = FakeHost()

    private fun frames(n: Int, value: Float) = FloatArray(2 * n) { value }

    @Test
    fun mediaTimeFollowsTheAnchorsAsFramesAreRead() {
        val source = BusSource(host, "a")
        source.write(frames(1000, 0.1f), 1000, 5_000_000L)
        assertEquals(5_000_000L, source.nextMediaTimeUs())
        source.readInto(FloatArray(2 * 441), 441, 0)
        assertEquals(5_010_000L, source.nextMediaTimeUs())
    }

    @Test
    fun playedFramesFollowTheOutputHead() {
        val source = BusSource(host, "a")
        source.write(frames(2000, 0.1f), 2000, 0L)
        source.readInto(FloatArray(2 * 448), 448, 0)
        source.readInto(FloatArray(2 * 448), 448, 448)
        host.playedFrames = 0
        assertEquals(0L, source.playedFrames())
        host.playedFrames = 600
        assertEquals(600L, source.playedFrames())
        host.playedFrames = 5000
        assertEquals(896L, source.playedFrames())
    }

    @Test
    fun flushResetsPositionAndDropsPendingAudio() {
        val source = BusSource(host, "a")
        source.write(frames(2000, 0.1f), 2000, 0L)
        source.readInto(FloatArray(2 * 448), 448, 0)
        source.flush()
        host.playedFrames = 10_000
        assertEquals(0L, source.playedFrames())
        assertEquals(0, source.availableFrames())
        assertNull(source.nextMediaTimeUs())
    }

    @Test
    fun ringWrapsAroundWithoutLosingFrames() {
        val source = BusSource(host, "a")
        val chunk = BusSource.RING_FRAMES - 100
        source.write(frames(chunk, 0.1f), chunk, 0L)
        source.readInto(FloatArray(2 * chunk), chunk, 0)
        assertEquals(500, source.write(frames(500, 0.7f), 500, 0L))
        val out = FloatArray(2 * 500)
        assertEquals(500, source.readInto(out, 500, chunk.toLong()))
        assertTrue(out.all { it == 0.7f })
    }
}

class BusMixerTest {
    private val host = FakeHost()
    private val block = BusMixer.BLOCK_FRAMES

    private fun flat(value: Double) = listOf(MixLanePointDTO(0, value, "hold"), MixLanePointDTO(1000, value, "linear"))

    private fun doc(durationMs: Int = 1000) = MixDocumentDTO(
        version = 2, durationMs = durationMs, a = MixSideADTO(10_000), b = MixSideBDTO(0, 0),
        lanes = mapOf("a.volume" to flat(1.0), "b.volume" to flat(1.0))
    )

    private class Events : MixSession.Listener {
        val log = mutableListOf<String>()
        override fun onAudible() { log += "audible" }
        override fun onCompleted() { log += "completed" }
        override fun onFailed(reason: String) { log += "failed" }
    }

    /** Fuente con [frames] frames (como mucho [BusSource.RING_FRAMES]) desde la posición de medio [startUs], ya sonando. */
    private fun source(name: String, frames: Int, startUs: Long, value: Float): BusSource {
        val s = BusSource(host, name)
        var written = 0
        while (written < frames) {
            val n = minOf(4096, frames - written)
            s.write(FloatArray(2 * n) { value }, n, startUs + written * 1_000_000L / BusMixer.SAMPLE_RATE)
            written += n
        }
        s.play()
        return s
    }

    private fun run(mixer: BusMixer, blocks: Int) {
        val out = FloatArray(2 * block)
        repeat(blocks) { mixer.render(out, it.toLong() * block) }
    }

    @Test
    fun mixStartsOnTheExactFrameWhereAReachesItsStart() {
        val mixer = BusMixer()
        // A empieza 100 ms (4410 frames) antes de a.startMs.
        val a = source("a", 20_000, 9_900_000L, 0.1f)
        val b = source("b", 20_000, 0L, 0.2f)
        mixer.sources += a; mixer.sources += b
        val events = Events()
        mixer.session = MixSession(doc(), a, b, null, events, { it.run() }, {})

        run(mixer, 20)

        host.playedFrames = Long.MAX_VALUE
        val rendered = 20L * block
        assertEquals(rendered - 4410, b.playedFrames())
        assertTrue(events.log.contains("audible"))
    }

    @Test
    fun lateStartWaitsForTheNextBarOfA() {
        val mixer = BusMixer()
        // A ya va 50 ms por detrás de a.startMs: con compás de 200 ms la mezcla empieza en a.startMs + 200 ms.
        val a = source("a", 20_000, 10_050_000L, 0.1f)
        val b = source("b", 20_000, 0L, 0.2f)
        mixer.sources += a; mixer.sources += b
        mixer.session = MixSession(doc(), a, b, 8_820, Events(), { it.run() }, {})

        run(mixer, 30)

        host.playedFrames = Long.MAX_VALUE
        val startFrame = 8_820 - 2_205
        assertEquals(30L * block - startFrame, b.playedFrames())
    }

    @Test
    fun mixCompletesAndRetiresA() {
        val mixer = BusMixer()
        val a = source("a", 20_000, 10_000_000L, 0.1f)
        val b = source("b", 60_000, 0L, 0.2f)
        mixer.sources += a; mixer.sources += b
        val events = Events()
        mixer.session = MixSession(doc(durationMs = 100), a, b, null, events, { it.run() }, {})

        run(mixer, 30)

        assertTrue(a.retired)
        assertNull(mixer.session)
        assertEquals(listOf("audible", "completed"), events.log)
    }

    @Test
    fun missingBFailsAfterTheWaitLimit() {
        val mixer = BusMixer()
        val a = source("a", BusSource.RING_FRAMES, 10_000_000L, 0.1f)
        val b = BusSource(host, "b").also { it.play() }
        mixer.sources += a; mixer.sources += b
        val events = Events()
        mixer.session = MixSession(doc(), a, b, null, events, { it.run() }, {})

        // A se rellena cada bloque para que no se quede sin audio mientras se espera a B.
        val out = FloatArray(2 * block)
        var media = 10_000_000L + BusSource.RING_FRAMES * 1_000_000L / BusMixer.SAMPLE_RATE
        for (i in 0 until 1000) {
            mixer.render(out, i.toLong() * block)
            a.write(FloatArray(2 * block) { 0.1f }, block, media)
            media += block * 1_000_000L / BusMixer.SAMPLE_RATE
            if (events.log.isNotEmpty()) break
        }

        assertEquals(listOf("failed"), events.log)
        assertNull(mixer.session)
    }

    @Test
    fun limiterKeepsTheSumBelowMinusOneDbfs() {
        val mixer = BusMixer()
        mixer.sources += source("a", 10_000, 0L, 0.9f)
        mixer.sources += source("b", 10_000, 0L, 0.9f)
        val out = FloatArray(2 * block)
        repeat(10) { mixer.render(out, it.toLong() * block) }
        assertTrue(out.all { kotlin.math.abs(it) <= 0.8913f + 1e-4f })
    }
}
