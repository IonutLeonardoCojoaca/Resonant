package com.example.resonant.streak

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.resonant.workers.StreakReminderWorker
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * One-off reminder at the next 21:00 local time that schedules itself again each day. A periodic
 * 24 h request would drift away from 21:00 and ignore clock changes.
 */
object StreakReminderScheduler {
    private const val WORK_NAME = "streak_reminder"

    fun schedule(context: Context) {
        val delay = StreakReminderPolicy.delayUntilNext(ZonedDateTime.now())
        val request = OneTimeWorkRequestBuilder<StreakReminderWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
