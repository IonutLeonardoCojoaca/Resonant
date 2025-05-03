package com.example.spomusicapp

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        // Si la notificación tiene datos o es de tipo "data"
        remoteMessage.data.isNotEmpty().let {
            // Procesa los datos aquí
        }
        val activitySongList = ActivitySongList()
        // Si la notificación tiene un cuerpo
        remoteMessage.notification?.let {
            activitySongList.showUpdateNotification()  // Llama a la función para mostrar la notificación
        }
    }
}
