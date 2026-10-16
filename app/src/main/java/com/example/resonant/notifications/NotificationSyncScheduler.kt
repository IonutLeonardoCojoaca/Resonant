package com.example.resonant.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.resonant.workers.NotificationSyncWorker
import java.util.concurrent.TimeUnit

/**
 * Single periodic `notification_sync` every 15 min (WorkManager's minimum) with network.
 * `MainActivity` schedules it when the capability is on and cancels it otherwise. KEEP leaves an
 * already scheduled run alone, so calling it on every start does not reset the period.
 */
object NotificationSyncScheduler {
    const val WORK_NAME = "notification_sync"
    private const val INTERVAL_MINUTES = 15L

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<NotificationSyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
