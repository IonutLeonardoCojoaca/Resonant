package com.example.resonant.unisound

/** What to do when a `/u/{code}` invite link opens the app. */
sealed class UnisoundLinkAction {
    object Unavailable : UnisoundLinkAction()
    object OpenCurrentRoom : UnisoundLinkAction()
    data class ConfirmJoin(val code: String) : UnisoundLinkAction()
    data class ConfirmSwitch(val code: String) : UnisoundLinkAction()
}

object UnisoundLinkDecision {
    fun decide(code: String, featureEnabled: Boolean, room: UnisoundRoomState?): UnisoundLinkAction = when {
        !featureEnabled -> UnisoundLinkAction.Unavailable
        room == null -> UnisoundLinkAction.ConfirmJoin(code)
        room.code == code -> UnisoundLinkAction.OpenCurrentRoom
        else -> UnisoundLinkAction.ConfirmSwitch(code)
    }
}
