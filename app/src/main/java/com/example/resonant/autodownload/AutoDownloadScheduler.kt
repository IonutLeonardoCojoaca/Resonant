package com.example.resonant.autodownload

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.resonant.managers.AutoDownloadPreferences
import com.example.resonant.managers.AutoDownloadSettings
import com.example.resonant.workers.AutoDownloadWorker
import java.util.concurrent.TimeUnit

/** Restricciones de WorkManager derivadas de las preferencias (lógica pura, testeable). */
data class AutoDownloadConstraintSpec(
    val networkType: NetworkType,
    val requiresCharging: Boolean,
    val requiresStorageNotLow: Boolean
) {
    fun toConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(networkType)
        .setRequiresCharging(requiresCharging)
        .setRequiresStorageNotLow(requiresStorageNotLow)
        .build()

    companion object {
        fun from(settings: AutoDownloadSettings) = AutoDownloadConstraintSpec(
            networkType = if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
            requiresCharging = settings.requiresCharging,
            requiresStorageNotLow = true
        )
    }
}

/**
 * Programa `auto-downloads` cada 6 h y una ejecución inmediata al abrir la app, con las
 * condiciones del usuario. Solo se programa con la capacidad activa (lo decide `MainActivity`);
 * al cambiar las preferencias se vuelve a llamar a [schedule] (política UPDATE).
 */
object AutoDownloadScheduler {
    const val PERIODIC_WORK = "auto-downloads"
    const val IMMEDIATE_WORK = "auto-downloads-now"
    private const val INTERVAL_HOURS = 6L

    suspend fun schedule(context: Context, userId: String, runNow: Boolean) {
        val appContext = context.applicationContext
        val settings = AutoDownloadPreferences(appContext).get(userId)
        val constraints = AutoDownloadConstraintSpec.from(settings).toConstraints()
        val workManager = WorkManager.getInstance(appContext)
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AutoDownloadWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
        )
        if (runNow) {
            workManager.enqueueUniqueWork(
                IMMEDIATE_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AutoDownloadWorker>()
                    .setConstraints(constraints)
                    .build()
            )
        }
    }

    fun cancel(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        workManager.cancelUniqueWork(PERIODIC_WORK)
        workManager.cancelUniqueWork(IMMEDIATE_WORK)
    }
}
