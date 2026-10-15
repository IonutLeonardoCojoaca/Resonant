package com.example.resonant.workers

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
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
import com.example.resonant.data.network.StreakStatusDTO
import com.example.resonant.managers.CapabilitiesManager
import com.example.resonant.managers.SettingsManager
import com.example.resonant.managers.StreakManager
import com.example.resonant.streak.StreakReminderPolicy
import com.example.resonant.streak.StreakReminderScheduler
import com.example.resonant.streak.StreakResult
import com.example.resonant.ui.activities.MainActivity
import kotlinx.coroutines.flow.first

/**
 * 21:00 local check: notifies when the streak is at risk and schedules tomorrow's check. It stops
 * (without rescheduling) when the setting or the streaks feature is off; `MainActivity` schedules it
 * again when they come back.
 */
class StreakReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!SettingsManager(applicationContext).streakReminderEnabledFlow.first()) return Result.success()
        if (!CapabilitiesManager.refresh(applicationContext).streaks) return Result.success()

        val result = StreakManager.get(applicationContext).getStatus(force = true)
        if (result is StreakResult.Ok && StreakReminderPolicy.shouldNotify(result.status)) {
            notify(result.status)
        } else {
            Log.d(TAG, "no reminder: ${result.javaClass.simpleName}")
        }
        // A late reminder is pointless, so failures are not retried: just try again tomorrow.
        StreakReminderScheduler.schedule(applicationContext)
        return Result.success()
    }

    private fun notify(status: StreakStatusDTO) {
        val granted = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!granted || !manager.areNotificationsEnabled()) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.streak_reminder_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val openApp = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_streak_flame)
            .setContentTitle(applicationContext.getString(R.string.streak_reminder_title))
            .setContentText(StreakReminderPolicy.message(status))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "StreakReminder"
        private const val CHANNEL_ID = "streak_reminder"
        private const val NOTIFICATION_ID = 2101
    }
}
