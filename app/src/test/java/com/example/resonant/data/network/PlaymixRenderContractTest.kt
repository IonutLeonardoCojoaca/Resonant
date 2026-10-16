package com.example.resonant.data.network

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaymixRenderContractTest {
    private val gson = Gson()

    private val baseTransition = """
        "id": "8a1f", "fromPlaymixSongId": "a", "toPlaymixSongId": "b",
        "exitPointMs": 198400, "entryPointMs": 412, "crossfadeDurationMs": 14000,
        "fadeCurveType": "linear", "eqSettings": null, "compatibility": null
    """.trimIndent()

    @Test
    fun `parses ready render with splice points`() {
        val json = """
            { $baseTransition,
              "render": { "status": "ready", "revision": 3, "url": "https://x/3.flac?sig",
                          "expiresAt": "2026-10-15T22:00:00Z", "aStartMs": 197400, "bEndMs": 15412,
                          "prerollMs": 1000, "postrollMs": 1000, "durationMs": 18000, "error": null } }
        """.trimIndent()

        val render = gson.fromJson(json, PlaymixTransitionDTO::class.java).render!!

        assertEquals("ready", render.status)
        assertEquals(3, render.revision)
        assertEquals("https://x/3.flac?sig", render.url)
        assertEquals(197_400, render.aStartMs)
        assertEquals(15_412, render.bEndMs)
        assertEquals(1_000, render.prerollMs)
        assertEquals(18_000, render.durationMs)
    }

    @Test
    fun `transition without render keeps working`() {
        val transition = gson.fromJson("{ $baseTransition }", PlaymixTransitionDTO::class.java)

        assertNull(transition.render)
        assertEquals(198_400, transition.exitPointMs)
    }

    @Test
    fun `pending render has no url`() {
        val json = """{ $baseTransition, "render": { "status": "pending", "revision": 2 } }"""

        val render = gson.fromJson(json, PlaymixTransitionDTO::class.java).render!!

        assertEquals("pending", render.status)
        assertNull(render.url)
        assertNull(render.aStartMs)
    }
}
