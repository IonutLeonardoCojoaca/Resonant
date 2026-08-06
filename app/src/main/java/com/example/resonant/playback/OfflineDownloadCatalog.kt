package com.example.resonant.playback

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import com.example.resonant.data.local.AppDatabase
import com.example.resonant.data.local.entities.DownloadedSong
import com.example.resonant.data.network.ApiClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

@OptIn(UnstableApi::class)
object OfflineDownloadCatalog {

    suspend fun persistCompleted(
        context: Context,
        download: Download,
        fallbackMetadata: OfflineDownloadMetadata? = null
    ) {
        val appContext = context.applicationContext
        val metadata = fallbackMetadata
            ?: OfflineDownloadMetadata.decode(download.request.data)
            ?: return
        if (metadata.userId.isBlank() || metadata.songId.isBlank()) return

        val coverPath = downloadCover(appContext, metadata)
        val sizeBytes = download.bytesDownloaded
            .takeIf { it > 0L }
            ?: download.contentLength.coerceAtLeast(0L)

        val dao = AppDatabase.getDatabase(appContext).downloadedSongDao()
        val existing = dao.getById(metadata.userId, metadata.songId)
        dao.insert(
            DownloadedSong(
                userId = metadata.userId,
                songId = metadata.songId,
                title = metadata.title,
                artistName = metadata.artistName,
                album = metadata.album,
                duration = metadata.duration,
                localAudioPath = PlaybackUrlResolver.stableUriFor(metadata.songId),
                localImagePath = coverPath,
                downloadDate = System.currentTimeMillis(),
                sizeBytes = sizeBytes,
                audioAnalysisJson = metadata.audioAnalysisJson,
                isIndividuallySaved = existing?.isIndividuallySaved == true ||
                    metadata.collectionType.isNullOrBlank()
            )
        )
    }

    private suspend fun downloadCover(
        context: Context,
        metadata: OfflineDownloadMetadata
    ): String? {
        val directory = File(context.filesDir, COVER_DIRECTORY)
        val relativePath = "$COVER_DIRECTORY/${metadata.songId.safeFileName()}.jpg"
        val destination = File(context.filesDir, relativePath)
        if (destination.exists() && destination.length() > 0L) return relativePath
        if (!directory.exists() && !directory.mkdirs()) return null

        val coverUrl = metadata.coverUrl
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: runCatching {
                ApiClient.getSongService(context)
                    .getSongById(metadata.songId)
                    .coverUrl
            }.getOrNull()
            ?: return null

        return runCatching {
            val response = ApiClient.getMediaHttpClient()
                .newCall(Request.Builder().url(coverUrl).build())
                .execute()
            response.use {
                val body = it.body
                if (!it.isSuccessful || body == null) return@use null
                destination.outputStream().buffered().use { output ->
                    body.byteStream().use { input -> input.copyTo(output) }
                }
                relativePath
            }
        }.onFailure {
            destination.delete()
            Log.w(TAG, "No se pudo guardar la portada offline de ${metadata.songId}", it)
        }.getOrNull()
    }

    private fun String.safeFileName(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it) }
    }

    private const val COVER_DIRECTORY = "offline_covers"
    private const val TAG = "OfflineDownloadCatalog"
}
