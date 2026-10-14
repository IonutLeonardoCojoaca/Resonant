package com.example.resonant.live

import com.example.resonant.unisound.UnisoundControl
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveControlPolicyTest {
    @Test
    fun `pause holds and resume rejoins`() {
        assertEquals(LiveRoute.Hold, LiveControlPolicy.route(LiveCommand.PAUSE))
        assertEquals(LiveRoute.Rejoin, LiveControlPolicy.route(LiveCommand.RESUME))
    }

    @Test
    fun `skips and seek from the app or aria are denied with a message`() {
        listOf(LiveCommand.NEXT, LiveCommand.PREVIOUS, LiveCommand.SEEK).forEach {
            assertEquals(it.name, LiveRoute.Denied("En directo no se puede saltar"), LiveControlPolicy.route(it))
        }
    }

    @Test
    fun `toggles, queue edits and connect are ignored`() {
        listOf(LiveCommand.SHUFFLE, LiveCommand.REPEAT, LiveCommand.UPDATE_QUEUE, LiveCommand.CONNECT).forEach {
            assertEquals(it.name, LiveRoute.Ignored, LiveControlPolicy.route(it))
        }
    }

    @Test
    fun `own music leaves the broadcast`() {
        assertEquals(LiveRoute.Leave, LiveControlPolicy.route(LiveCommand.PLAY_OWN))
    }

    @Test
    fun `unknown action passes through`() {
        assertEquals(LiveRoute.PassThrough, LiveControlPolicy.route(LiveCommand.OTHER))
    }

    @Test
    fun `system controls hold, rejoin or are ignored silently`() {
        assertEquals(LiveRoute.Hold, LiveControlPolicy.routeSystem(UnisoundControl.PAUSE))
        assertEquals(LiveRoute.Rejoin, LiveControlPolicy.routeSystem(UnisoundControl.PLAY))
        listOf(UnisoundControl.NEXT, UnisoundControl.PREVIOUS, UnisoundControl.SEEK).forEach {
            assertEquals(it.name, LiveRoute.Ignored, LiveControlPolicy.routeSystem(it))
        }
    }
}
