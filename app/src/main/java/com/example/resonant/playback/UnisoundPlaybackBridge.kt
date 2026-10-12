package com.example.resonant.playback

import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One-shot hand-off from [com.example.resonant.services.MusicPlaybackService] to the visible
 * Activity: the user started their own music while in a room. The Activity asks
 * "Saldrás de la sala" and, if confirmed, leaves the room and re-sends [Intent].
 */
object UnisoundPlaybackBridge {
    private val _conflicts = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val conflicts: SharedFlow<Intent> = _conflicts.asSharedFlow()

    /** False when no screen is collecting (app in background): the caller drops the action. */
    fun offerConflict(playIntent: Intent): Boolean =
        _conflicts.subscriptionCount.value > 0 && _conflicts.tryEmit(playIntent)
}
