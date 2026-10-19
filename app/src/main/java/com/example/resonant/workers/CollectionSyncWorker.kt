package com.example.resonant.workers

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.resonant.managers.OfflineCollectionManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Cada 12 h, con Wi-Fi y sin batería baja: sincroniza las playlists y Playmix descargadas con el servidor
 * (collection-queue-offline D6). Descarga lo nuevo, borra lo sobrante y marca "Ya no disponible" en 404/403.
 * Un fallo de red espera a la siguiente ejecución.
 */
class CollectionSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val synced = OfflineCollectionManager(applicationContext).syncAll()
            Log.i(TAG, "Colecciones descargadas sincronizadas: $synced")
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Sincronización de colecciones fallida", error)
            Result.success()
        }
    }

    companion object {
        private const val TAG = "CollectionSync"
        private const val WORK_NAME = "collection_offline_sync"
        private const val INTERVAL_HOURS = 12L

        /** Idempotente: mantiene el trabajo ya programado. */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<CollectionSyncWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }
}
