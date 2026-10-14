package com.example.resonant.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.example.resonant.unisound.UnisoundControl

/**
 * Player handed to the MediaSession so system controls (notification, lock screen, headset,
 * Bluetooth) reach the room (or the Live broadcast) instead of the local player.
 * [intercept] returns true when it took the control; otherwise the call goes through.
 * While [restrictControls] is true (Live) the session does not offer seek, skip, shuffle or repeat.
 * The service keeps driving its own ExoPlayer directly, never through this wrapper.
 */
@OptIn(UnstableApi::class)
class UnisoundAwarePlayer(
    player: Player,
    private val restrictControls: () -> Boolean = { false },
    private val intercept: (control: UnisoundControl, positionMs: Long?) -> Boolean
) : ForwardingPlayer(player) {

    override fun isCommandAvailable(command: Int): Boolean =
        !(restrictControls() && command in RESTRICTED_COMMANDS) && super.isCommandAvailable(command)

    override fun getAvailableCommands(): Player.Commands {
        val commands = super.getAvailableCommands()
        if (!restrictControls()) return commands
        return commands.buildUpon().removeAll(*RESTRICTED_COMMANDS).build()
    }

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

    companion object {
        /** What a Live listener cannot do: only play (rejoin) and pause (hold) stay. */
        val RESTRICTED_COMMANDS = intArrayOf(
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_SET_REPEAT_MODE
        )
    }
}
