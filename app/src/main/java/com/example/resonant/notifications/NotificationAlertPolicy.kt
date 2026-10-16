package com.example.resonant.notifications

import com.example.resonant.data.network.NotificationItemDTO

/**
 * Last notification already considered by the background sync, per user: its `createdAtUtcMs`
 * and the ids created in that same millisecond (the page is ordered by `createdAt DESC, id DESC`,
 * so a timestamp alone could skip a sibling).
 */
data class NotificationAlertMark(
    val userId: String,
    val createdAtUtcMs: Long,
    val ids: Set<String>
)

/** What a sync run shows and the mark it must save afterwards (null: keep the current one). */
data class NotificationAlertDecision(
    val toShow: List<NotificationItemDTO>,
    val newMark: NotificationAlertMark?
)

/**
 * Decides which inbox items become a system notification. An item is shown only if it is new
 * (after the saved mark), unread, not expired, has `alert = true` and the app can show it (not in
 * the foreground and with permission). Every new item moves the mark forward, shown or not, so
 * nothing pops up later (e.g. after granting the permission or leaving the app).
 */
object NotificationAlertPolicy {
    /** Without a mark (first run, other user) only this recent window is shown, never the backlog. */
    const val FIRST_SYNC_LOOKBACK_MS = 6 * 60 * 60 * 1000L

    /** Cap per run so a long offline period does not flood the shade. */
    const val MAX_PER_RUN = 5

    fun decide(
        items: List<NotificationItemDTO>,
        mark: NotificationAlertMark?,
        userId: String,
        nowMs: Long,
        canShow: Boolean
    ): NotificationAlertDecision {
        val validMark = mark?.takeIf { it.userId == userId }
        val fresh = items.filter { it.id.isNotBlank() && isNew(it, validMark, nowMs) }
        val toShow = if (canShow) {
            fresh.filter { shouldAlert(it, nowMs) }
                .sortedByDescending { it.createdAtUtcMs }
                .take(MAX_PER_RUN)
        } else {
            emptyList()
        }
        // Without a mark the whole page becomes the baseline, so old items are never "new" later.
        val markSource = if (validMark == null) items.filter { it.id.isNotBlank() } else fresh
        return NotificationAlertDecision(toShow, nextMark(markSource, validMark, userId))
    }

    fun shouldAlert(item: NotificationItemDTO, nowMs: Long): Boolean {
        val expires = item.expiresAtUtcMs
        return item.alert && item.readAtUtcMs == null && (expires == null || expires > nowMs)
    }

    private fun isNew(item: NotificationItemDTO, mark: NotificationAlertMark?, nowMs: Long): Boolean {
        if (mark == null) return item.createdAtUtcMs >= nowMs - FIRST_SYNC_LOOKBACK_MS
        return item.createdAtUtcMs > mark.createdAtUtcMs ||
            (item.createdAtUtcMs == mark.createdAtUtcMs && item.id !in mark.ids)
    }

    private fun nextMark(
        fresh: List<NotificationItemDTO>,
        mark: NotificationAlertMark?,
        userId: String
    ): NotificationAlertMark? {
        val newest = fresh.maxOfOrNull { it.createdAtUtcMs } ?: return null
        val idsAtNewest = fresh.filter { it.createdAtUtcMs == newest }.map { it.id }.toSet()
        val ids = if (mark != null && mark.createdAtUtcMs == newest) mark.ids + idsAtNewest else idsAtNewest
        return NotificationAlertMark(userId, newest, ids)
    }
}
