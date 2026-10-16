package com.example.resonant.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.resonant.managers.StreamReporter
import kotlinx.coroutines.CancellationException

/** Vacía la bandeja de escuchas pendientes (`stream_outbox_flush`, solo con red). */
class StreamOutboxWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        if (StreamReporter.flush(applicationContext) || runAttemptCount >= MAX_ATTEMPTS) {
            Result.success()
        } else {
            Result.retry()
        }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    private companion object {
        const val MAX_ATTEMPTS = 10
    }
}
