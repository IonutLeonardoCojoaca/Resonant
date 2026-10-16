package com.example.resonant.autodownload

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.StatFs
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadService
import com.example.resonant.R
import com.example.resonant.data.local.AppDatabase
import com.example.resonant.data.local.entities.AutoDownloadJob
import com.example.resonant.data.local.entities.AutoDownloadJobState
import com.example.resonant.data.local.entities.DownloadCollection
import com.example.resonant.data.local.entities.DownloadCollectionSong
import com.example.resonant.data.models.Song
import com.example.resonant.managers.AutoDownloadPreferences
import com.example.resonant.managers.AutoDownloadSettings
import com.example.resonant.managers.DownloadCollectionRef
import com.example.resonant.managers.DownloadStatus
import com.example.resonant.managers.MusicDownloadManager
import com.example.resonant.playback.OfflineDownloadCleaner
import com.example.resonant.playback.OfflineMediaFiles
import com.example.resonant.services.ResonantDownloadService
import com.example.resonant.ui.activities.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal const val AUTO_DOWNLOAD_TAG = "AutoDownload"

/** Usuario de `SharedPreferences("user_data")`, preferencias en DataStore y espacio libre con StatFs. */
class AndroidAutoDownloadEnvironment(context: Context) : AutoDownloadEnvironment {
    private val appContext = context.applicationContext
    private val preferences = AutoDownloadPreferences(appContext)

    override fun currentUserId(): String? =
        appContext.getSharedPreferences("user_data", Context.MODE_PRIVATE)
            .getString("USER_ID", null)
            ?.takeIf(String::isNotBlank)

    override suspend fun settings(userId: String): AutoDownloadSettings = preferences.get(userId)

    override fun installationId(): String = preferences.installationId()

    override fun freeBytes(): Long = runCatching { StatFs(appContext.filesDir.path).availableBytes }.getOrDefault(0L)

    override fun nowMs(): Long = System.currentTimeMillis()
}

/** Trabajos en `auto_download_jobs` y descargas en `downloaded_songs`. */
class RoomAutoDownloadStore(context: Context) : AutoDownloadLocalStore {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getDatabase(appContext)
    private val jobs = database.autoDownloadJobDao()
    private val downloads = database.downloadedSongDao()

    override suspend fun unreported(userId: String): List<AutoDownloadJob> =
        jobs.getByState(userId, AutoDownloadJobState.DOWNLOADED_UNREPORTED)

    override suspend fun saveJob(job: AutoDownloadJob) = jobs.upsert(job)

    override suspend fun updateJobState(userId: String, planId: String, state: String, nowMs: Long) =
        jobs.updateState(userId, planId, state, nowMs)

    override suspend fun isDownloaded(userId: String, songId: String): Boolean =
        downloads.getById(userId, songId) != null

    override suspend fun autoUsedBytes(userId: String): Long =
        downloads.getAutoOnlyUsedBytes(userId, AUTO_ARTIST_COLLECTION)

    override suspend fun cleanup(userId: String, keepDays: Int, nowMs: Long) {
        val cutoff = AutoDownloadStorageRules.cleanupCutoff(keepDays, nowMs) ?: return
        val candidates = downloads.getAutoOnlyCleanupCandidates(userId, AUTO_ARTIST_COLLECTION, cutoff)
        if (candidates.isEmpty()) return
        val removed = OfflineDownloadCleaner(downloads, OfflineMediaFiles.remover(appContext))
            .removeFromCollectionType(userId, AUTO_ARTIST_COLLECTION, candidates)
        Log.d(AUTO_DOWNLOAD_TAG, "cleanup removed ${removed.size} songs")
    }
}

/**
 * Descarga con Media3 registrando la colección `auto_artist` (id = artistId) para reutilizar el
 * conteo de referencias: la canción queda `isIndividuallySaved = false` salvo que ya fuera manual.
 * Si el worker se cancela (se pierde una condición), pausa la descarga en curso con un stopReason
 * propio, salvo que la canción tenga también una descarga manual o de otra colección.
 */
@OptIn(UnstableApi::class)
class MediaAutoDownloadSongDownloader(context: Context) : AutoDownloadSongDownloader {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.getDatabase(appContext).downloadedSongDao()
    private val musicDownloads = MusicDownloadManager(appContext, dao)

    override suspend fun download(song: Song, userId: String, artistId: String, artistName: String): Boolean {
        dao.insertCollectionWithSongs(
            DownloadCollection(
                userId = userId,
                collectionType = AUTO_ARTIST_COLLECTION,
                collectionId = artistId,
                title = artistName,
                createdAtEpochMs = System.currentTimeMillis()
            ),
            listOf(DownloadCollectionSong(userId, AUTO_ARTIST_COLLECTION, artistId, song.id))
        )
        var succeeded = false
        try {
            musicDownloads.downloadSong(
                song = song,
                userId = userId,
                collection = DownloadCollectionRef(AUTO_ARTIST_COLLECTION, artistId, artistName)
            ).collect { status ->
                when (status) {
                    is DownloadStatus.Success -> succeeded = true
                    is DownloadStatus.Error -> succeeded = false
                    else -> Unit
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { pauseIfOnlyAutomatic(userId, song.id) }
            throw e
        }
        if (!succeeded && dao.getById(userId, song.id) == null) {
            // Sin descarga no debe quedar la referencia: no cuenta en la cuota ni en la limpieza.
            dao.removeSongsFromCollectionType(userId, AUTO_ARTIST_COLLECTION, listOf(song.id))
        }
        return succeeded
    }

    private suspend fun pauseIfOnlyAutomatic(userId: String, songId: String) {
        val existing = dao.getById(userId, songId)
        val otherReferences = dao.getCollectionTypesForSong(userId, songId).any { it != AUTO_ARTIST_COLLECTION }
        if (existing?.isIndividuallySaved == true || otherReferences) return
        // No se tocan los Requirements globales de Media3 (afectarían a las descargas manuales):
        // se pausa solo esta descarga, sin borrar la caché parcial. El sendAddDownload normal la reanuda.
        DownloadService.sendSetStopReason(
            appContext,
            ResonantDownloadService::class.java,
            songId,
            STOP_REASON_AUTO_CONDITIONS,
            false
        )
        Log.d(AUTO_DOWNLOAD_TAG, "paused automatic download (conditions lost)")
    }

    companion object {
        /** Distinto de `Download.STOP_REASON_NONE` (0). */
        const val STOP_REASON_AUTO_CONDITIONS = 0x4144
    }
}

/** Notificación local agrupada por artista (canal `auto_downloads`). */
object AutoDownloadNotifier {
    private const val CHANNEL_ID = "auto_downloads"
    private const val NOTIFICATION_ID = 2201

    fun notify(context: Context, downloadedByArtist: Map<String, Int>) {
        val text = AutoDownloadNotificationText.text(downloadedByArtist) ?: return
        val appContext = context.applicationContext
        val granted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val manager = NotificationManagerCompat.from(appContext)
        if (!granted || !manager.areNotificationsEnabled()) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.auto_download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val openApp = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_done)
            .setContentTitle(appContext.getString(R.string.auto_download_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
