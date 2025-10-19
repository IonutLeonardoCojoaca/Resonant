package com.example.resonant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.core.app.NotificationCompat

class AppNotificationManager(
    private val service: Service,
    private val sessionToken: MediaSessionCompat.Token
) {
    private val context: Context = service.applicationContext

    companion object {
        const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "music_channel"
    }

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reproducción de música",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Reproducción de música en segundo plano"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    fun startForeground(song: Song, isPlaying: Boolean, bitmap: Bitmap?) {
        val notification = createNotification(song, isPlaying, bitmap)
        Log.d("AppNotificationManager", "Starting service in foreground.")
        service.startForeground(NOTIFICATION_ID, notification)
    }

    fun updateNotification(song: Song?, isPlaying: Boolean, bitmap: Bitmap?) {
        Log.d("AppNotificationManager", "📱 updateNotification() llamado - Song: ${song?.title}")

        if (song != null) {
            val notification = createNotification(song, isPlaying, bitmap)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
            Log.d("AppNotificationManager", "✅ Notificación actualizada: ${song.title}")
        } else {
            Log.w("AppNotificationManager", "⚠️ No hay canción para actualizar la notificación. Deteniendo foreground.")
            service.stopForeground(true)
        }
    }

    private fun createNotification(song: Song, isPlaying: Boolean, bitmap: Bitmap?): Notification {
        val largeIcon = bitmap ?: BitmapFactory.decodeResource(context.resources, R.drawable.s_resonant_white)

        val requestCodeBase = (song.id?.hashCode() ?: System.currentTimeMillis().toInt()) and 0xffff

        val prevIntent = Intent(service, MusicPlaybackService::class.java).apply { action = MusicPlaybackService.ACTION_PREVIOUS }
        val prevPI = PendingIntent.getService(service, requestCodeBase + 1, prevIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val playPauseAction = if (isPlaying) MusicPlaybackService.ACTION_PAUSE else MusicPlaybackService.ACTION_RESUME
        val playPauseIntent = Intent(service, MusicPlaybackService::class.java).apply { action = playPauseAction }
        val playPausePI = PendingIntent.getService(service, requestCodeBase + 2, playPauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val nextIntent = Intent(service, MusicPlaybackService::class.java).apply { action = MusicPlaybackService.ACTION_NEXT }
        val nextPI = PendingIntent.getService(service, requestCodeBase + 3, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("fromNotification", true)
        }
        val contentPI = PendingIntent.getActivity(context, 0, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val prevActionCompat = NotificationCompat.Action(R.drawable.skip_previous_filled, "Anterior", prevPI)
        val playPauseActionDrawable = if (isPlaying) R.drawable.pause else R.drawable.play_arrow_filled
        val playPauseActionText = if (isPlaying) "Pausar" else "Reproducir"
        val playPauseActionCompat = NotificationCompat.Action(playPauseActionDrawable, playPauseActionText, playPausePI)
        val nextActionCompat = NotificationCompat.Action(R.drawable.skip_next_filled, "Siguiente", nextPI)

        Log.d("AppNotificationManager", "🔘 Creando notificación - Song: ${song.title}, Estado: ${if(isPlaying) "PLAY" else "PAUSE"}")

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.s_resonant_white)
            .setContentTitle(song.title ?: "Canción")
            .setContentText(song.artistName ?: "Artista")
            .setLargeIcon(largeIcon)
            .setContentIntent(contentPI)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(false)
            .addAction(prevActionCompat)
            .addAction(playPauseActionCompat)
            .addAction(nextActionCompat)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }
}