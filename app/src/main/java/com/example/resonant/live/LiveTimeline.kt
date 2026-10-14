package com.example.resonant.live

import com.example.resonant.data.models.Song
import com.example.resonant.data.network.LiveBlockDTO
import com.example.resonant.data.network.LiveNowResponseDTO

/** One scheduled song of a block, with absolute server times so offsets never accumulate errors. */
data class LiveSlot(
    val blockId: String,
    val position: Int,
    val startUtcMs: Long,
    val durationMs: Long,
    /** Null when the song no longer exists: the slot keeps its time and plays silence. */
    val song: Song?,
    val djIntro: String? = null
) {
    val endUtcMs: Long get() = startUtcMs + durationMs

    /** Stable identity of the slot, also used as the player queue entry id. */
    val key: String get() = "$blockId:$position"
}

data class LiveBlockInfo(
    val blockId: String,
    val title: String,
    val startsAtUtcMs: Long,
    val endsAtUtcMs: Long
)

/** What the schedule says should sound at a given server instant. */
sealed class LiveCursor {
    /** [positionMs] into [slot]'s song. */
    data class Playing(val slot: LiveSlot, val positionMs: Long) : LiveCursor()

    /**
     * Nothing to play: [slot] has no song or its audio failed. [resumesAt] is the next playable
     * slot, or null when the known schedule has none.
     */
    data class Gap(val slot: LiveSlot?, val resumesAt: LiveSlot?) : LiveCursor() {
        val resumesAtMs: Long? get() = resumesAt?.startUtcMs
    }

    /** The known schedule starts later (an upcoming special). */
    data class NotStarted(val startsAtMs: Long) : LiveCursor()

    /** The known schedule is over. */
    object Ended : LiveCursor()
}

/**
 * The fixed schedule this client knows: the block on air and the next one. Lists are fixed once
 * published, so merging a newer `/now` only adds blocks and refreshes intros. Immutable.
 */
class LiveTimeline private constructor(
    val slots: List<LiveSlot>,
    val blocks: List<LiveBlockInfo>,
    private val failedKeys: Set<String>
) {
    val isEmpty: Boolean get() = slots.isEmpty()

    fun at(serverNowMs: Long): LiveCursor {
        val first = slots.firstOrNull() ?: return LiveCursor.Ended
        if (serverNowMs < first.startUtcMs) return LiveCursor.NotStarted(first.startUtcMs)
        val index = slots.indexOfLast { it.startUtcMs <= serverNowMs }
        val slot = slots[index]
        if (serverNowMs >= slot.endUtcMs) {
            // Past the last slot, or in a hole between two blocks.
            return if (index == slots.lastIndex) LiveCursor.Ended else LiveCursor.Gap(null, nextPlayable(index + 1))
        }
        if (!isPlayable(slot)) return LiveCursor.Gap(slot, nextPlayable(index + 1))
        return LiveCursor.Playing(slot, serverNowMs - slot.startUtcMs)
    }

    fun blockAt(serverNowMs: Long): LiveBlockInfo? =
        blocks.firstOrNull { serverNowMs >= it.startsAtUtcMs && serverNowMs < it.endsAtUtcMs }

    fun endUtcMs(): Long? = slots.lastOrNull()?.endUtcMs

    fun isPlayable(slot: LiveSlot): Boolean = slot.song != null && slot.key !in failedKeys

    /** Slots with a song, failed ones included: they were already handed to the player. */
    fun slotsWithSong(): List<LiveSlot> = slots.filter { it.song != null }

    /** Songs for the player from [from] on, with the slot duration when the song has none. */
    fun songsFrom(from: LiveSlot): List<Pair<String, Song>> =
        slotsWithSong().dropWhile { it.startUtcMs < from.startUtcMs }.map { it.key to songOf(it) }

    fun markFailed(key: String): LiveTimeline =
        if (key in failedKeys) this else LiveTimeline(slots, blocks, failedKeys + key)

    /**
     * Adds the blocks of [response] this timeline does not have, refreshes the intros of the ones
     * it has, and forgets blocks that ended before [serverNowMs].
     */
    fun merge(response: LiveNowResponseDTO, serverNowMs: Long): LiveTimeline {
        val incoming = listOfNotNull(response.block, response.nextBlock).associateBy(LiveBlockDTO::blockId)
        val known = blocks.map(LiveBlockInfo::blockId).toSet()
        val refreshed = slots.map { slot ->
            val item = incoming[slot.blockId]?.items?.firstOrNull { it.position == slot.position }
            if (item != null && item.djIntro != slot.djIntro) slot.copy(djIntro = item.djIntro) else slot
        }
        val added = incoming.values.filter { it.blockId !in known }
        val keptBlocks = (blocks + added.map(::infoOf)).filter { it.endsAtUtcMs > serverNowMs }
        val keptIds = keptBlocks.map(LiveBlockInfo::blockId).toSet()
        val allSlots = (refreshed + added.flatMap(::slotsOf))
            .filter { it.blockId in keptIds }
            .sortedBy(LiveSlot::startUtcMs)
        return LiveTimeline(allSlots, keptBlocks.sortedBy(LiveBlockInfo::startsAtUtcMs), failedKeys)
    }

    private fun nextPlayable(from: Int): LiveSlot? =
        slots.drop(from).firstOrNull(::isPlayable)

    companion object {
        val EMPTY = LiveTimeline(emptyList(), emptyList(), emptySet())

        fun from(response: LiveNowResponseDTO): LiveTimeline =
            EMPTY.merge(response, Long.MIN_VALUE)

        private fun infoOf(block: LiveBlockDTO) =
            LiveBlockInfo(block.blockId, block.title, block.startsAtUtcMs, block.endsAtUtcMs)

        private fun slotsOf(block: LiveBlockDTO): List<LiveSlot> =
            block.items.orEmpty().map { item ->
                LiveSlot(
                    blockId = block.blockId,
                    position = item.position,
                    startUtcMs = block.startsAtUtcMs + item.startOffsetMs,
                    durationMs = item.durationMs,
                    song = item.song,
                    djIntro = item.djIntro
                )
            }

        private fun songOf(slot: LiveSlot): Song {
            val song = requireNotNull(slot.song)
            if (!song.duration.isNullOrBlank() || slot.durationMs <= 0L) return song
            return song.copy(duration = (slot.durationMs / 1000L).toString())
        }
    }
}
