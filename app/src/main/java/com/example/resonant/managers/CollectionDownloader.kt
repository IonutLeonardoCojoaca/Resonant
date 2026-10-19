package com.example.resonant.managers

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.example.resonant.autodownload.AutoDownloadStorageRules
import com.example.resonant.data.local.dao.DownloadedSongDao
import com.example.resonant.data.local.entities.DownloadCollection
import com.example.resonant.data.local.entities.DownloadCollectionSong
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.ClientCompatibilityInterceptor
import com.example.resonant.data.network.NetworkTypeDetector
import com.example.resonant.data.network.PlaybackResolveRequestDTO
import com.example.resonant.playback.OfflineDownloadProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Progreso de una colección descargada, calculado desde Room y el índice de Media3 (sobrevive a la pantalla). */
data class CollectionProgress(
    val total: Int,
    val completed: Int,
    val percent: Int
) {
    val isComplete: Boolean get() = total > 0 && completed >= total
}

/**
 * Motor de descarga de colecciones (álbum, playlist, Playmix) sobre el `DownloadManager` de Media3
 * (collection-queue-offline D4): registrar la colección, sembrar las resoluciones en lotes de 50 y descargar cada
 * canción con su [DownloadCollectionRef]. Es el flujo que tenía el álbum en `DownloadViewModel`, sin cambios.
 */
@OptIn(UnstableApi::class)
class CollectionDownloader(
    context: Context,
    private val dao: DownloadedSongDao,
    private val downloadManager: MusicDownloadManager = MusicDownloadManager(context, dao),
    private val settingsManager: SettingsManager = SettingsManager(context)
) {
    private val appContext = context.applicationContext

    /** Evento por canción mientras se descarga la colección. */
    sealed class SongEvent {
        abstract val songId: String
        data class Progress(override val songId: String, val percent: Int) : SongEvent()
        data class Completed(override val songId: String) : SongEvent()
        data class Failed(override val songId: String) : SongEvent()
    }

    /** Registra la colección y sus referencias de canción (idempotente). */
    suspend fun registerCollection(userId: String, type: String, collectionId: String, title: String, songIds: List<String>) {
        withContext(Dispatchers.IO) {
            dao.insertCollectionWithSongs(
                DownloadCollection(
                    userId = userId,
                    collectionType = type,
                    collectionId = collectionId,
                    title = title,
                    createdAtEpochMs = System.currentTimeMillis()
                ),
                songIds.map { songId ->
                    DownloadCollectionSong(
                        userId = userId,
                        collectionType = type,
                        collectionId = collectionId,
                        songId = songId
                    )
                }
            )
        }
    }

    /** Pide las URLs de descarga en lotes de [MAX_RESOLVE_BATCH]; si falla, se resolverá canción a canción. */
    suspend fun seedResolutions(songs: List<Song>) {
        val quality = settingsManager.downloadQualityFlow.first()
        val service = ApiClient.getSongService(appContext)
        OfflineDownloadProvider.getDownloadManager(appContext)
        songs.forEach { OfflineDownloadProvider.prepareDownload(it.id) }

        songs.chunked(MAX_RESOLVE_BATCH).forEach { chunk ->
            runCatching {
                withTimeout(BATCH_TIMEOUT_MS) {
                    service.resolvePlayback(
                        request = PlaybackResolveRequestDTO(
                            songIds = chunk.map(Song::id),
                            preferredQuality = quality.apiValue,
                            networkType = NetworkTypeDetector.current(appContext),
                            download = true
                        ),
                        capabilities = ClientCompatibilityInterceptor.BASE_PLAYBACK_CAPABILITIES
                    )
                }
            }.onSuccess { response ->
                response.items.forEach(OfflineDownloadProvider::seedDownloadResolution)
            }.onFailure { error ->
                Log.w(TAG, "Batch de descarga no disponible; se resolverá canción a canción", error)
            }
        }
    }

    /**
     * Descarga [songs] dentro de [collection], informando de cada canción en [onEvent] (en el hilo del llamante).
     * Devuelve los ids que fallaron.
     */
    suspend fun downloadSongs(
        userId: String,
        collection: DownloadCollectionRef,
        songs: List<Song>,
        onEvent: (SongEvent) -> Unit
    ): Set<String> {
        val failed = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        supervisorScope {
            songs.map { song ->
                async {
                    try {
                        downloadManager.downloadSong(song = song, userId = userId, collection = collection)
                            .collect { status ->
                                when (status) {
                                    is DownloadStatus.Started -> onEvent(SongEvent.Progress(song.id, 0))
                                    is DownloadStatus.Progress -> onEvent(SongEvent.Progress(song.id, status.percent))
                                    is DownloadStatus.Success -> onEvent(SongEvent.Completed(song.id))
                                    is DownloadStatus.Error -> {
                                        failed += song.id
                                        onEvent(SongEvent.Failed(song.id))
                                    }
                                    else -> Unit
                                }
                            }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w(TAG, "Descarga fallida de ${song.id}", error)
                        failed += song.id
                        onEvent(SongEvent.Failed(song.id))
                    }
                }
            }.awaitAll()
        }
        return failed.toSet()
    }

    /**
     * Análisis completo de cada canción (el de `getSongPlaybackInfo` que usa `SongManager.getSongById`), para que
     * las transiciones inteligentes funcionen sin red. Si una canción no se puede pedir, se queda con el suyo.
     */
    suspend fun withFullAnalysis(songs: List<Song>): List<Song> = withContext(Dispatchers.IO) {
        val songManager = SongManager(appContext)
        val permits = Semaphore(ANALYSIS_PARALLELISM)
        songs.map { song ->
            async {
                permits.withPermit {
                    val full = runCatching { songManager.getSongById(song.id) }.getOrNull()
                    val analysis = full?.audioAnalysis ?: song.audioAnalysis
                    if (analysis === song.audioAnalysis) song else song.copy(audioAnalysis = analysis)
                }
            }
        }.awaitAll()
    }

    /** `false` si quedan menos de [AutoDownloadStorageRules.MIN_FREE_BYTES] libres: no se empieza a descargar. */
    fun hasEnoughSpace(): Boolean = freeBytes() >= AutoDownloadStorageRules.MIN_FREE_BYTES

    fun freeBytes(): Long = runCatching { StatFs(appContext.filesDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    /** Progreso desde Room (canciones terminadas) y el índice de Media3 (las que aún se descargan). */
    suspend fun progress(userId: String, type: String, collectionId: String): CollectionProgress =
        withContext(Dispatchers.IO) {
            val all = dao.getCollectionSongIds(userId, type, collectionId)
            if (all.isEmpty()) return@withContext CollectionProgress(0, 0, 0)
            val completed = dao.getCompletedCollectionSongIds(userId, type, collectionId).toSet()
            val partial = all.filterNot { it in completed }
                .sumOf { OfflineDownloadProvider.downloadPercent(appContext, it) ?: 0 }
            val percent = ((completed.size * 100 + partial) / all.size).coerceIn(0, 100)
            CollectionProgress(all.size, completed.size, percent)
        }

    companion object {
        private const val TAG = "CollectionDownloader"
        const val MAX_RESOLVE_BATCH = 50
        private const val BATCH_TIMEOUT_MS = 5_000L
        private const val ANALYSIS_PARALLELISM = 4
    }
}
