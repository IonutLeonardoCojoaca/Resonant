package com.example.resonant.playback

import com.example.resonant.data.models.Song
import com.example.resonant.live.LiveDjMode
import com.example.resonant.live.LiveDjSubtitle
import com.example.resonant.live.LiveSlot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveDjControllerTest {
    private class FakeSpeaker(override var isReady: Boolean = true) : DjSpeaker {
        val spoken = mutableListOf<String>()
        var stops = 0
        var shutdowns = 0
        var pendingDone: (() -> Unit)? = null

        override fun prepare() = Unit

        override fun speak(text: String, onDone: () -> Unit): Boolean {
            spoken += text
            pendingDone = onDone
            return true
        }

        override fun stop() {
            stops++
        }

        override fun shutdown() {
            shutdowns++
        }

        fun finish() {
            pendingDone?.invoke()
            pendingDone = null
        }
    }

    private class Fixture(scope: TestScope, mode: LiveDjMode = LiveDjMode.VOICE, ready: Boolean = true) {
        val speaker = FakeSpeaker(ready)
        val duckCalls = mutableListOf<Boolean>()
        val subtitle = MutableStateFlow<LiveDjSubtitle?>(null)
        val controller = LiveDjController(
            speaker = speaker,
            duck = { duckCalls += it },
            mode = { mode },
            featureOn = { true },
            scope = scope,
            subtitle = subtitle,
            subtitleHoldMs = 8_000L
        )
    }

    private fun slot(position: Int, intro: String?) =
        LiveSlot("b1", position, position * 200_000L, 200_000L, Song(id = "s$position", title = "Song $position"), intro)

    @Test
    fun `slot change with intro speaks and ducks`() = runTest {
        val f = Fixture(this)

        f.controller.onTick(slot(0, null), 150_000L)
        f.controller.onTick(slot(1, "Ahora, Song 1."), 500L)

        assertEquals(listOf("Ahora, Song 1."), f.speaker.spoken)
        assertEquals(listOf(true), f.duckCalls)
        assertEquals("Ahora, Song 1.", f.subtitle.value?.text)
    }

    @Test
    fun `voice done restores volume and hides subtitle`() = runTest {
        val f = Fixture(this)
        f.controller.onTick(slot(1, "Ahora, Song 1."), 500L)

        f.speaker.finish()

        assertEquals(listOf(true, false), f.duckCalls)
        assertNull(f.subtitle.value)
    }

    @Test
    fun `stop halfway cuts the voice and restores volume`() = runTest {
        val f = Fixture(this)
        f.controller.onTick(slot(1, "Ahora, Song 1."), 500L)

        f.controller.stop("focus_loss")

        assertEquals(1, f.speaker.stops)
        assertEquals(listOf(true, false), f.duckCalls)
        assertNull(f.subtitle.value)
        f.speaker.finish() // late callback from the engine: no effect
        assertEquals(listOf(true, false), f.duckCalls)
    }

    @Test
    fun `slot change during the intro cuts it`() = runTest {
        val f = Fixture(this)
        f.controller.onTick(slot(1, "Ahora, Song 1."), 500L)

        f.controller.onTick(slot(2, null), 100L)

        assertEquals(1, f.speaker.stops)
        assertEquals(listOf(true, false), f.duckCalls)
    }

    @Test
    fun `the same intro is not repeated`() = runTest {
        val f = Fixture(this)
        f.controller.onTick(slot(1, "Ahora, Song 1."), 500L)
        f.speaker.finish()

        f.controller.onTick(slot(1, "Ahora, Song 1."), 1_000L)
        f.controller.stop("hold")
        f.controller.onTick(slot(1, "Ahora, Song 1."), 1_500L)

        assertEquals(1, f.speaker.spoken.size)
    }

    @Test
    fun `subtitles mode shows the text for a while without ducking`() = runTest {
        val f = Fixture(this, mode = LiveDjMode.SUBTITLES)

        f.controller.onTick(slot(1, "Ahora, Song 1."), 500L)
        runCurrent()

        assertEquals("Ahora, Song 1.", f.subtitle.value?.text)
        assertEquals(emptyList<Boolean>(), f.duckCalls)
        advanceTimeBy(8_001L)
        assertNull(f.subtitle.value)
        assertEquals(emptyList<String>(), f.speaker.spoken)
    }

    @Test
    fun `voice without tts shows subtitle only`() = runTest {
        val f = Fixture(this, ready = false)

        f.controller.onTick(slot(1, "Ahora, Song 1."), 500L)

        assertEquals(emptyList<String>(), f.speaker.spoken)
        assertEquals(emptyList<Boolean>(), f.duckCalls)
        assertEquals("Ahora, Song 1.", f.subtitle.value?.text)
        f.controller.release()
    }

    @Test
    fun `release frees the engine and a new session can present again`() = runTest {
        val f = Fixture(this)
        f.controller.onTick(slot(1, "Ahora, Song 1."), 500L)

        f.controller.release()
        f.controller.onTick(slot(1, "Ahora, Song 1."), 500L)

        assertEquals(1, f.speaker.shutdowns)
        assertEquals(2, f.speaker.spoken.size)
    }
}
