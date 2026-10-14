package com.example.resonant.live

import com.example.resonant.unisound.UnisoundControl

/** In-app (and Aria) playback orders that reach the service while a Live broadcast plays. */
enum class LiveCommand { PAUSE, RESUME, NEXT, PREVIOUS, SEEK, SHUFFLE, REPEAT, UPDATE_QUEUE, CONNECT, PLAY_OWN, OTHER }

sealed class LiveRoute {
    /** Stop listening locally; the broadcast goes on. */
    object Hold : LiveRoute()

    /** Listen again at the current point of the schedule. */
    object Rejoin : LiveRoute()

    /** Leave the broadcast, then handle the order as usual (own music). */
    object Leave : LiveRoute()

    /** Not allowed in Live; tell the user why. */
    data class Denied(val message: String) : LiveRoute()

    /** Not allowed in Live and nobody to tell (system controls, toggles). */
    object Ignored : LiveRoute()

    /** Not a playback order: the caller handles it as usual. */
    object PassThrough : LiveRoute()
}

/** Live listeners can only enter and leave: nothing they do changes the broadcast. */
object LiveControlPolicy {

    fun route(command: LiveCommand): LiveRoute = when (command) {
        LiveCommand.PAUSE -> LiveRoute.Hold
        LiveCommand.RESUME -> LiveRoute.Rejoin
        LiveCommand.NEXT, LiveCommand.PREVIOUS, LiveCommand.SEEK -> LiveRoute.Denied(LiveErrors.MESSAGE_NO_SKIP)
        LiveCommand.SHUFFLE, LiveCommand.REPEAT, LiveCommand.UPDATE_QUEUE, LiveCommand.CONNECT -> LiveRoute.Ignored
        LiveCommand.PLAY_OWN -> LiveRoute.Leave
        LiveCommand.OTHER -> LiveRoute.PassThrough
    }

    /** Notification, lock screen, headset and Bluetooth: no screen to show a message on. */
    fun routeSystem(control: UnisoundControl): LiveRoute = when (control) {
        UnisoundControl.PAUSE -> LiveRoute.Hold
        UnisoundControl.PLAY -> LiveRoute.Rejoin
        UnisoundControl.NEXT, UnisoundControl.PREVIOUS, UnisoundControl.SEEK -> LiveRoute.Ignored
    }
}
