package com.example.resonant.workers

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.resonant.R
import com.example.resonant.data.network.NotificationItemDTO
import com.example.resonant.managers.CapabilitiesManager
import com.example.resonant.managers.NotificationsManager
import com.example.resonant.managers.NotificationsResult
import com.example.resonant.managers.UserManager
import com.example.resonant.notifications.AppForegroundState
import com.example.resonant.notifications.DataStoreNotificationAlertStore
import com.example.resonant.notifications.NotificationAlertPolicy
import com.example.resonant.notifications.NotificationChannels
import com.example.resonant.notifications.NotificationIntents
import com.example.resonant.notifications.NotificationSyncScheduler
import com.example.resonant.ui.activities.MainActivity

/**
 * Every 15 min: reads the newest page of the inbox and shows as a local notification what
 * [NotificationAlertPolicy] allows. No push server involved. Without the capability it cancels
 * itself (`MainActivity` schedules it again when the feature comes back); network errors simply
 * wait for the next run.
 */
class NotificationSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!CapabilitiesManager.refresh(context).notifications) {
            NotificationSyncScheduler.cancel(context)
            return Result.success()
        }
        val userId = UserManager(context).getUserId()?.takeIf(String::isNotBlank) ?: return Result.success()

        val page = when (val result = NotificationsManager.get(context).getPage(cursor = null, limit = PAGE_LIMIT)) {
            is NotificationsResult.Ok -> result.value
            NotificationsResult.Disabled -> {
                NotificationSyncScheduler.cancel(context)
                return Result.success()
            }
            NotificationsResult.Failed -> return Result.success()
        }

        val store = DataStoreNotificationAlertStore(context)
        val decision = NotificationAlertPolicy.decide(
            items = page.items.orEmpty(),
            mark = store.load(),
            userId = userId,
            nowMs = System.currentTimeMillis(),
            canShow = canPostNotifications() && !AppForegroundState.isInForeground
        )
        if (decision.toShow.isNotEmpty()) {
            NotificationChannels.ensureCreated(context)
            decision.toShow.forEach(::show)
        }
        decision.newMark?.let { store.save(it) }
        Log.d(TAG, "sync: shown=${decision.toShow.size}")
        return Result.success()
    }

    private fun canPostNotifications(): Boolean {
        val granted = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()
    }

    // Only called after canPostNotifications(); a late revocation is caught below.
    @SuppressLint("MissingPermission")
    private fun show(item: NotificationItemDTO) {
        val context = applicationContext
        val requestCode = item.id.hashCode()
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // A distinct action keeps each PendingIntent (and its extras) separate.
            .setAction("$ACTION_OPEN_PREFIX${item.id}")
            .putExtra(NotificationIntents.EXTRA_NOTIFICATION_ID, item.id)
            .putExtra(NotificationIntents.EXTRA_DEEP_LINK, item.deepLink)
            .putExtra(NotificationIntents.EXTRA_WAS_UNREAD, item.readAtUtcMs == null)
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, NotificationChannels.channelFor(item.category))
            .setSmallIcon(R.drawable.ic_notifications_bell)
            .setContentTitle(item.title)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setWhen(item.createdAtUtcMs)
            .setShowWhen(true)
        item.body?.takeIf(String::isNotBlank)?.let {
            builder.setContentText(it)
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(it))
        }
        item.expiresAtUtcMs?.let { expires ->
            val timeout = expires - System.currentTimeMillis()
            if (timeout > 0) builder.setTimeoutAfter(timeout)
        }
        try {
            NotificationManagerCompat.from(context).notify(TAG, requestCode, builder.build())
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
            Log.w(TAG, "notify denied")
        }
    }

    companion object {
        private const val TAG = "NotificationSync"
        private const val PAGE_LIMIT = 20
        private const val ACTION_OPEN_PREFIX = "com.example.resonant.action.OPEN_NOTIFICATION."
    }
}
