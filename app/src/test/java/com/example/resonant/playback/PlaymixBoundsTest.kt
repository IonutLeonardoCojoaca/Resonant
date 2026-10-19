package com.example.resonant.playback

import com.example.resonant.data.network.PlaymixRenderDTO
import com.example.resonant.data.network.PlaymixTransitionDTO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class PlaymixBoundsTest {

    private val now = Instant.parse("2026-10-15T20:00:00Z").toEpochMilli()

    // Mismos builders que RenderedTransitionTest, con el clip de la spec: aStartMs 164 000 y B audible a 1 000 ms.
    private fun render() = PlaymixRenderDTO(
        status = "ready", revision = 3, url = "https://x/3.flac", expiresAt = "2026-10-15T23:00:00Z",
        aStartMs = 164_000, bEndMs = 15_412, prerollMs = 1_000, postrollMs = 1_000, durationMs = 18_000
    )

    private fun transition() = PlaymixTransitionDTO(
        id = "t", fromPlaymixSongId = "a", toPlaymixSongId = "b", exitPointMs = 165_000, entryPointMs = 412,
        crossfadeDurationMs = 14_000, fadeCurveType = "linear", eqSettings = null, compatibility = null, render = render()
    )

    // Canción de 3:18 con entrega en 2:45 y preparación del clip 6 s antes del arranque.
    private val bounds = PlaymixBounds.of(handoffMs = 165_000, triggerMs = 158_000, mediaDurationMs = 198_000)!!

    // ── Límites ─────────────────────────────────────────────────────────────

    @Test
    fun `seek below the limit is applied as is`() {
        assertEquals(90_000L, bounds.clampSeek(90_000))
        assertEquals(157_500L, bounds.clampSeek(157_500))
    }

    @Test
    fun `seek beyond the limit stays at the limit`() {
        assertEquals(158_000L - PlaymixBounds.SEEK_GUARD_MS, bounds.seekLimitMs)
        assertEquals(157_500L, bounds.clampSeek(164_000))
        assertEquals(157_500L, bounds.clampSeek(200_000))
    }

    @Test
    fun `displayed duration is the handoff and position never exceeds it`() {
        assertEquals(165_000L, bounds.displayDuration)
        assertEquals(120_000L, bounds.displayPosition(120_000))
        assertEquals(165_000L, bounds.displayPosition(170_000))
    }

    @Test
    fun `handoff outside the audio or unknown trigger gives no bounds`() {
        assertNull(PlaymixBounds.of(handoffMs = 0, triggerMs = 158_000, mediaDurationMs = 198_000))
        assertNull(PlaymixBounds.of(handoffMs = 198_000, triggerMs = 158_000, mediaDurationMs = 198_000))
        assertNull(PlaymixBounds.of(handoffMs = 210_000, triggerMs = 158_000, mediaDurationMs = 198_000))
        assertNull(PlaymixBounds.of(handoffMs = 165_000, triggerMs = 158_000, mediaDurationMs = 0))
        assertNull(PlaymixBounds.of(handoffMs = 165_000, triggerMs = -1, mediaDurationMs = 198_000))
        assertNotNull(PlaymixBounds.of(handoffMs = 165_000, triggerMs = 158_000, mediaDurationMs = 198_000))
    }

    // ── Punto de entrega por vía ────────────────────────────────────────────

    @Test
    fun `rendered clip hands off when B becomes audible`() {
        val plan = RenderedTransition.planFor(transition(), now)!!
        assertEquals(1_000L, plan.audibleAtClipMs)
        assertEquals(165_000L, PlaymixBounds.handoffFor(PlaymixBounds.Route.Clip(plan)))
    }

    @Test
    fun `document hands off at a start plus b offset`() {
        assertEquals(164_000L, PlaymixBounds.handoffFor(PlaymixBounds.Route.Document(aStartMs = 160_000, bOffsetMs = 4_000)))
    }

    @Test
    fun `classic transition hands off at the exit point`() {
        assertEquals(170_000L, PlaymixBounds.handoffFor(PlaymixBounds.Route.Classic(exitPointMs = 170_000)))
    }

    @Test
    fun `no transition hands off at the end of A audio`() {
        assertEquals(195_000L, PlaymixBounds.handoffFor(PlaymixBounds.Route.NoMix(aStartMs = 195_000)))
    }

    // ── Posición publicada ──────────────────────────────────────────────────

    @Test
    fun `without bounds the published position is the same as before`() {
        assertEquals(PlaybackPosition(170_000, 198_000), PlaymixBounds.positionFor(170_000, 198_000, null))
        assertEquals(198_000, PlaymixBounds.positionFor(170_000, 198_000, null).mediaDurationMs)
    }

    @Test
    fun `with bounds the published duration is the handoff and the real one is kept`() {
        val position = PlaymixBounds.positionFor(170_000, 198_000, bounds)
        assertEquals(165_000L, position.position)
        assertEquals(165_000, position.duration)
        assertEquals(198_000, position.mediaDurationMs)
    }
}
