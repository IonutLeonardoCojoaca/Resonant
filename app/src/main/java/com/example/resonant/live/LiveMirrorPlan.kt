package com.example.resonant.live

import com.example.resonant.unisound.UnisoundDriftAction
import com.example.resonant.unisound.UnisoundDriftPolicy

enum class LiveListenStatus {
    LISTENING,

    /** Paused locally (pause, focus loss, unplugged headphones): the broadcast goes on. */
    HELD
}

/** What the local player mirrors of a Live session: enough to tell what changed between states. */
data class LiveMirror(
    val slug: String,
    val status: LiveListenStatus,
    val songSlotKeys: List<String>
)

enum class LiveMirrorChange {
    NONE,

    /** Load the schedule from the current point (entering a channel). */
    ENTER,

    /** New slots at the end (the next block arrived): add them without cutting. */
    APPEND,

    HOLD,

    /** Load again from the current point: the player may be far behind after a hold. */
    REJOIN,

    EXIT
}

/** The player as the 500 ms loop sees it. [index] is the position in the player queue. */
data class LivePlayerSnapshot(
    val index: Int?,
    val positionMs: Long,
    val remainingMs: Long,
    val isPlaying: Boolean,
    val isBuffering: Boolean
)

sealed class LiveTickAction {
    object None : LiveTickAction()

    /** Jump to [index] at [positionMs] and play. */
    data class SeekTo(val index: Int, val positionMs: Long) : LiveTickAction()

    /** Same song, small drift: let the shared drift policy act. */
    data class Drift(val action: UnisoundDriftAction) : LiveTickAction()

    /** Silence until the next playable slot: pause, parked at the start of [nextIndex] if known. */
    data class HoldForGap(val nextIndex: Int?) : LiveTickAction()

    /** The current slot is not in the player queue: load it again from the current point. */
    object Rebuild : LiveTickAction()

    /** The known schedule is over. */
    object Ended : LiveTickAction()
}

object LiveMirrorPlan {
    /** Around a song change the player and the schedule may be this far apart without correcting. */
    const val SONG_BOUNDARY_GRACE_MS = 2_000L

    fun mirrorOf(slug: String?, status: LiveListenStatus?, timeline: LiveTimeline?): LiveMirror? {
        if (slug == null || status == null || timeline == null) return null
        return LiveMirror(slug, status, timeline.slotsWithSong().map(LiveSlot::key))
    }

    fun diff(previous: LiveMirror?, next: LiveMirror?): LiveMirrorChange = when {
        previous == null && next == null -> LiveMirrorChange.NONE
        next == null -> LiveMirrorChange.EXIT
        previous == null || previous.slug != next.slug -> LiveMirrorChange.ENTER
        previous.status == LiveListenStatus.LISTENING && next.status == LiveListenStatus.HELD -> LiveMirrorChange.HOLD
        previous.status == LiveListenStatus.HELD && next.status == LiveListenStatus.LISTENING -> LiveMirrorChange.REJOIN
        next.songSlotKeys.any { it !in previous.songSlotKeys } -> LiveMirrorChange.APPEND
        else -> LiveMirrorChange.NONE
    }

    /**
     * One step of the listening loop. [queuedKeys] are the slot keys of the player queue, in order.
     * [drift] is stateful and only used from the main thread.
     */
    fun tick(
        cursor: LiveCursor,
        queuedKeys: List<String>,
        player: LivePlayerSnapshot,
        drift: UnisoundDriftPolicy,
        nowMs: Long
    ): LiveTickAction = when (cursor) {
        LiveCursor.Ended -> LiveTickAction.Ended
        is LiveCursor.NotStarted -> parkFor(if (queuedKeys.isEmpty()) null else 0, player)
        is LiveCursor.Gap -> parkFor(cursor.resumesAt?.let { queuedKeys.indexOf(it.key) }?.takeIf { it >= 0 }, player)
        is LiveCursor.Playing -> playing(cursor, queuedKeys.indexOf(cursor.slot.key), player, drift, nowMs)
    }

    private fun parkFor(nextIndex: Int?, player: LivePlayerSnapshot): LiveTickAction {
        val parked = !player.isPlaying && (nextIndex == null || player.index == nextIndex)
        return if (parked) LiveTickAction.None else LiveTickAction.HoldForGap(nextIndex)
    }

    private fun playing(
        cursor: LiveCursor.Playing,
        index: Int,
        player: LivePlayerSnapshot,
        drift: UnisoundDriftPolicy,
        nowMs: Long
    ): LiveTickAction {
        if (index < 0) return LiveTickAction.Rebuild
        val slotRemainingMs = cursor.slot.durationMs - cursor.positionMs
        if (!player.isPlaying && !player.isBuffering) {
            // Parked after a gap, or stopped by an audio error.
            drift.reset()
            return LiveTickAction.SeekTo(index, cursor.positionMs)
        }
        if (player.index != index) {
            val aboutToCatchUp = player.index == index - 1 &&
                cursor.positionMs <= SONG_BOUNDARY_GRACE_MS && player.remainingMs in 0..SONG_BOUNDARY_GRACE_MS
            val ranAhead = player.index == index + 1 && slotRemainingMs in 0..SONG_BOUNDARY_GRACE_MS
            if (aboutToCatchUp || ranAhead) return LiveTickAction.None
            drift.reset()
            return LiveTickAction.SeekTo(index, cursor.positionMs)
        }
        if (slotRemainingMs <= SONG_BOUNDARY_GRACE_MS) return LiveTickAction.None
        return when (val action = drift.decide(cursor.positionMs, player.positionMs, true, player.isBuffering, nowMs)) {
            UnisoundDriftAction.None -> LiveTickAction.None
            else -> LiveTickAction.Drift(action)
        }
    }
}
