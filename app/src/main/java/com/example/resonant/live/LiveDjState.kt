package com.example.resonant.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Intro shown on the Live screen card (✦) while it is read, or for a few seconds in subtitle mode. */
data class LiveDjSubtitle(val key: String, val text: String)

/**
 * Bridge between the playback service (which owns [com.example.resonant.playback.LiveDjController])
 * and the Live screen. Null when no intro is being presented.
 */
object LiveDjState {
    private val _subtitle = MutableStateFlow<LiveDjSubtitle?>(null)
    val subtitle: StateFlow<LiveDjSubtitle?> = _subtitle.asStateFlow()

    /** The writable flow; only the controller publishes. */
    internal val sink: MutableStateFlow<LiveDjSubtitle?> get() = _subtitle
}
