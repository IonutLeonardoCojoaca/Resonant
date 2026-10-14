package com.example.resonant.live

import com.example.resonant.data.models.Song
import com.example.resonant.data.network.LiveBlockDTO
import com.example.resonant.data.network.LiveBlockItemDTO
import com.example.resonant.data.network.LiveChannelInfoDTO
import com.example.resonant.data.network.LiveNowResponseDTO

/** Hand-written builders shared by the Live unit tests. */
object LiveFixtures {
    const val SLUG = "rock"
    const val BLOCK_START = 1_791_639_000_000L

    /** Items back to back; a null id is a song that no longer exists. */
    fun block(id: String, startMs: Long, durations: List<Long>, missing: Set<Int> = emptySet()): LiveBlockDTO {
        var offset = 0L
        val items = durations.mapIndexed { position, duration ->
            LiveBlockItemDTO(
                position = position,
                startOffsetMs = offset,
                durationMs = duration,
                song = if (position in missing) null else Song(id = "$id-song-$position", title = "Song $position")
            ).also { offset += duration }
        }
        return LiveBlockDTO(id, "Resonant Rock · $id", startMs, startMs + offset, items)
    }

    fun now(
        block: LiveBlockDTO?,
        next: LiveBlockDTO? = null,
        serverTimeMs: Long = block?.startsAtUtcMs ?: 0L,
        status: String = if (block != null) "on_air" else if (next != null) "upcoming" else "off_air"
    ) = LiveNowResponseDTO(
        serverTimeUtcMs = serverTimeMs,
        status = status,
        channel = LiveChannelInfoDTO("ch-1", SLUG, "channel", "Resonant Rock"),
        block = block,
        nextBlock = next
    )

    /** Block "b1" with 200/180/240 s songs starting at [BLOCK_START]. */
    fun standardBlock(missing: Set<Int> = emptySet()) =
        block("b1", BLOCK_START, listOf(200_000L, 180_000L, 240_000L), missing)

    fun nextOf(block: LiveBlockDTO, id: String = "b2", durations: List<Long> = listOf(210_000L, 190_000L)) =
        block(id, block.endsAtUtcMs, durations)
}
