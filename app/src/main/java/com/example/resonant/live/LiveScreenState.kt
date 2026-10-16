package com.example.resonant.live

import com.example.resonant.data.models.Song
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

/** Next edition of an off-air special, from `/now`'s `channel.nextOccurrenceUtcMs` and its schedule. */
data class LiveNextEdition(val startsAtMs: Long, val durationMinutes: Int?)

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
    val countdownMs: Long?,
    /** Off-air special with a known next edition ("Vuelve el…"); no listen button. */
    val scheduled: LiveNextEdition? = null
)

/** Builds [LiveScreenState] from the known schedule and the server instant. Pure. */
object LiveScreenStateBuilder {

    /** From this close to the next edition, `/now` is asked again so the countdown shows once the block exists. */
    const val SCHEDULED_REFRESH_WINDOW_MS = 12L * 60L * 60L * 1000L

    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    /** Fixed Spanish abbreviations, like [LiveCardMapper.DAYS]: the JDK's are not stable between versions. */
    private val MONTHS = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

    fun build(
        channelName: String,
        timeline: LiveTimeline,
        listening: Boolean,
        nowMs: Long,
        nextEdition: LiveNextEdition? = null,
        zone: ZoneId = ZoneId.systemDefault()
    ): LiveScreenState {
        val cursor = timeline.at(nowMs)
        val scheduled = nextEdition?.takeIf { cursor == LiveCursor.Ended && !listening && it.startsAtMs > nowMs }
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
            message = scheduled?.let { scheduledText(it, zone) } ?: messageOf(cursor, nowMs),
            countdownMs = (cursor as? LiveCursor.NotStarted)?.let { (it.startsAtMs - nowMs).coerceAtLeast(0L) },
            scheduled = scheduled
        )
    }

    /** "Vuelve el vie 23 oct a las 22:00 · 90 min" in [zone]; without duration if unknown. */
    fun scheduledText(edition: LiveNextEdition, zone: ZoneId): String {
        val start = Instant.ofEpochMilli(edition.startsAtMs).atZone(zone)
        val day = LiveCardMapper.DAYS.getValue(start.dayOfWeek)
        val month = MONTHS[start.monthValue - 1]
        val text = "Vuelve el $day ${start.dayOfMonth} $month a las ${start.format(TIME)}"
        val minutes = edition.durationMinutes?.takeIf { it > 0 } ?: return text
        return "$text · $minutes min"
    }

    /** True when the next edition is 12 h away or less: its block may already exist. */
    fun shouldRefreshScheduled(edition: LiveNextEdition, nowMs: Long): Boolean =
        edition.startsAtMs - nowMs <= SCHEDULED_REFRESH_WINDOW_MS

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
