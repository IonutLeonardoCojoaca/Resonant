package com.example.resonant.streak

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Last streak event not yet handled. It survives until the app is in the foreground again (a
 * milestone reached with the screen off is celebrated on return) and [consume] stops it repeating.
 */
open class StreakEventState {
    private val _pending = MutableStateFlow<StreakEvent?>(null)
    val pending: StateFlow<StreakEvent?> = _pending.asStateFlow()

    fun publish(event: StreakEvent) {
        // A later "today counts" must not hide a milestone that has not been celebrated yet.
        _pending.update { current ->
            if (current is StreakEvent.Milestone && event is StreakEvent.QualifiedToday) current else event
        }
    }

    /** Clears [event] only if it is still the pending one, so a newer event is kept. */
    fun consume(event: StreakEvent) {
        _pending.compareAndSet(event, null)
    }
}

/** Published from `MusicPlaybackService`; consumed by `MainActivity`. */
object StreakEventBus : StreakEventState()
