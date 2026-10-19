package com.example.resonant.playback

import com.example.resonant.data.network.PlaymixRenderDTO
import com.example.resonant.data.network.PlaymixTransitionDTO
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Clip descargado de una Playmix: no caduca (collection-queue-offline D5). */
class RenderedTransitionLocalClipTest {

    private val now = Instant.parse("2026-10-15T20:00:00Z").toEpochMilli()

    private fun transition(url: String? = "https://x/3.flac", expiresAt: String? = "2026-10-12T20:00:00Z") =
        PlaymixTransitionDTO(
            id = "t1", fromPlaymixSongId = "a", toPlaymixSongId = "b", exitPointMs = 200_400, entryPointMs = 412,
            crossfadeDurationMs = 14_000, fadeCurveType = "linear", eqSettings = null, compatibility = null,
            render = PlaymixRenderDTO(
                status = "ready", revision = 3, url = url, expiresAt = expiresAt,
                aStartMs = 197_400, bEndMs = 15_412, prerollMs = 1_000, postrollMs = 1_000, durationMs = 18_000
            )
        )

    @After
    fun tearDown() = OfflineClips.replaceCompleted(emptyList())

    @Test
    fun `expired url without a local clip falls back to the engine`() {
        assertNull(RenderedTransition.planFor(transition(), now, localClipUri = null))
        assertTrue(RenderedTransition.needsRefresh(transition(), now, localClipUri = null))
    }

    @Test
    fun `local clip ignores an url expired days ago`() {
        val local = OfflineClips.uriFor("t1", 3)
        val plan = RenderedTransition.planFor(transition(), now, localClipUri = local)
        assertNotNull(plan)
        assertEquals(local, plan!!.localUri)
        assertEquals(197_400L, plan.aStartMs)
        assertFalse(RenderedTransition.needsRefresh(transition(), now, localClipUri = local))
    }

    @Test
    fun `local clip works even when the stored detail has no url`() {
        val local = OfflineClips.uriFor("t1", 3)
        val plan = RenderedTransition.planFor(transition(url = null, expiresAt = null), now, localClipUri = local)
        assertEquals(local, plan?.localUri)
    }

    @Test
    fun `completed clips are found by transition and render revision`() {
        assertNull(OfflineClips.localUriFor(transition()))
        OfflineClips.markCompleted(OfflineClips.keyFor("t1", 3))
        assertEquals("resonant-clip://t1/3", OfflineClips.localUriFor(transition()))
        // Por defecto planFor consulta los clips completos.
        assertEquals("resonant-clip://t1/3", RenderedTransition.planFor(transition(), now)?.localUri)

        // Un re-render (revisión nueva) no usa el clip antiguo.
        OfflineClips.replaceCompleted(listOf(OfflineClips.keyFor("t1", 2)))
        assertNull(OfflineClips.localUriFor(transition()))
    }

    @Test
    fun `clip uris and keys round trip`() {
        assertEquals("t1" to 3, OfflineClips.parseUri(OfflineClips.uriFor("t1", 3)))
        assertEquals("t1" to 3, OfflineClips.parseKey(OfflineClips.keyFor("t1", 3)))
        assertNull(OfflineClips.parseUri("resonant://song/x"))
        assertNull(OfflineClips.parseKey("song:x"))
    }
}
