package com.example.resonant.unisound

enum class UnisoundControl { PLAY, PAUSE, NEXT, PREVIOUS, SEEK }

/** Where a control came from; only used for logs and telemetry, permissions are the same. */
enum class UnisoundControlOrigin { APP, SYSTEM, ARIA }

sealed class UnisoundRoute {
    /** Send the order to the hub; the local player waits for the server state. */
    data class SendCommand(val control: UnisoundControl, val positionMs: Long? = null) : UnisoundRoute()

    /** Resume only this device at the room position (it was on local hold). */
    object ReengageLocal : UnisoundRoute()

    data class Denied(val message: String) : UnisoundRoute()

    /** Nothing to do (e.g. play while the room is already playing here). */
    object Ignored : UnisoundRoute()
}

/** Turns a local transport control into what must happen while in a room. */
object UnisoundControlRouter {

    fun route(
        control: UnisoundControl,
        state: UnisoundRoomState?,
        positionMs: Long? = null
    ): UnisoundRoute {
        if (state == null) return UnisoundRoute.Ignored
        if (control == UnisoundControl.PLAY && state.isPlaying) {
            return if (state.localHold) UnisoundRoute.ReengageLocal else UnisoundRoute.Ignored
        }
        if (state.connection != UnisoundConnection.CONNECTED) {
            return UnisoundRoute.Denied(UnisoundErrors.MESSAGE_RECONNECTING)
        }
        if (!state.canControl) return UnisoundRoute.Denied(UnisoundErrors.MESSAGE_CONTROL_DENIED)
        return when (control) {
            UnisoundControl.PAUSE, UnisoundControl.SEEK -> UnisoundRoute.SendCommand(control, positionMs ?: 0L)
            else -> UnisoundRoute.SendCommand(control)
        }
    }
}
