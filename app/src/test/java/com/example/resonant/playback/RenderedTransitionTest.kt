package com.example.resonant.playback

import com.example.resonant.data.network.PlaymixRenderDTO
import com.example.resonant.data.network.PlaymixTransitionDTO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RenderedTransitionTest {

    private val now = Instant.parse("2026-10-15T20:00:00Z").toEpochMilli()

    private fun render(
        status: String = "ready",
        url: String? = "https://x/3.flac",
        expiresAt: String? = "2026-10-15T23:00:00Z"
    ) = PlaymixRenderDTO(
        status = status, revision = 3, url = url, expiresAt = expiresAt,
        aStartMs = 197_400, bEndMs = 15_412, prerollMs = 1_000, postrollMs = 1_000, durationMs = 18_000
    )

    // exitPointMs = a.startMs (198 400) + offset (2 000)
    private fun transition(render: PlaymixRenderDTO? = render()) = PlaymixTransitionDTO(
        id = "t", fromPlaymixSongId = "a", toPlaymixSongId = "b", exitPointMs = 200_400, entryPointMs = 412,
        crossfadeDurationMs = 14_000, fadeCurveType = "linear", eqSettings = null, compatibility = null, render = render
    )

    private fun plan() = RenderedTransition.planFor(transition(), now)!!

    // ── Cuándo usar el clip ─────────────────────────────────────────────────

    @Test
    fun `ready render with valid url gives a plan`() {
        val plan = plan()
        assertEquals(197_400L, plan.aStartMs)
        assertEquals(18_000L, plan.clipDurationMs)
        assertEquals(3_000L, plan.audibleAtClipMs) // preroll 1 000 + offset 2 000
    }

    @Test
    fun `no render, pending render or missing data use the current engine`() {
        assertNull(RenderedTransition.planFor(transition(render = null), now))
        assertNull(RenderedTransition.planFor(transition(render("pending", url = null)), now))
        assertNull(RenderedTransition.planFor(transition(render(url = "")), now))
        assertNull(RenderedTransition.planFor(transition(render().copy(bEndMs = null)), now))
    }

    @Test
    fun `expired or almost expired url uses the current engine and asks for a refresh`() {
        val expired = transition(render(expiresAt = "2026-10-15T20:01:00Z"))
        assertNull(RenderedTransition.planFor(expired, now))
        assertTrue(RenderedTransition.needsRefresh(expired, now))
        assertFalse(RenderedTransition.needsRefresh(transition(), now))
        assertFalse(RenderedTransition.needsRefresh(transition(render("pending", url = null)), now))
    }

    @Test
    fun `trigger is early enough to prepare the clip`() {
        assertEquals(197_400L - RenderedTransition.PREPARE_LEAD_MS, RenderedTransition.triggerPositionMs(plan()))
    }

    // ── Empalme A → clip ────────────────────────────────────────────────────

    @Test
    fun `clip position follows A inside the preroll`() {
        val plan = plan()
        assertNull(RenderedTransition.clipPositionForA(197_399, plan))
        assertEquals(0L, RenderedTransition.clipPositionForA(197_400, plan))
        assertEquals(350L, RenderedTransition.clipPositionForA(197_750, plan))
        assertNull(RenderedTransition.clipPositionForA(198_400, plan))
    }

    @Test
    fun `preroll exhausted falls back to the current engine`() {
        assertFalse(RenderedTransition.missedPreroll(198_399, plan()))
        assertTrue(RenderedTransition.missedPreroll(198_400, plan()))
    }

    // ── Empalme clip → B ────────────────────────────────────────────────────

    @Test
    fun `switch to b happens inside the postroll at the equivalent position`() {
        val plan = plan()
        assertEquals(17_500L, plan.switchToBAtClipMs)
        assertFalse(RenderedTransition.shouldSwitchToB(17_499, plan))
        assertTrue(RenderedTransition.shouldSwitchToB(17_500, plan))
        assertEquals(14_912L, plan.bPreparePositionMs)
        assertEquals(15_412L, RenderedTransition.bPositionForClip(plan, 18_000))
    }

    @Test
    fun `b becomes current when it enters`() {
        assertFalse(RenderedTransition.isAudible(2_999, plan()))
        assertTrue(RenderedTransition.isAudible(3_000, plan()))
    }

    @Test
    fun `audible point is kept inside the mix when derived fields are clamped`() {
        val early = RenderedTransition.planFor(transition().copy(exitPointMs = 100), now)
        assertNotNull(early)
        assertEquals(1_000L, early!!.audibleAtClipMs)
    }

    // ── Volumen y fundidos ──────────────────────────────────────────────────

    @Test
    fun `clip volume goes from a normalization to b normalization`() {
        val plan = plan()
        assertEquals(0.8f, RenderedTransition.clipVolume(500, plan, 0.8f, 0.6f), 1e-6f)
        assertEquals(0.7f, RenderedTransition.clipVolume(9_000, plan, 0.8f, 0.6f), 1e-6f)
        assertEquals(0.6f, RenderedTransition.clipVolume(17_500, plan, 0.8f, 0.6f), 1e-6f)
    }

    @Test
    fun `splice fade lasts thirty milliseconds`() {
        assertEquals(0f, RenderedTransition.spliceProgress(1_000, 1_000), 0f)
        assertEquals(0.5f, RenderedTransition.spliceProgress(1_015, 1_000), 1e-6f)
        assertEquals(1f, RenderedTransition.spliceProgress(1_100, 1_000), 0f)
    }
}
