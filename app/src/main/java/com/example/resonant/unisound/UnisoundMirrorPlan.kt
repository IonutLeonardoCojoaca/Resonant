package com.example.resonant.unisound

import com.example.resonant.data.models.Song
import com.example.resonant.data.network.UnisoundQueueItemDTO

/** What the local player mirrors of a room: enough to tell what changed between states. */
data class UnisoundMirror(
    val sessionId: String,
    val currentItemId: String?,
    val queueItemIds: List<String>
)

enum class UnisoundMirrorChange {
    /** Nothing to mirror before or after. */
    NONE,

    /** Load a new current song (entering the room, a skip or the server advancing). */
    REBUILD,

    /** Same current song, the pending queue changed. */
    QUEUE,

    /** Only play/pause/position/local hold may have changed. */
    STATE,

    /** The room is gone for this device. */
    EXIT
}

object UnisoundMirrorPlan {

    fun mirrorOf(state: UnisoundRoomState?): UnisoundMirror? = state?.let {
        UnisoundMirror(it.sessionId, it.current?.itemId, it.queue.map(UnisoundQueueItemDTO::itemId))
    }

    fun diff(previous: UnisoundMirror?, next: UnisoundMirror?): UnisoundMirrorChange = when {
        previous == null && next == null -> UnisoundMirrorChange.NONE
        next == null -> UnisoundMirrorChange.EXIT
        previous == null ||
            previous.sessionId != next.sessionId ||
            previous.currentItemId != next.currentItemId -> UnisoundMirrorChange.REBUILD
        previous.queueItemIds != next.queueItemIds -> UnisoundMirrorChange.QUEUE
        else -> UnisoundMirrorChange.STATE
    }

    /** Current song followed by the pending queue, as the player's song list. */
    fun songsOf(state: UnisoundRoomState): List<Song> =
        (listOfNotNull(state.current) + state.queue).map(::songOf)

    fun entryIdsOf(state: UnisoundRoomState): List<String> =
        (listOfNotNull(state.current) + state.queue).map(UnisoundQueueItemDTO::itemId)

    /**
     * True while the player and the room are a few hundred ms apart around a song change:
     * either the player already moved to the room's next song, or the room moved on and the
     * player is about to. Correcting (or reloading) there would cut the song audibly.
     */
    fun isCrossingSongBoundary(
        state: UnisoundRoomState,
        playerMediaId: String?,
        playerNextMediaId: String?,
        playerRemainingMs: Long,
        expectedPositionMs: Long
    ): Boolean {
        val current = state.current ?: return false
        val ranAhead = playerMediaId != null &&
            playerMediaId == state.queue.firstOrNull()?.songId &&
            current.durationMs - expectedPositionMs in 0..SONG_BOUNDARY_GRACE_MS
        val aboutToCatchUp = playerMediaId != current.songId &&
            playerNextMediaId == current.songId &&
            playerRemainingMs in 0..SONG_BOUNDARY_GRACE_MS
        return ranAhead || aboutToCatchUp
    }

    const val SONG_BOUNDARY_GRACE_MS = 2_000L

    private fun songOf(item: UnisoundQueueItemDTO): Song {
        val song = item.song ?: Song(id = item.songId)
        if (!song.duration.isNullOrBlank() || item.durationMs <= 0L) return song
        return song.copy(duration = (item.durationMs / 1000L).toString())
    }
}
