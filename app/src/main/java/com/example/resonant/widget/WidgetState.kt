package com.example.resonant.widget

/** What the home widget is showing for: own music (Flow included), a Unisound room or Live. */
enum class WidgetMode { NORMAL, UNISOUND, LIVE }

/** Presentation picked from the size the launcher gives the widget. */
enum class WidgetSize { COMPACT, HORIZONTAL, LARGE }

/** Layout file used by a render: one per size plus the "nothing to resume" one. */
enum class WidgetLayout { COMPACT, HORIZONTAL, LARGE, EMPTY }

/**
 * Immutable picture of the playback the widget mirrors. Only display data: no URLs, tokens or
 * queue ids, so it can be persisted as it is.
 */
data class WidgetState(
    val title: String = "",
    val artist: String = "",
    val hasSong: Boolean = false,
    val isPlaying: Boolean = false,
    val mode: WidgetMode = WidgetMode.NORMAL,
    val liveChannelName: String? = null,
    /** Unisound only: host, or guest with `guestsCanControl`. Always true in the other modes. */
    val canControl: Boolean = true
) {
    companion object {
        val EMPTY = WidgetState()
    }
}

/** Per-widget quick access chosen in the configuration screen. Stored as `AUTO|ROOM|LIVE:<slug>:<name>`. */
sealed class QuickAccessConfig {
    object Auto : QuickAccessConfig()
    object Room : QuickAccessConfig()
    data class Live(val slug: String, val name: String) : QuickAccessConfig()

    fun encode(): String = when (this) {
        Auto -> AUTO
        Room -> ROOM
        is Live -> "$LIVE_PREFIX$slug:$name"
    }

    companion object {
        private const val AUTO = "AUTO"
        private const val ROOM = "ROOM"
        private const val LIVE_PREFIX = "LIVE:"

        /** Anything unknown or corrupt falls back to [Auto]. The name may contain `:`. */
        fun decode(raw: String?): QuickAccessConfig {
            if (raw == null) return Auto
            return when {
                raw == AUTO -> Auto
                raw == ROOM -> Room
                raw.startsWith(LIVE_PREFIX) -> {
                    val parts = raw.removePrefix(LIVE_PREFIX).split(":", limit = 2)
                    val slug = parts.getOrNull(0)?.trim().orEmpty()
                    val name = parts.getOrNull(1)
                    if (slug.isEmpty() || name == null) Auto else Live(slug, name.ifBlank { slug })
                }
                else -> Auto
            }
        }
    }
}

/** The quick access actually shown, once resolved against the room, last channel and capabilities. */
sealed class QuickAccessTarget {
    object Room : QuickAccessTarget()
    data class Live(val slug: String, val name: String) : QuickAccessTarget()
}

/** What a widget button does. Media keys are always explicit: never a play/pause toggle. */
enum class WidgetButtonAction { PLAY, PAUSE, NEXT, PREVIOUS, OPEN_APP, OPEN_ROOM, OPEN_LIVE }

/** Label over the title that tells the mode apart. */
sealed class WidgetModeLabel {
    object Unisound : WidgetModeLabel()

    /** "EN DIRECTO · <channel>". */
    data class Live(val channelName: String) : WidgetModeLabel()
}

/**
 * Everything [ResonantWidgetViews] needs to fill a `RemoteViews`, decided by
 * [ResonantWidgetRenderer]. Kept free of Android types so the whole mapping is unit-tested.
 */
data class WidgetRenderSpec(
    val layout: WidgetLayout,
    /** Null when the layout shows no text for the song (compact and empty). */
    val title: String?,
    val artist: String?,
    val showCover: Boolean,
    val modeLabel: WidgetModeLabel?,
    /** Play/pause icon for media keys, a text button for the "open" actions. */
    val centerAction: WidgetButtonAction,
    val showSkipButtons: Boolean,
    val quickAccess: QuickAccessTarget?
)
