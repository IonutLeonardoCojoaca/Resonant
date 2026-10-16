package com.example.resonant.widget

/**
 * Resolves the configured quick access (design D7). "Automático" is the current room, else the
 * last Live channel heard, else nothing. Live or Unisound accesses are hidden when that capability
 * was off the last time it was read.
 */
object WidgetQuickAccessResolver {

    fun resolve(
        config: QuickAccessConfig,
        inRoom: Boolean,
        lastLive: QuickAccessTarget.Live?,
        liveEnabled: Boolean,
        unisoundEnabled: Boolean
    ): QuickAccessTarget? = when (config) {
        QuickAccessConfig.Auto -> when {
            inRoom && unisoundEnabled -> QuickAccessTarget.Room
            lastLive != null && liveEnabled -> lastLive
            else -> null
        }
        QuickAccessConfig.Room -> if (unisoundEnabled) QuickAccessTarget.Room else null
        is QuickAccessConfig.Live -> if (liveEnabled) QuickAccessTarget.Live(config.slug, config.name) else null
    }
}
