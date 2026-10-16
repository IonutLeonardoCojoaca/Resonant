package com.example.resonant.streak

import com.example.resonant.data.models.Song
import com.example.resonant.data.network.StreakStatusDTO

/** Streak change caused by a reported listen (`streakEvent` of `POST api/analytics/stream`). */
sealed class StreakEvent {
    abstract val days: Int

    data class QualifiedToday(override val days: Int) : StreakEvent()
    data class Milestone(override val days: Int) : StreakEvent()
}

sealed class StreakResult {
    data class Ok(val status: StreakStatusDTO) : StreakResult()
    /** 404 `FEATURE_DISABLED`: refresh capabilities and hide the streak. */
    object Disabled : StreakResult()
    object Failed : StreakResult()
}

sealed class SongOfDayResult {
    data class Ready(
        val song: Song,
        val reason: String,
        val isAria: Boolean,
        val date: String,
        /** `requestId` de la impresión `SONG_OF_DAY` (estable durante el día); `null` sin telemetría. */
        val requestId: String? = null
    ) : SongOfDayResult()
    /** 202 while Aria prepares today's picks. */
    data class Pending(val retryAfterSeconds: Int?) : SongOfDayResult()
    /** 204 or an unusable song: nothing to show today for now. */
    object Empty : SongOfDayResult()
    object Disabled : SongOfDayResult()
    object Failed : SongOfDayResult()
}
