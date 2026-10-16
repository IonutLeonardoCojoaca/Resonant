package com.example.resonant.widget

import com.example.resonant.data.models.Song
import com.example.resonant.live.LiveListenStatus
import com.example.resonant.live.LiveSessionState
import com.example.resonant.playback.QueueSource
import com.example.resonant.unisound.UnisoundRoomState

/**
 * Builds the widget picture from the same state the mini-player and the media notification
 * read (`PlaybackStateRepository`), plus the Live and Unisound managers for the mode.
 */
object WidgetStateMapper {

    fun map(
        song: Song?,
        isPlaying: Boolean,
        source: QueueSource?,
        live: LiveSessionState?,
        room: UnisoundRoomState?
    ): WidgetState {
        if (song == null) return WidgetState.EMPTY
        val mode = when {
            source == QueueSource.LIVE && live != null -> WidgetMode.LIVE
            source == QueueSource.UNISOUND && room != null -> WidgetMode.UNISOUND
            else -> WidgetMode.NORMAL
        }
        val playing = when (mode) {
            // HELD is a local pause while the broadcast goes on.
            WidgetMode.LIVE -> isPlaying && live?.status != LiveListenStatus.HELD
            else -> isPlaying
        }
        return WidgetState(
            title = titleOf(song),
            artist = artistOf(song),
            hasSong = true,
            isPlaying = playing,
            mode = mode,
            liveChannelName = if (mode == WidgetMode.LIVE) live?.let { it.channel.name.ifBlank { it.slug } } else null,
            canControl = if (mode == WidgetMode.UNISOUND) room?.canControl == true else true
        )
    }

    /** Same cleanup as the mini-player (`MainActivity.updateDataPlayer`). */
    fun titleOf(song: Song): String = song.title.removeSuffix(".mp3").trim()

    /** Same rule as the mini-player: `artistName`, or the artists' names joined. */
    fun artistOf(song: Song): String =
        song.artistName?.takeIf { it.isNotBlank() }
            ?: song.artists.joinToString(", ") { it.name }
}
