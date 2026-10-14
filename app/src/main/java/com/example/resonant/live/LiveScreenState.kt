package com.example.resonant.live

import com.example.resonant.data.models.Song

enum class LiveRowState { PLAYED, CURRENT, UPCOMING }

/** One row of the block's programme on the broadcast screen. */
data class LiveScheduleRow(
    val key: String,
    val title: String?,
    val artist: String?,
    val imageUrl: String?,
    val state: LiveRowState
)

enum class LiveScreenButton { LISTEN, LEAVE, NONE }

/** Everything the broadcast screen shows at one instant. Null fields are hidden. */
data class LiveScreenState(
    val channelName: String,
    val blockTitle: String?,
    val onAir: Boolean,
    val title: String?,
    val artist: String?,
    val imageUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
    val upNext: String?,
    val rows: List<LiveScheduleRow>,
    val button: LiveScreenButton,
    /** Off air, a gap or the end of the schedule. */
    val message: String?,
    /** Time left before an upcoming special, null otherwise. */
    val countdownMs: Long?
)

/** Builds [LiveScreenState] from the known schedule and the server instant. Pure. */
object LiveScreenStateBuilder {

    fun build(channelName: String, timeline: LiveTimeline, listening: Boolean, nowMs: Long): LiveScreenState {
        val cursor = timeline.at(nowMs)
        val block = timeline.blockAt(nowMs) ?: timeline.blocks.firstOrNull { it.startsAtUtcMs > nowMs }
        val currentSlot = when (cursor) {
            is LiveCursor.Playing -> cursor.slot
            is LiveCursor.Gap -> cursor.slot
            else -> null
        }
        val onAir = cursor is LiveCursor.Playing || cursor is LiveCursor.Gap
        val nextSong = currentSlot?.let { current ->
            timeline.slots.firstOrNull { it.startUtcMs > current.startUtcMs && it.song != null }
        }
        return LiveScreenState(
            channelName = channelName,
            blockTitle = block?.title,
            onAir = onAir,
            title = currentSlot?.song?.title,
            artist = currentSlot?.song?.let(::artistOf),
            imageUrl = currentSlot?.song?.coverUrl,
            positionMs = (cursor as? LiveCursor.Playing)?.positionMs ?: 0L,
            durationMs = currentSlot?.durationMs ?: 0L,
            upNext = nextSong?.song?.let(::lineOf),
            rows = block?.let { rowsOf(timeline, it.blockId, nowMs) }.orEmpty(),
            button = when {
                listening -> LiveScreenButton.LEAVE
                onAir -> LiveScreenButton.LISTEN
                else -> LiveScreenButton.NONE
            },
            message = messageOf(cursor, nowMs),
            countdownMs = (cursor as? LiveCursor.NotStarted)?.let { (it.startsAtMs - nowMs).coerceAtLeast(0L) }
        )
    }

    /** "1 h 05 min" from an hour on, "12:34" below. */
    fun formatCountdown(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) + 999L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "$hours h %02d min".format(minutes) else "%02d:%02d".format(minutes, seconds)
    }

    private fun messageOf(cursor: LiveCursor, nowMs: Long): String? = when (cursor) {
        is LiveCursor.Gap -> cursor.resumesAtMs
            ?.let { LiveErrors.gapMessage((it - nowMs + 999L) / 1000L) }
            ?: LiveErrors.MESSAGE_ENDED
        LiveCursor.Ended -> LiveErrors.MESSAGE_OFF_AIR
        else -> null
    }

    private fun rowsOf(timeline: LiveTimeline, blockId: String, nowMs: Long): List<LiveScheduleRow> =
        timeline.slots.filter { it.blockId == blockId }.map { slot ->
            LiveScheduleRow(
                key = slot.key,
                title = slot.song?.title,
                artist = slot.song?.let(::artistOf),
                imageUrl = slot.song?.coverUrl,
                state = when {
                    slot.endUtcMs <= nowMs -> LiveRowState.PLAYED
                    slot.startUtcMs <= nowMs -> LiveRowState.CURRENT
                    else -> LiveRowState.UPCOMING
                }
            )
        }

    private fun artistOf(song: Song): String? =
        song.artists.firstOrNull()?.name?.takeIf(String::isNotBlank) ?: song.artistName

    private fun lineOf(song: Song): String =
        artistOf(song)?.let { "${song.title} · $it" } ?: song.title
}
