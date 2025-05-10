package com.example.spomusicapp

import android.R
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.widget.ImageView
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import androidx.core.content.edit
import com.example.spomusicapp.ActivitySongList

object Utils {

    private const val MAX_CACHED_FILES = 100

    @SuppressLint("DefaultLocale")
    fun formatTime(millis: Int): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / 1000) / 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    fun getCachedSongFile(context: Context, url: String): File {
        val fileName = url.toUri().lastPathSegment ?: url.hashCode().toString()
        return File(context.cacheDir, fileName)
    }

    suspend fun cacheSongIfNeeded(context: Context, url: String): File {
        val cachedFile = getCachedSongFile(context, url)
        if (cachedFile.exists()) return cachedFile

        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection()
                val input = connection.getInputStream()
                val output = FileOutputStream(cachedFile)

                input.use { inp ->
                    output.use { out ->
                        inp.copyTo(out)
                    }
                }

                cachedFile
            } catch (e: Exception) {
                e.printStackTrace()
                throw e
            }
        }
    }

    fun cleanOldCacheFiles(context: Context) {
        val cacheDir = context.cacheDir
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size > MAX_CACHED_FILES) {
            val filesToDelete = files.take(files.size - MAX_CACHED_FILES)
            filesToDelete.forEach { it.delete() }
        }
    }

    fun enrichSong(context: Context, song: Song): Song? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(song.url, HashMap())

            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: song.title
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Artista desconocido"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Álbum desconocido"
            val art = retriever.embeddedPicture

            var localCoverPath: String? = null

            if (art != null) {
                val safeTitle = title
                    .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    .lowercase()
                val fileName = "${safeTitle}_cover.jpg"
                val coverFile = File(context.cacheDir, fileName)
                FileOutputStream(coverFile).use {
                    it.write(art)
                }
                localCoverPath = fileName
            }

            retriever.release()

            Song(
                title = title,
                artist = artist,
                album = album,
                url = song.url,
                localCoverPath = localCoverPath // <-- NUEVO CAMPO (debes agregarlo a tu modelo Song)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getImageSongFromCache (song: Song, context: Context, songImage: ImageView, imageName: String){
        if (!imageName.isNullOrEmpty()) {
            val file = File(context.cacheDir, imageName)  // Usamos cacheDir para obtener la ruta completa
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    songImage.setImageBitmap(bitmap)
                } else {
                    songImage.setImageResource(com.example.spomusicapp.R.drawable.album_cover)
                }
            } else {
                songImage.setImageResource(com.example.spomusicapp.R.drawable.album_cover)
            }
        } else {
            songImage.setImageResource(com.example.spomusicapp.R.drawable.album_cover)
        }
    }

    fun hasShownDialog(context: Context): Boolean {
        val sharedPreferences = context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
        return sharedPreferences.getBoolean("dialog_shown", false)
    }

    fun setDialogShown(context: Context) {
        val sharedPreferences = context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
        sharedPreferences.edit() { putBoolean("dialog_shown", true) }
    }

}