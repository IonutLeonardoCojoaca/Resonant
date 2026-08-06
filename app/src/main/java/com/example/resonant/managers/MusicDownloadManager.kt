package com.example.resonant.managers

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.example.resonant.data.local.dao.DownloadedSongDao
import com.example.resonant.data.local.entities.DownloadedSong
import com.example.resonant.data.models.Song
import com.example.resonant.playback.OfflineDownloadCatalog
import com.example.resonant.playback.OfflineDownloadMetadata
import com.example.resonant.playback.OfflineDownloadProvider
import com.example.resonant.playback.PlaybackUrlResolver
import com.example.resonant.services.ResonantDownloadService
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File

sealed class DownloadStatus {
    object Idle : DownloadStatus()
    object Started : DownloadStatus()
    data class Progress(val percent: Int) : DownloadStatus()
    data class Success(val songTitle: String) : DownloadStatus()
    data class Error(val message: String) : DownloadStatus()
}

data class DownloadCollectionRef(
    val type: String,
    val id: String,
    val title: String
)

/**
 * UI-facing adapter over Media3's persistent DownloadManager. Downloads keep
 * running when the ViewModel, Activity or application UI goes away.
 */
@OptIn(UnstableApi::class)
class MusicDownloadManager(
    context: Context,
    private val downloadedSongDao: DownloadedSongDao
) {
    private val appContext = context.applicationContext

    fun downloadSong(
        song: Song,
        userId: String,
        collection: DownloadCollectionRef? = null
    ): Flow<DownloadStatus> = callbackFlow {
        trySend(DownloadStatus.Started)
        trySend(DownloadStatus.Progress(0))

        val legacyFile = File(appContext.filesDir, "${song.id}.mp3")
        if (legacyFile.exists() && legacyFile.length() > 0L) {
            registerLegacyDownload(song, userId, legacyFile, collection)
            trySend(DownloadStatus.Progress(100))
            trySend(DownloadStatus.Success(song.title))
            close()
            return@callbackFlow
        }

        val metadata = OfflineDownloadMetadata.from(
            song = song,
            userId = userId,
            collectionType = collection?.type,
            collectionId = collection?.id
        )
        val downloadManager = OfflineDownloadProvider.getDownloadManager(appContext)
        val existing = downloadManager.downloadIndex.getDownload(song.id)
        if (existing?.state == Download.STATE_COMPLETED) {
            OfflineDownloadCatalog.persistCompleted(appContext, existing, metadata)
            trySend(DownloadStatus.Progress(100))
            trySend(DownloadStatus.Success(song.title))
            close()
            return@callbackFlow
        }

        val listener = object : DownloadManager.Listener {
            override fun onDownloadChanged(
                manager: DownloadManager,
                download: Download,
                finalException: Exception?
            ) {
                if (download.request.id != song.id) return
                when (download.state) {
                    Download.STATE_QUEUED,
                    Download.STATE_RESTARTING -> trySend(DownloadStatus.Started)

                    Download.STATE_DOWNLOADING -> {
                        val progress = download.percentDownloaded
                            .takeIf { it >= 0f }
                            ?.toInt()
                            ?.coerceIn(0, 99)
                        if (progress != null) trySend(DownloadStatus.Progress(progress))
                    }

                    Download.STATE_COMPLETED -> launch(Dispatchers.IO) {
                        OfflineDownloadCatalog.persistCompleted(appContext, download, metadata)
                        trySend(DownloadStatus.Progress(100))
                        trySend(DownloadStatus.Success(song.title))
                        close()
                    }

                    Download.STATE_FAILED -> {
                        trySend(
                            DownloadStatus.Error(
                                finalException?.localizedMessage ?: "La descarga no pudo completarse"
                            )
                        )
                        close()
                    }
                }
            }
        }
        downloadManager.addListener(listener)

        val request = DownloadRequest.Builder(
            song.id,
            Uri.parse(PlaybackUrlResolver.stableUriFor(song.id))
        )
            .setMimeType(MimeTypes.AUDIO_MPEG)
            .setCustomCacheKey(PlaybackUrlResolver.CACHE_KEY_PREFIX + song.id)
            .setData(metadata.encode())
            .build()

        if (collection == null) {
            OfflineDownloadProvider.prepareDownload(song.id)
        }
        DownloadService.sendAddDownload(
            appContext,
            ResonantDownloadService::class.java,
            request,
            false
        )

        awaitClose { downloadManager.removeListener(listener) }
    }.flowOn(Dispatchers.IO)

    private suspend fun registerLegacyDownload(
        song: Song,
        userId: String,
        file: File,
        collection: DownloadCollectionRef?
    ) {
        val coverName = "cover_${song.id}.jpg"
        val cover = File(appContext.filesDir, coverName).takeIf { it.exists() }
        downloadedSongDao.insert(
            DownloadedSong(
                userId = userId,
                songId = song.id,
                title = song.title,
                artistName = song.artistName ?: "Desconocido",
                album = song.album,
                duration = song.duration,
                localAudioPath = file.name,
                localImagePath = cover?.name,
                downloadDate = System.currentTimeMillis(),
                sizeBytes = file.length() + (cover?.length() ?: 0L),
                audioAnalysisJson = song.audioAnalysis?.let(Gson()::toJson),
                isIndividuallySaved = collection == null
            )
        )
    }
}
