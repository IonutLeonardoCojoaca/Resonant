package com.example.resonant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object Utils {

    fun formatTime(millis: Int): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / 1000) / 60
        return String.format("%02d:%02d", minutes, seconds)
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
}
