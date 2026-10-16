package com.example.resonant.notifications

/** Text of the bell's counter: nothing for 0, the number up to 99 and "99+" above. */
object NotificationBadge {
    const val MAX_SHOWN = 99

    fun format(unread: Int): String? = when {
        unread <= 0 -> null
        unread > MAX_SHOWN -> "$MAX_SHOWN+"
        else -> unread.toString()
    }
}
