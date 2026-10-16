package com.example.resonant.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.resonant.autodownload.AndroidAutoDownloadEnvironment
import com.example.resonant.autodownload.AutoDownloadNotifier
import com.example.resonant.autodownload.AutoDownloadRunOutcome
import com.example.resonant.autodownload.AutoDownloadRunner
import com.example.resonant.autodownload.AutoDownloadScheduler
import com.example.resonant.autodownload.MediaAutoDownloadSongDownloader
import com.example.resonant.autodownload.RoomAutoDownloadStore
import com.example.resonant.managers.AutoDownloadManager
import com.example.resonant.managers.CapabilitiesManager

/**
 * Una ejecución de descargas automáticas (ver [AutoDownloadRunner]). WorkManager la lanza solo con
 * las condiciones del usuario; si una se pierde a mitad, se cancela y la descarga en curso se pausa
 * sin informar resultado (el lease vence en el servidor).
 */
class AutoDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!CapabilitiesManager.refresh(context).autoDownloads) {
            AutoDownloadScheduler.cancel(context)
            return Result.success()
        }
        val runner = AutoDownloadRunner(
            manager = AutoDownloadManager.get(context),
            env = AndroidAutoDownloadEnvironment(context),
            store = RoomAutoDownloadStore(context),
            downloader = MediaAutoDownloadSongDownloader(context),
            log = { Log.d(TAG, it) }
        )
        when (val outcome = runner.run()) {
            is AutoDownloadRunOutcome.Finished -> AutoDownloadNotifier.notify(context, outcome.downloadedByArtist)
            AutoDownloadRunOutcome.Disabled -> {
                // El servidor apagó la feature: sin errores visibles; se deja de consultar hasta que
                // las capacidades vuelvan a activarla (MainActivity reprograma al abrir).
                CapabilitiesManager.refresh(context, force = true)
                AutoDownloadScheduler.cancel(context)
            }
            else -> Log.d(TAG, "run ended: ${outcome.javaClass.simpleName}")
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "AutoDownload"
    }
}
