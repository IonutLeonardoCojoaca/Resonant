package com.example.resonant.unisound

import com.example.resonant.data.network.UnisoundSettingsDTO
import com.example.resonant.unisound.UnisoundFixtures.room
import com.example.resonant.unisound.UnisoundFixtures.snapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class UnisoundControlRouterTest {
    private val noControl = UnisoundSettingsDTO(guestsCanAdd = true, guestsCanControl = false, joinOpen = true)

    @Test
    fun `guest with control sends every order to the hub`() {
        val state = room(snapshot = snapshot(status = UnisoundRoomState.STATUS_PAUSED))

        assertEquals(UnisoundRoute.SendCommand(UnisoundControl.PLAY), UnisoundControlRouter.route(UnisoundControl.PLAY, state))
        assertEquals(UnisoundRoute.SendCommand(UnisoundControl.NEXT), UnisoundControlRouter.route(UnisoundControl.NEXT, state))
        assertEquals(UnisoundRoute.SendCommand(UnisoundControl.PREVIOUS), UnisoundControlRouter.route(UnisoundControl.PREVIOUS, state))
        assertEquals(
            UnisoundRoute.SendCommand(UnisoundControl.SEEK, 61_000L),
            UnisoundControlRouter.route(UnisoundControl.SEEK, state, 61_000L)
        )
    }

    @Test
    fun `pause carries the current position`() {
        assertEquals(
            UnisoundRoute.SendCommand(UnisoundControl.PAUSE, 61_000L),
            UnisoundControlRouter.route(UnisoundControl.PAUSE, room(), 61_000L)
        )
    }

    @Test
    fun `guest without control is denied for every order`() {
        // App, notification, headset and Aria all reach the same router, so one rule covers them.
        val state = room(snapshot = snapshot(settings = noControl, status = UnisoundRoomState.STATUS_PAUSED))

        UnisoundControl.values().forEach { control ->
            assertEquals(
                control.name,
                UnisoundRoute.Denied(UnisoundErrors.MESSAGE_CONTROL_DENIED),
                UnisoundControlRouter.route(control, state, 1_000L)
            )
        }
    }

    @Test
    fun `host keeps control when guests have none`() {
        val state = room(myUserId = UnisoundFixtures.HOST, snapshot = snapshot(settings = noControl))

        assertEquals(UnisoundRoute.SendCommand(UnisoundControl.NEXT), UnisoundControlRouter.route(UnisoundControl.NEXT, state))
    }

    @Test
    fun `play on local hold while the room plays only reengages this device`() {
        val state = room(snapshot = snapshot(settings = noControl), localHold = true)

        assertEquals(UnisoundRoute.ReengageLocal, UnisoundControlRouter.route(UnisoundControl.PLAY, state))
    }

    @Test
    fun `play on local hold while the room is paused sends play`() {
        val state = room(snapshot = snapshot(status = UnisoundRoomState.STATUS_PAUSED), localHold = true)

        assertEquals(UnisoundRoute.SendCommand(UnisoundControl.PLAY), UnisoundControlRouter.route(UnisoundControl.PLAY, state))
    }

    @Test
    fun `play while already playing is ignored`() {
        assertEquals(UnisoundRoute.Ignored, UnisoundControlRouter.route(UnisoundControl.PLAY, room()))
    }

    @Test
    fun `orders are denied while reconnecting`() {
        val state = room(connection = UnisoundConnection.RECONNECTING)

        assertEquals(
            UnisoundRoute.Denied(UnisoundErrors.MESSAGE_RECONNECTING),
            UnisoundControlRouter.route(UnisoundControl.PAUSE, state, 0L)
        )
    }

    @Test
    fun `no room means nothing to route`() {
        assertEquals(UnisoundRoute.Ignored, UnisoundControlRouter.route(UnisoundControl.PAUSE, null))
    }
}
