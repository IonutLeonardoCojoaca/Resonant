package com.example.resonant.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.scheduler.Requirements
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.PlaybackResolveItemDTO
import com.example.resonant.managers.SettingsManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

/**
 * Permanent offline storage. Unlike [PlaybackCache], this cache never evicts
 * content and is only written by Media3's DownloadManager.
 */
@OptIn(UnstableApi::class)
object OfflineDownloadProvider {
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadExecutor = Executors.newFixedThreadPool(2)

    @Volatile
    private var cache: SimpleCache? = null

    @Volatile
    private var manager: DownloadManager? = null

    @Volatile
    private var resolver: PlaybackUrlResolver? = null

    @Volatile
    private var databaseProvider: StandaloneDatabaseProvider? = null

    fun getCache(context: Context): SimpleCache {
        val appContext = context.applicationContext
        return cache ?: synchronized(this) {
            cache ?: SimpleCache(
                File(appContext.filesDir, OFFLINE_DIRECTORY),
                NoOpCacheEvictor(),
                getDatabaseProvider(appContext)
            ).also { cache = it }
        }
    }

    fun getDownloadManager(context: Context): DownloadManager {
        val appContext = context.applicationContext
        return manager ?: synchronized(this) {
            manager ?: DownloadManager(
                appContext,
                getDatabaseProvider(appContext),
                getCache(appContext),
                createResolvingHttpFactory(appContext),
                downloadExecutor
            ).apply {
                maxParallelDownloads = 2
                requirements = Requirements(Requirements.NETWORK)
                addListener(object : DownloadManager.Listener {
                    override fun onDownloadChanged(
                        downloadManager: DownloadManager,
                        download: Download,
                        finalException: Exception?
                    ) {
                        if (OfflineClips.isClipKey(download.request.id)) {
                            // Clip de Studio de una Playmix descargada: su propio índice (collection-queue-offline).
                            if (download.state == Download.STATE_COMPLETED) {
                                persistenceScope.launch {
                                    runCatching { OfflineClipDownloads.persistCompleted(appContext, download) }
                                        .onFailure { Log.e(TAG, "No se pudo indexar el clip ${download.request.id}", it) }
                                }
                            } else {
                                OfflineClips.markRemoved(download.request.id)
                            }
                            return
                        }
                        if (download.state == Download.STATE_COMPLETED) {
                            persistenceScope.launch {
                                runCatching {
                                    OfflineDownloadCatalog.persistCompleted(appContext, download)
                                }.onFailure {
                                    Log.e(
                                        TAG,
                                        "No se pudo indexar la descarga ${download.request.id}",
                                        it
                                    )
                                }
                            }
                        }
                    }

                    override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                        OfflineClips.markRemoved(download.request.id)
                    }
                })
            }.also {
                manager = it
                loadCompletedClips(it)
            }
        }
    }

    /**
     * Una descarga nueva debe resolver la calidad elegida ahora, no reutilizar
     * una concesión cacheada de una descarga anterior.
     */
    fun prepareDownload(songId: String) {
        resolver?.invalidate(songId)
    }

    fun seedDownloadResolution(item: PlaybackResolveItemDTO) {
        if (
            item.error != null ||
            item.streamUrl.isNullOrBlank()
        ) {
            return
        }
        resolver?.seed(
            songId = item.id,
            url = item.streamUrl,
            expiresAtUtc = item.expiresAtUtc
        )
    }

    /** Clips completos del índice de descargas, para que `RenderedTransition` no lea la base de datos en Main. */
    private fun loadCompletedClips(downloadManager: DownloadManager) {
        persistenceScope.launch {
            runCatching {
                val keys = mutableListOf<String>()
                downloadManager.downloadIndex.getDownloads(Download.STATE_COMPLETED).use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.download.request.id
                        if (OfflineClips.isClipKey(id)) keys += id
                    }
                }
                OfflineClips.replaceCompleted(keys)
            }.onFailure { Log.w(TAG, "No se pudieron leer los clips descargados", it) }
        }
    }

    /** `true` si la descarga [id] (canción o clip) está completa en el índice de Media3. Llamar fuera de Main. */
    fun isDownloadCompleted(context: Context, id: String): Boolean =
        runCatching {
            getDownloadManager(context).downloadIndex.getDownload(id)?.state == Download.STATE_COMPLETED
        }.getOrDefault(false)

    /** Progreso (0-100) de la descarga [id] en el índice de Media3, o `null` si no existe. Llamar fuera de Main. */
    fun downloadPercent(context: Context, id: String): Int? =
        runCatching {
            val download = getDownloadManager(context).downloadIndex.getDownload(id) ?: return@runCatching null
            when (download.state) {
                Download.STATE_COMPLETED -> 100
                Download.STATE_FAILED -> null
                else -> download.percentDownloaded.takeIf { it >= 0f }?.toInt()?.coerceIn(0, 99) ?: 0
            }
        }.getOrNull()

    private fun getDatabaseProvider(context: Context): StandaloneDatabaseProvider {
        return databaseProvider ?: synchronized(this) {
            databaseProvider ?: StandaloneDatabaseProvider(context.applicationContext)
                .also { databaseProvider = it }
        }
    }

    private fun createResolvingHttpFactory(context: Context): ResolvingDataSource.Factory {
        val httpFactory = MediaHttpDataSourceProvider.create(
            context,
            "Resonant/Android Offline"
        )
        val playbackResolver = resolver ?: PlaybackUrlResolver(
            loadPlaybackInfo = { songId ->
                val quality = SettingsManager(context)
                    .downloadQualityFlow
                    .first()
                // Downloads are stored as progressive audio for offline playback.
                ApiClient.getSongService(context).getSongPlaybackInfo(
                    songId = songId,
                    deliveryMode = "progressive",
                    preferredQuality = quality.apiValue
                )
            }
        ).also { resolver = it }

        return ResolvingDataSource.Factory(httpFactory) { dataSpec ->
            val uri = dataSpec.uri
            if (uri.scheme == PlaybackUrlResolver.STABLE_SCHEME &&
                uri.authority == PlaybackUrlResolver.STABLE_AUTHORITY
            ) {
                val songId = uri.pathSegments.firstOrNull()
                    ?: throw IllegalArgumentException("La URI offline no contiene songId")
                dataSpec.withUri(Uri.parse(playbackResolver.resolveBlocking(songId)))
            } else if (uri.scheme == OfflineClips.SCHEME) {
                // Clip de Studio: la render.url firmada vigente de esa transición.
                dataSpec.withUri(Uri.parse(OfflineClipDownloads.resolveUrlBlocking(context, uri)))
            } else {
                dataSpec
            }
        }
    }

    private const val OFFLINE_DIRECTORY = "offline_media"
    private const val TAG = "OfflineDownloads"
}
