package com.example.resonant.widget

/** What `MainActivity` does when a widget opener launches it. */
sealed class WidgetLaunchAction {
    /** Just the app ("Abrir Resonant"), or nothing to do (logged out: the usual login flow). */
    object None : WidgetLaunchAction()

    /** Open the channel screen and start listening there (with its Unisound confirmation). */
    data class OpenLive(val slug: String) : WidgetLaunchAction()

    /** Live is off: show the usual "not available" message. */
    object LiveUnavailable : WidgetLaunchAction()
    object OpenRoom : WidgetLaunchAction()

    /** "Ya no estás en ninguna sala". */
    object RoomGone : WidgetLaunchAction()
}

/** Pure decision behind `MainActivity.handleWidgetIntent`, like `UnisoundLinkDecision`. */
object WidgetLaunchDecision {

    fun isWidgetAction(action: String?): Boolean =
        action == ResonantWidgetActions.ACTION_OPEN_APP ||
            action == ResonantWidgetActions.ACTION_OPEN_ROOM ||
            action == ResonantWidgetActions.ACTION_OPEN_LIVE

    /** [inRoom] must be read after trying `UnisoundManager.restore()` when there was no room. */
    fun decide(
        action: String?,
        liveSlug: String?,
        loggedIn: Boolean,
        liveEnabled: Boolean,
        inRoom: Boolean
    ): WidgetLaunchAction = when {
        !loggedIn -> WidgetLaunchAction.None
        action == ResonantWidgetActions.ACTION_OPEN_LIVE -> when {
            liveSlug.isNullOrBlank() -> WidgetLaunchAction.None
            !liveEnabled -> WidgetLaunchAction.LiveUnavailable
            else -> WidgetLaunchAction.OpenLive(liveSlug)
        }
        action == ResonantWidgetActions.ACTION_OPEN_ROOM ->
            if (inRoom) WidgetLaunchAction.OpenRoom else WidgetLaunchAction.RoomGone
        else -> WidgetLaunchAction.None
    }
}
