package com.example.resonant.utils

import com.example.resonant.R
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import androidx.annotation.IdRes
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import com.example.resonant.ui.activities.MainActivity
import java.io.File
import java.io.FileOutputStream

object Utils {

    fun formatTime(millis: Int): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / 1000) / 60
        if (minutes < 10 && seconds < 10) return "0$minutes:0$seconds"
        if (minutes < 10) return "0$minutes:$seconds"
        if (seconds < 10) return "$minutes:0$seconds"
        return "$minutes:$seconds"
    }

    fun saveBitmapToCache(context: Context, bitmap: Bitmap, songId: String): String {
        val fileName = "cover_$songId.png"
        val file = File(context.cacheDir, fileName)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file.absolutePath
    }

    fun saveBitmapToCacheUri(context: Context, bitmap: Bitmap, fileName: String): Uri? {
        return try {
            val file = File(context.cacheDir, fileName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun cacheSongBitmap(songId: String, bitmap: Bitmap, context: Context) {
        if (songId.isBlank()) return
        try {
            // Usamos .jpg para optimizar el espacio. 85 es un buen balance calidad/tamaño.
            val fileName = "cover_$songId.jpg"
            val file = File(context.cacheDir, fileName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
            Log.d("UtilsCache", "✅ Bitmap cacheado para songId: $songId")
        } catch (e: Exception) {
            Log.e("UtilsCache", "❌ Error al cachear bitmap para songId: $songId", e)
        }
    }

    fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "0m"
        }
    }

    fun formatSecondsToMinSec(seconds: Int): String {
        val min = seconds / 60
        val sec = seconds % 60
        return "${min}m ${sec}s"
    }

    fun getCachedSongBitmap(songId: String, context: Context): Bitmap? {
        if (songId.isBlank()) return null
        return try {
            val fileName = "cover_$songId.jpg" // Buscamos el archivo .jpg
            val file = File(context.cacheDir, fileName)
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("UtilsCache", "❌ Error al leer bitmap de la caché para songId: $songId", e)
            null
        }
    }

    fun compareSemver(a: String, b: String): Int {
        fun toParts(s: String) = s
            .split("-", limit = 2)[0] // quita sufijo -beta etc
            .split(".")
            .map { it.toIntOrNull() ?: 0 }
            .let { parts -> listOf(parts.getOrElse(0) {0}, parts.getOrElse(1){0}, parts.getOrElse(2){0}) }

        val (a1, a2, a3) = toParts(a)
        val (b1, b2, b3) = toParts(b)
        if (a1 != b1) return a1 - b1
        if (a2 != b2) return a2 - b2
        return a3 - b3
    }

    fun loadUserProfile(context: Context, userProfileImage: ImageView) {
        val localFileName = "profile_user.png"
        val file = File(context.filesDir, localFileName)

        // 1. Intentar cargar la imagen
        if (file.exists()) {
            try {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                userProfileImage.setImageBitmap(bitmap)
            } catch (e: Exception) {
                e.printStackTrace()
                userProfileImage.setImageResource(R.drawable.ic_user)
            }
        } else {
            userProfileImage.setImageResource(R.drawable.ic_user)
        }

        // 2. Configurar el Clic para abrir el Drawer
        userProfileImage.setOnClickListener {
            // Verificamos si el contexto es MainActivity (o envuelto en una)
            val activity = getActivityFromContext(context)
            if (activity is MainActivity) {
                activity.openDrawer()
            }
        }
    }

    // Función auxiliar para obtener la Activity desde cualquier Context (a veces el context es un wrapper)
    private fun getActivityFromContext(context: Context): android.app.Activity? {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is android.app.Activity) {
                return ctx
            }
            ctx = ctx.baseContext
        }
        return null
    }

}