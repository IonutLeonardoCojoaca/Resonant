package com.example.resonant.widget

/**
 * Pure state -> view mapping of the widget (design D2). It returns a [WidgetRenderSpec] instead of
 * `RemoteViews` so every rule is covered by JVM tests; [ResonantWidgetViews] only copies it.
 *
 * | Situation                       | Prev/Next | Center               | Label                |
 * |---------------------------------|-----------|----------------------|----------------------|
 * | Not logged in / no song         | -         | OPEN_APP             | (logo)               |
 * | NORMAL, service alive           | visible   | PAUSE if playing/PLAY| -                    |
 * | NORMAL, cold                    | hidden    | PLAY                 | -                    |
 * | UNISOUND canControl, alive      | visible   | PAUSE/PLAY           | Unisound             |
 * | UNISOUND without canControl     | hidden    | OPEN_ROOM            | Unisound             |
 * | LIVE, alive                     | hidden    | PAUSE/PLAY           | EN DIRECTO · channel |
 * | UNISOUND or LIVE, cold          | hidden    | OPEN_APP             | -                    |
 *
 * The compact size only uses the cover and the center button; the quick access only exists in
 * the large size (and in the empty layout of a large widget when logged in).
 */
object ResonantWidgetRenderer {

    fun render(
        state: WidgetState,
        size: WidgetSize,
        quickAccess: QuickAccessTarget?,
        serviceActive: Boolean,
        loggedIn: Boolean
    ): WidgetRenderSpec {
        if (!loggedIn || !state.hasSong) {
            return WidgetRenderSpec(
                layout = WidgetLayout.EMPTY,
                title = null,
                artist = null,
                showCover = false,
                modeLabel = null,
                centerAction = WidgetButtonAction.OPEN_APP,
                showSkipButtons = false,
                quickAccess = if (loggedIn && size == WidgetSize.LARGE) quickAccess else null
            )
        }

        // In a cold process nothing is playing, whatever was persisted.
        val playKey = if (serviceActive && state.isPlaying) WidgetButtonAction.PAUSE else WidgetButtonAction.PLAY
        val controls: Triple<WidgetButtonAction, Boolean, WidgetModeLabel?> = when (state.mode) {
            WidgetMode.NORMAL -> Triple(playKey, serviceActive, null)
            WidgetMode.UNISOUND -> when {
                !serviceActive -> Triple(WidgetButtonAction.OPEN_APP, false, null)
                state.canControl -> Triple(playKey, true, WidgetModeLabel.Unisound)
                else -> Triple(WidgetButtonAction.OPEN_ROOM, false, WidgetModeLabel.Unisound)
            }
            WidgetMode.LIVE ->
                if (!serviceActive) {
                    Triple(WidgetButtonAction.OPEN_APP, false, null)
                } else {
                    Triple(playKey, false, WidgetModeLabel.Live(state.liveChannelName.orEmpty()))
                }
        }
        val (center, skips, label) = controls

        val compact = size == WidgetSize.COMPACT
        return WidgetRenderSpec(
            layout = when (size) {
                WidgetSize.COMPACT -> WidgetLayout.COMPACT
                WidgetSize.HORIZONTAL -> WidgetLayout.HORIZONTAL
                WidgetSize.LARGE -> WidgetLayout.LARGE
            },
            title = if (compact) null else state.title,
            artist = if (compact) null else state.artist,
            showCover = true,
            modeLabel = if (compact) null else label,
            centerAction = center,
            showSkipButtons = skips && !compact,
            quickAccess = if (size == WidgetSize.LARGE) quickAccess else null
        )
    }
}
