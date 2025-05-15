package com.example.spomusicapp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationManagerHelper {

    private const val CHANNEL_ID = "media_playback_channel"

    fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Music Playback",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun showNotification(context: Context) {
        val playPauseIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = "ACTION_PLAY_PAUSE"
        }
        val playPausePendingIntent = PendingIntent.getBroadcast(
            context, 0, playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val previousIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = "ACTION_PREVIOUS"
        }
        val previousPendingIntent = PendingIntent.getBroadcast(
            context, 0, previousIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = "ACTION_NEXT"
        }
        val nextPendingIntent = PendingIntent.getBroadcast(
            context, 1, nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Obtener la duración actual de la canción
        val currentDuration = MediaPlayerManager.getDuration()
        val currentPosition = MediaPlayerManager.getCurrentPosition()

        // Crear notificación con la información actualizada
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.play_arrow_filled)
            .setContentTitle("Reproduciendo música")
            .setContentText("Tu canción aquí") // Aquí puedes poner el nombre de la canción
            .addAction(NotificationCompat.Action(R.drawable.skip_previous_filled, "Anterior", previousPendingIntent))
            .addAction(NotificationCompat.Action(
                if (MediaPlayerManager.isPlaying()) R.drawable.pause else R.drawable.play_arrow_filled,
                "Play/Pause",
                playPausePendingIntent
            ))
            .addAction(NotificationCompat.Action(R.drawable.skip_next_filled, "Siguiente", nextPendingIntent))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(currentDuration, currentPosition, false) // Aquí actualizas el progreso de la canción
            .build()

        val manager = NotificationManagerCompat.from(context)
        manager.notify(1, notification)
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun updateNotification(context: Context) {
        showNotification(context)
    }
}
