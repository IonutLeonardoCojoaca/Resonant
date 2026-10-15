package com.example.resonant.streak

/**
 * Waits between song of the day requests while Aria prepares today's picks (`202`): the server's
 * `retryAfterSeconds` bounded to [MIN_SECONDS]..[MAX_SECONDS], at most [MAX_RETRIES] per opening of
 * Explorar. The caller counts the retries and starts again from 0 on each opening.
 */
object SongOfDayRetryPolicy {
    const val MAX_RETRIES = 4
    private const val DEFAULT_SECONDS = 15
    private const val MIN_SECONDS = 5
    private const val MAX_SECONDS = 60

    fun delaySeconds(retryAfterSeconds: Int?): Int =
        if (retryAfterSeconds == null || retryAfterSeconds <= 0) DEFAULT_SECONDS
        else retryAfterSeconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

    /** [retriesDone] retries were already made in this opening. */
    fun canRetry(retriesDone: Int): Boolean = retriesDone < MAX_RETRIES
}
