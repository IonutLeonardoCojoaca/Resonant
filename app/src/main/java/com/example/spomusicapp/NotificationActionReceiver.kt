package com.example.spomusicapp

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.RequiresPermission

class NotificationActionReceiver : BroadcastReceiver() {
    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "ACTION_PLAY_PAUSE" -> {
                if (MediaPlayerManager.isPlaying()) {
                    MediaPlayerManager.pause()
                } else {
                    MediaPlayerManager.resume()
                    
                }
                NotificationManagerHelper.updateNotification(context) // La actualizamos
            }
            "ACTION_NEXT" -> {
                PlaybackManager.playNext(context)
            }
            "ACTION_PREVIOUS" -> {
                PlaybackManager.playPrevious(context)
            }
        }
        NotificationManagerHelper.updateNotification(context)
    }
}
