package com.example.resonant.workers

import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

class HourlyNotificationWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        showNotification()
        return Result.success()
    }

    private fun showNotification() {
        val channelId = "HourlyNotificationChannel"
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Actualización pendiente",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("¡Actualización disponible!")
            .setContentText("Hay una nueva versión de Resonant. Actualiza ahora para disfrutar las novedades.")
            .setSmallIcon(R.drawable.stat_sys_download_done)
            .build()

        notificationManager.notify(1001, notification)
    }
}