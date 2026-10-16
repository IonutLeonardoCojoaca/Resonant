package com.example.resonant.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.example.resonant.R
import com.example.resonant.data.network.NotificationCategories

/**
 * System channels for inbox alerts, one per category with its own reason to be silenced
 * (Aria, Unisound, Live specials) plus a common one. The existing streak reminder and update
 * channels are not touched.
 */
object NotificationChannels {
    const val ARIA = "inbox_aria"
    const val UNISOUND = "inbox_unisound"
    const val LIVE = "inbox_live"
    const val GENERAL = "inbox_general"

    fun channelFor(category: String): String = when (category) {
        NotificationCategories.ARIA -> ARIA
        NotificationCategories.UNISOUND -> UNISOUND
        NotificationCategories.LIVE -> LIVE
        else -> GENERAL
    }

    /** Idempotent: creating an existing channel only updates its name. */
    fun ensureCreated(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(ARIA, context.getString(R.string.notifications_channel_aria), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(UNISOUND, context.getString(R.string.notifications_channel_unisound), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(LIVE, context.getString(R.string.notifications_channel_live), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(GENERAL, context.getString(R.string.notifications_channel_general), NotificationManager.IMPORTANCE_DEFAULT)
            )
        )
    }
}

/** Extras of the `PendingIntent` of a system notification, read by `MainActivity.handleDeepLink`. */
object NotificationIntents {
    const val EXTRA_NOTIFICATION_ID = "com.example.resonant.extra.NOTIFICATION_ID"
    const val EXTRA_DEEP_LINK = "com.example.resonant.extra.NOTIFICATION_DEEP_LINK"
    const val EXTRA_WAS_UNREAD = "com.example.resonant.extra.NOTIFICATION_WAS_UNREAD"
}
