package com.example.resonant.explore

import com.example.resonant.explore.ExploreLayoutEvent.AriaDismissed
import com.example.resonant.explore.ExploreLayoutEvent.AriaRestored
import com.example.resonant.explore.ExploreLayoutEvent.DeferredFailed
import com.example.resonant.explore.ExploreLayoutEvent.DeferredLoaded
import com.example.resonant.explore.ExploreLayoutEvent.DeferredMissing
import com.example.resonant.explore.ExploreLayoutEvent.DeferredRequested
import com.example.resonant.explore.ExploreLayoutEvent.NewLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreLayoutStateTest {

    private val reducer = ExploreLayoutReducer()

    private fun state(vararg sections: String, etag: String? = "\"e1\"") =
        reducer.layout(responseJson(layoutJson(*sections)), etag)

    private fun ExploreLayoutState.ids() = visibleSections.map { it.id }

    private fun ExploreLayoutState.ariaIds() =
        (visibleSections.single { it is ExploreSectionUi.AriaPicks } as ExploreSectionUi.AriaPicks)
            .items!!.map { it.song.id }

    @Test
    fun `layout keeps server order and skips hidden sections`() {
        val state = state(
            songsSection("daypart", "s1"),
            """{ "id": "live", "type": "live_sessions", "title": "x", "items": [ {} ] }""",
            pendingSection("forgotten"),
            """{ "id": "new_from_artists", "type": "albums", "title": "x", "items": [] }"""
        )

        assertEquals(listOf("daypart", "forgotten"), state.ids())
        assertEquals(setOf("forgotten"), state.pending)
        assertEquals("evening", state.daypart)
    }

    @Test
    fun `deferred loaded replaces pending in place`() {
        val state = state(songsSection("daypart", "s1"), pendingSection("forgotten"), songsSection("hidden_gems", "s3"))
            .let { reducer.reduce(it, DeferredRequested("forgotten"))!! }

        val next = reducer.reduce(state, DeferredLoaded("forgotten", sectionJson(songsSection("forgotten", "s2"))))!!

        assertEquals(listOf("daypart", "forgotten", "hidden_gems"), next.ids())
        assertTrue(next.pending.isEmpty())
        assertTrue(next.inFlight.isEmpty())
        assertEquals("s2", (next.visibleSections[1] as ExploreSectionUi.Songs).items?.single()?.id)
    }

    @Test
    fun `deferred loaded empty removes the section`() {
        val state = state(songsSection("daypart", "s1"), pendingSection("forgotten"))

        val next = reducer.reduce(
            state,
            DeferredLoaded("forgotten", sectionJson("""{ "id": "forgotten", "type": "songs", "title": "x", "items": [] }"""))
        )!!

        assertEquals(listOf("daypart"), next.ids())
    }

    @Test
    fun `deferred missing and failed remove the section and failed is remembered`() {
        val state = state(songsSection("daypart", "s1"), pendingSection("forgotten"), pendingSection("hidden_gems"))

        val missing = reducer.reduce(state, DeferredMissing("forgotten"))!!
        val failed = reducer.reduce(missing, DeferredFailed("hidden_gems"))!!

        assertEquals(listOf("daypart", "hidden_gems"), missing.ids())
        assertEquals(listOf("daypart"), failed.ids())
        assertEquals(setOf("hidden_gems"), failed.failedOnce)
        assertFalse("forgotten" in failed.failedOnce)
    }

    @Test
    fun `deferred requested is tracked only for pending sections`() {
        val state = state(songsSection("daypart", "s1"), pendingSection("forgotten"))

        assertEquals(setOf("forgotten"), reducer.reduce(state, DeferredRequested("forgotten"))!!.inFlight)
        assertTrue(reducer.reduce(state, DeferredRequested("daypart"))!!.inFlight.isEmpty())
    }

    @Test
    fun `aria dismissed filters the pick and removes the section when it was the last`() {
        val state = state(songsSection("daypart", "s1"), ariaSection("p1" to 1, "p2" to 2))

        val one = reducer.reduce(state, AriaDismissed("p1"))!!
        val none = reducer.reduce(one, AriaDismissed("p2"))!!

        assertEquals(listOf("p2"), one.ariaIds())
        assertEquals(listOf("daypart"), none.ids())
    }

    @Test
    fun `aria restored puts the pick back at its index`() {
        val state = state(ariaSection("p1" to 1, "p2" to 2, "p3" to 3))
        val dismissed = reducer.reduce(state, AriaDismissed("p2"))!!

        val restored = reducer.reduce(dismissed, AriaRestored("p2"))!!

        assertEquals(listOf("p1", "p3"), dismissed.ariaIds())
        assertEquals(listOf("p1", "p2", "p3"), restored.ariaIds())
    }

    @Test
    fun `new layout with the same etag keeps dismissals and with another etag drops them`() {
        val json = layoutJson(ariaSection("p1" to 1, "p2" to 2))
        val dismissed = reducer.reduce(state(ariaSection("p1" to 1, "p2" to 2)), AriaDismissed("p1"))

        val same = reducer.reduce(dismissed, NewLayout(responseJson(json), "\"e1\""))!!
        val other = reducer.reduce(dismissed, NewLayout(responseJson(json), "\"e2\""))!!

        assertEquals(listOf("p2"), same.ariaIds())
        assertEquals(listOf("p1", "p2"), other.ariaIds())
    }

    @Test
    fun `layout without sections is empty`() {
        val state = state("""{ "id": "x", "type": "songs", "title": "x", "items": [] }""")
        val onlyAria = reducer.reduce(state(ariaSection("p1" to 1)), AriaDismissed("p1"))!!

        assertTrue(state.isEmpty)
        assertTrue(onlyAria.isEmpty)
        assertFalse(state(songsSection("daypart", "s1")).isEmpty)
    }

    @Test
    fun `events without a layout yield null`() {
        assertNull(reducer.reduce(null, DeferredMissing("x")))
    }
}
