package com.example.resonant.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLaunchDecisionTest {

    private fun decide(
        action: String?,
        slug: String? = null,
        loggedIn: Boolean = true,
        liveEnabled: Boolean = true,
        inRoom: Boolean = false
    ) = WidgetLaunchDecision.decide(action, slug, loggedIn, liveEnabled, inRoom)

    @Test
    fun `live with capability opens the channel to start listening`() {
        assertEquals(WidgetLaunchAction.OpenLive("rock"), decide(ResonantWidgetActions.ACTION_OPEN_LIVE, slug = "rock"))
    }

    @Test
    fun `live without capability says it is not available`() {
        assertEquals(
            WidgetLaunchAction.LiveUnavailable,
            decide(ResonantWidgetActions.ACTION_OPEN_LIVE, slug = "rock", liveEnabled = false)
        )
    }

    @Test
    fun `live without slug does nothing`() {
        assertEquals(WidgetLaunchAction.None, decide(ResonantWidgetActions.ACTION_OPEN_LIVE, slug = null))
    }

    @Test
    fun `live room opens it`() {
        assertEquals(WidgetLaunchAction.OpenRoom, decide(ResonantWidgetActions.ACTION_OPEN_ROOM, inRoom = true))
    }

    @Test
    fun `ended room warns`() {
        assertEquals(WidgetLaunchAction.RoomGone, decide(ResonantWidgetActions.ACTION_OPEN_ROOM, inRoom = false))
    }

    @Test
    fun `logged out never acts`() {
        assertEquals(WidgetLaunchAction.None, decide(ResonantWidgetActions.ACTION_OPEN_LIVE, slug = "rock", loggedIn = false))
        assertEquals(WidgetLaunchAction.None, decide(ResonantWidgetActions.ACTION_OPEN_ROOM, inRoom = true, loggedIn = false))
    }

    @Test
    fun `open app does nothing more`() {
        assertEquals(WidgetLaunchAction.None, decide(ResonantWidgetActions.ACTION_OPEN_APP))
    }

    @Test
    fun `only widget actions are recognised`() {
        assertTrue(WidgetLaunchDecision.isWidgetAction(ResonantWidgetActions.ACTION_OPEN_ROOM))
        assertFalse(WidgetLaunchDecision.isWidgetAction("android.intent.action.VIEW"))
        assertFalse(WidgetLaunchDecision.isWidgetAction(null))
    }
}
