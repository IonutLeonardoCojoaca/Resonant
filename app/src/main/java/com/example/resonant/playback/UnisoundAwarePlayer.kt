package com.example.resonant.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.example.resonant.unisound.UnisoundControl

/**
 * Player handed to the MediaSession so system controls (notification, lock screen, headset,
 * Bluetooth) reach the room instead of the local player while in a Unisound room.
 * [intercept] returns true when it took the control; otherwise the call goes through.
 * The service keeps driving its own ExoPlayer directly, never through this wrapper.
 */
@OptIn(UnstableApi::class)
class UnisoundAwarePlayer(
    player: Player,
    private val intercept: (control: UnisoundControl, positionMs: Long?) -> Boolean
) : ForwardingPlayer(player) {

    override fun play() {
        if (!intercept(UnisoundControl.PLAY, null)) super.play()
    }

    override fun pause() {
        if (!intercept(UnisoundControl.PAUSE, null)) super.pause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        val control = if (playWhenReady) UnisoundControl.PLAY else UnisoundControl.PAUSE
        if (!intercept(control, null)) super.setPlayWhenReady(playWhenReady)
    }

    override fun seekToNext() {
        if (!intercept(UnisoundControl.NEXT, null)) super.seekToNext()
    }

    override fun seekToNextMediaItem() {
        if (!intercept(UnisoundControl.NEXT, null)) super.seekToNextMediaItem()
    }

    override fun seekToPrevious() {
        if (!intercept(UnisoundControl.PREVIOUS, null)) super.seekToPrevious()
    }

    override fun seekToPreviousMediaItem() {
        if (!intercept(UnisoundControl.PREVIOUS, null)) super.seekToPreviousMediaItem()
    }

    override fun seekTo(positionMs: Long) {
        if (!intercept(UnisoundControl.SEEK, positionMs)) super.seekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (!intercept(UnisoundControl.SEEK, positionMs)) super.seekTo(mediaItemIndex, positionMs)
    }
}
