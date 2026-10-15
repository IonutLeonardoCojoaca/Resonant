package com.example.resonant.streak

import com.example.resonant.data.models.Song

/** What the song of the day card shows. */
sealed class SongOfDayUi {
    object Hidden : SongOfDayUi()
    object Loading : SongOfDayUi()
    data class Ready(val song: Song, val reason: String, val isAria: Boolean) : SongOfDayUi()

    /** [retryAfterSeconds] is set when the card must ask again after that wait. */
    data class Step(val ui: SongOfDayUi, val retryAfterSeconds: Int? = null)

    companion object {
        fun from(result: SongOfDayResult, retriesDone: Int, policy: SongOfDayRetryPolicy = SongOfDayRetryPolicy): Step =
            when (result) {
                is SongOfDayResult.Ready -> Step(Ready(result.song, result.reason, result.isAria))
                is SongOfDayResult.Pending ->
                    if (policy.canRetry(retriesDone)) Step(Loading, policy.delaySeconds(result.retryAfterSeconds))
                    else Step(Hidden)
                SongOfDayResult.Empty, SongOfDayResult.Disabled, SongOfDayResult.Failed -> Step(Hidden)
            }
    }
}
