package com.example.resonant.notifications

/**
 * Whether `MainActivity` is visible (set in its `onStart`/`onStop`). While it is, the background
 * sync only advances its mark: the bell already shows the counter.
 */
object AppForegroundState {
    @Volatile
    var isInForeground: Boolean = false
}
