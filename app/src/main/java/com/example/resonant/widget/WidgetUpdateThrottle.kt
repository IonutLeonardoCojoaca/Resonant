package com.example.resonant.widget

/** Whether observation of the playback state must start, stop or stay as it is. */
enum class WidgetObservationChange { START, STOP, NONE }

/**
 * Pure batching and on/off rules of [WidgetUpdateCoordinator] (design D4), with an injectable clock.
 *
 * - The widget observes only while the service is attached **and** there is at least one widget.
 * - Every signal moves the repaint [debounceMs] later, but never more than [maxDelayMs] after the
 *   first pending signal, so a burst of skips gives one repaint and the widget is never more than
 *   ~1 s behind the notification.
 * - Starting to observe asks for an immediate repaint.
 *
 * Not thread-safe: the coordinator calls it on the main thread only.
 */
class WidgetUpdateThrottle(
    private val clock: () -> Long,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val maxDelayMs: Long = MAX_DELAY_MS
) {
    var attached: Boolean = false
        private set
    var widgetCount: Int = 0
        private set

    val isObserving: Boolean get() = attached && widgetCount > 0

    private var firstPendingAtMs: Long? = null
    private var dueAtMs: Long? = null
    private var pendingSignals = 0

    /** New attach state or widget count. On START a repaint is already due. */
    fun update(attached: Boolean, widgetCount: Int): WidgetObservationChange {
        val wasObserving = isObserving
        this.attached = attached
        this.widgetCount = widgetCount.coerceAtLeast(0)
        return when {
            !wasObserving && isObserving -> {
                val now = clock()
                firstPendingAtMs = now
                dueAtMs = now
                pendingSignals = 1
                WidgetObservationChange.START
            }
            wasObserving && !isObserving -> {
                clearPending()
                WidgetObservationChange.STOP
            }
            else -> WidgetObservationChange.NONE
        }
    }

    /** Records a state change. Returns the delay until the repaint, or null when not observing. */
    fun signal(): Long? {
        if (!isObserving) return null
        val now = clock()
        val first = firstPendingAtMs ?: now.also { firstPendingAtMs = it }
        pendingSignals++
        val due = minOf(now + debounceMs, first + maxDelayMs)
        dueAtMs = due
        return (due - now).coerceAtLeast(0L)
    }

    /** Delay until the pending repaint, or null when there is none. */
    fun delayUntilDue(): Long? = dueAtMs?.let { (it - clock()).coerceAtLeast(0L) }

    /**
     * Timer callback. Returns how many signals the repaint batches (0 = nothing to do now: not due
     * yet, nothing pending or no longer observing).
     */
    fun fire(): Int {
        val due = dueAtMs ?: return 0
        if (!isObserving) {
            clearPending()
            return 0
        }
        if (clock() < due) return 0
        val batched = pendingSignals
        clearPending()
        return batched
    }

    private fun clearPending() {
        firstPendingAtMs = null
        dueAtMs = null
        pendingSignals = 0
    }

    companion object {
        const val DEBOUNCE_MS = 300L
        const val MAX_DELAY_MS = 900L
    }
}
