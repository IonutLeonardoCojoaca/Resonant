package com.example.resonant.managers

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import com.example.resonant.data.local.AppDatabase
import com.example.resonant.data.local.entities.OfflinePlaylist
import com.example.resonant.data.local.entities.OfflinePlaymix
import com.example.resonant.data.local.entities.OfflinePlaymixClip
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.PlaymixDetailDTO
import com.example.resonant.playback.CollectionSyncDiff
import com.example.resonant.playback.CollectionSyncDiff.ClipRef
import com.example.resonant.playback.OfflineAnalysisCodec
import com.example.resonant.playback.OfflineClipDownloads
import com.example.resonant.playback.OfflineClips
import com.example.resonant.playback.OfflineCollectionStore
import com.example.resonant.playback.OfflineCollectionStore.TYPE_PLAYLIST
import com.example.resonant.playback.OfflineCollectionStore.TYPE_PLAYMIX
import com.example.resonant.playback.OfflineDownloadCleaner
import com.example.resonant.playback.OfflineDownloadProvider
import com.example.resonant.playback.OfflineMediaFiles
import com.example.resonant.services.ResonantDownloadService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Descargas de playlists y Playmix enteras (collection-queue-offline D4–D6): descarga con el motor de colecciones,
 * paquete sin conexión de la Playmix (detalle y clips), sincronización con el servidor y limpieza.
 * Patrón legacy: Manager instanciado con un `Context`. Todo `suspend` y fuera de Main.
 */
@OptIn(UnstableApi::class)
class OfflineCollectionManager(context: Context) {

    sealed class Outcome {
        /** Descarga terminada; [failed] canciones o clips no se pudieron descargar (se reintentan al sincronizar). */
        data class Done(val songs: Int, val failed: Int) : Outcome()
        object Empty : Outcome()
        object NoSpace : Outcome()
        object Offline : Outcome()
        object Unavailable : Outcome()
        object NotDownloaded : Outcome()
        object UpToDate : Outcome()
        /** Hay cambios pero no caben: se queda la versión anterior. */
        object PendingUpdate : Outcome()
    }

    private val appContext = context.applicationContext
    private val db = AppDatabase.getDatabase(appContext)
    private val songDao = db.downloadedSongDao()
    private val offlineDao = db.offlineCollectionDao()
    private val downloader = CollectionDownloader(appContext, songDao)
    private val cleaner = OfflineDownloadCleaner(songDao, OfflineMediaFiles.remover(appContext))

    private fun userId(): String? = OfflineCollectionStore.currentUserId(appContext).takeIf { it.isNotBlank() }

    // ─── Playlist ─────────────────────────────────────────────

    suspend fun downloadPlaylist(
        playlistId: String,
        title: String,
        onEvent: (CollectionDownloader.SongEvent) -> Unit = {}
    ): Outcome = withContext(Dispatchers.IO) {
        val userId = userId() ?: return@withContext Outcome.Offline
        if (!downloader.hasEnoughSpace()) return@withContext Outcome.NoSpace
        val fetched = try {
            PlaylistManager(appContext).getSongsByPlaylistId(playlistId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext failureOutcome(e)
        }
        val songs = fetched.filter { it.id.isNotBlank() }.distinctBy { it.id }
        if (songs.isEmpty()) return@withContext Outcome.Empty

        offlineDao.upsertPlaylist(
            OfflinePlaylist(userId, playlistId, OfflineAnalysisCodec.encodeIds(songs.map { it.id }), System.currentTimeMillis())
        )
        downloader.registerCollection(userId, TYPE_PLAYLIST, playlistId, title, songs.map { it.id })
        val failed = downloadSongs(userId, TYPE_PLAYLIST, playlistId, title, songs, onEvent)
        Log.i(TAG, "Playlist $playlistId descargada: ${songs.size} canciones, ${failed.size} fallidas")
        Outcome.Done(songs.size, failed.size)
    }

    suspend fun removePlaylist(playlistId: String) = withContext(Dispatchers.IO) {
        val userId = userId() ?: return@withContext
        cancelPending(userId, TYPE_PLAYLIST, playlistId)
        val removed = cleaner.removeCollection(userId, TYPE_PLAYLIST, playlistId)
        offlineDao.deletePlaylist(userId, playlistId)
        Log.i(TAG, "Descarga de playlist $playlistId quitada: ${removed.size} canciones borradas")
    }

    suspend fun syncPlaylist(playlistId: String): Outcome = withContext(Dispatchers.IO) {
        val userId = userId() ?: return@withContext Outcome.Offline
        val stored = offlineDao.getPlaylist(userId, playlistId) ?: return@withContext Outcome.NotDownloaded
        val collection = offlineDao.getCollection(userId, TYPE_PLAYLIST, playlistId)
        val fetched = try {
            PlaylistManager(appContext).getSongsByPlaylistId(playlistId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val outcome = failureOutcome(e)
            if (outcome == Outcome.Unavailable) offlineDao.setPlaylistUnavailable(userId, playlistId, true)
            return@withContext outcome
        }
        val songs = fetched.filter { it.id.isNotBlank() }.distinctBy { it.id }
        if (stored.unavailable) offlineDao.setPlaylistUnavailable(userId, playlistId, false)

        val storedIds = OfflineAnalysisCodec.decodeIds(stored.songIdsJson)
        val diff = CollectionSyncDiff.songs(storedIds, songs.map { it.id })
        val missing = missingSongs(userId, TYPE_PLAYLIST, playlistId, songs)
        if (diff.isEmpty && missing.isEmpty()) return@withContext Outcome.UpToDate
        if (diff.added.isNotEmpty() && !downloader.hasEnoughSpace()) {
            offlineDao.setPlaylistPendingUpdate(userId, playlistId, true)
            return@withContext Outcome.PendingUpdate
        }

        val title = collection?.title ?: ""
        downloader.registerCollection(userId, TYPE_PLAYLIST, playlistId, title, diff.added)
        val failed = downloadSongs(userId, TYPE_PLAYLIST, playlistId, title, missing, onEvent = {})
        val removed = cleaner.removeFromCollection(userId, TYPE_PLAYLIST, playlistId, diff.removed)
        offlineDao.upsertPlaylist(
            stored.copy(
                songIdsJson = OfflineAnalysisCodec.encodeIds(songs.map { it.id }),
                syncedAtEpochMs = System.currentTimeMillis(),
                unavailable = false,
                pendingUpdate = failed.isNotEmpty()
            )
        )
        Log.i(TAG, "Playlist $playlistId sincronizada: +${diff.added.size} −${diff.removed.size} (borradas ${removed.size}, fallidas ${failed.size})")
        Outcome.Done(songs.size, failed.size)
    }

    // ─── Playmix ──────────────────────────────────────────────

    suspend fun downloadPlaymix(
        playmixId: String,
        onEvent: (CollectionDownloader.SongEvent) -> Unit = {}
    ): Outcome = withContext(Dispatchers.IO) {
        val userId = userId() ?: return@withContext Outcome.Offline
        if (!downloader.hasEnoughSpace()) return@withContext Outcome.NoSpace
        val detail = try {
            PlaymixManager(appContext).getPlaymixDetail(playmixId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext failureOutcome(e)
        }
        val songIds = CollectionSyncDiff.playmixSongIds(detail).distinct()
        if (songIds.isEmpty()) return@withContext Outcome.Empty

        // El detalle va primero: la descarga de los clips lo usa para pedir su URL firmada vigente.
        offlineDao.upsertPlaymix(
            OfflinePlaymix(userId, playmixId, OfflineCollectionStore.encodeDetail(detail), detail.contentVersion, System.currentTimeMillis())
        )
        downloader.registerCollection(userId, TYPE_PLAYMIX, playmixId, detail.name, songIds)
        val clipFailures = enqueueClips(userId, playmixId, CollectionSyncDiff.readyClips(detail))
        val songs = resolveSongs(songIds)
        val failed = downloadSongs(userId, TYPE_PLAYMIX, playmixId, detail.name, songs, onEvent)
        val unresolved = songIds.size - songs.size
        Log.i(TAG, "Playmix $playmixId descargada: ${songs.size} canciones, ${CollectionSyncDiff.readyClips(detail).size} clips, ${failed.size + unresolved} fallidas")
        Outcome.Done(songIds.size, failed.size + unresolved + clipFailures)
    }

    suspend fun removePlaymix(playmixId: String) = withContext(Dispatchers.IO) {
        val userId = userId() ?: return@withContext
        cancelPending(userId, TYPE_PLAYMIX, playmixId)
        val stored = offlineDao.getPlaymix(userId, playmixId)
        val clipKeys = offlineDao.getClips(userId, playmixId).map { it.cacheKey }.toMutableSet()
        stored?.let { OfflineCollectionStore.decodeDetail(it.detailJson) }
            ?.let { detail -> CollectionSyncDiff.readyClips(detail).forEach { clipKeys += OfflineClips.keyFor(it.transitionId, it.renderRevision) } }
        offlineDao.deletePlaymixPackage(userId, playmixId)
        clipKeys.forEach { removeClipIfUnused(it) }
        val removed = cleaner.removeCollection(userId, TYPE_PLAYMIX, playmixId)
        Log.i(TAG, "Descarga de Playmix $playmixId quitada: ${removed.size} canciones y ${clipKeys.size} clips")
    }

    suspend fun syncPlaymix(playmixId: String): Outcome = withContext(Dispatchers.IO) {
        val userId = userId() ?: return@withContext Outcome.Offline
        val stored = offlineDao.getPlaymix(userId, playmixId) ?: return@withContext Outcome.NotDownloaded
        val detail = try {
            PlaymixManager(appContext).getPlaymixDetail(playmixId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val outcome = failureOutcome(e)
            if (outcome == Outcome.Unavailable) offlineDao.setPlaymixUnavailable(userId, playmixId, true)
            return@withContext outcome
        }
        val storedDetail = OfflineCollectionStore.decodeDetail(stored.detailJson)
        val serverIds = CollectionSyncDiff.playmixSongIds(detail).distinct()
        val changed = CollectionSyncDiff.playmixChanged(stored.contentVersion, detail.contentVersion)
        val storedClips = offlineDao.getClips(userId, playmixId).map { ClipRef(it.transitionId, it.renderRevision) }
        val clipDiff = CollectionSyncDiff.clips(storedClips, CollectionSyncDiff.readyClips(detail))
        val missingSongIds = serverIds - songDao.getCompletedCollectionSongIds(userId, TYPE_PLAYMIX, playmixId).toSet()
        if (!changed && clipDiff.isEmpty && missingSongIds.isEmpty()) {
            if (stored.unavailable || stored.pendingUpdate) {
                offlineDao.upsertPlaymix(stored.copy(unavailable = false, pendingUpdate = false))
            }
            return@withContext Outcome.UpToDate
        }
        if (!downloader.hasEnoughSpace()) {
            offlineDao.upsertPlaymix(stored.copy(unavailable = false, pendingUpdate = true))
            return@withContext Outcome.PendingUpdate
        }

        val songDiff = CollectionSyncDiff.songs(storedDetail?.let(CollectionSyncDiff::playmixSongIds).orEmpty(), serverIds)
        // Detalle nuevo antes de los clips: su descarga lo usa para resolver la URL.
        offlineDao.upsertPlaymix(
            stored.copy(
                detailJson = OfflineCollectionStore.encodeDetail(detail),
                contentVersion = detail.contentVersion,
                syncedAtEpochMs = System.currentTimeMillis(),
                unavailable = false,
                pendingUpdate = false
            )
        )
        downloader.registerCollection(userId, TYPE_PLAYMIX, playmixId, detail.name, serverIds)
        if (clipDiff.toRemove.isNotEmpty()) {
            // Las revisiones antiguas dejan de referenciarse; la nueva se indexa al terminar su descarga.
            offlineDao.deleteClips(userId, playmixId, clipDiff.toRemove.map { it.transitionId })
            clipDiff.toRemove.forEach { removeClipIfUnused(OfflineClips.keyFor(it.transitionId, it.renderRevision)) }
        }
        val clipFailures = enqueueClips(userId, playmixId, clipDiff.toDownload)
        val toDownload = resolveSongs(missingSongIds.toList())
        val failed = downloadSongs(userId, TYPE_PLAYMIX, playmixId, detail.name, toDownload, onEvent = {})
        val removed = cleaner.removeFromCollection(userId, TYPE_PLAYMIX, playmixId, songDiff.removed)
        if (failed.isNotEmpty() || clipFailures > 0) offlineDao.setPlaymixPendingUpdate(userId, playmixId, true)
        Log.i(
            TAG,
            "Playmix $playmixId sincronizada: +${songDiff.added.size} −${songDiff.removed.size} canciones " +
                "(borradas ${removed.size}), clips +${clipDiff.toDownload.size} −${clipDiff.toRemove.size}"
        )
        Outcome.Done(serverIds.size, failed.size + clipFailures)
    }

    /** Sincroniza todas las colecciones descargadas del usuario (trabajo periódico). */
    suspend fun syncAll(): Int = withContext(Dispatchers.IO) {
        val userId = userId() ?: return@withContext 0
        var synced = 0
        offlineDao.getAllPlaylists(userId).forEach { playlist ->
            runCatching { syncPlaylist(playlist.playlistId) }
                .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Sincronización de playlist fallida", it) }
            synced++
        }
        offlineDao.getAllPlaymixes(userId).forEach { playmix ->
            runCatching { syncPlaymix(playmix.playmixId) }
                .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Sincronización de Playmix fallida", it) }
            synced++
        }
        synced
    }

    /** Progreso de la descarga (canciones y, en una Playmix, clips listos). */
    suspend fun progress(type: String, collectionId: String): CollectionProgress = withContext(Dispatchers.IO) {
        val userId = userId() ?: return@withContext CollectionProgress(0, 0, 0)
        val songs = downloader.progress(userId, type, collectionId)
        if (type != TYPE_PLAYMIX || songs.total == 0) return@withContext songs
        val detail = offlineDao.getPlaymix(userId, collectionId)?.let { OfflineCollectionStore.decodeDetail(it.detailJson) }
            ?: return@withContext songs
        val clips = CollectionSyncDiff.readyClips(detail)
        val doneClips = clips.count { OfflineClips.isCompleted(it.transitionId, it.renderRevision) }
        val total = songs.total + clips.size
        val completed = songs.completed + doneClips
        val percent = ((songs.percent * songs.total + doneClips * 100) / total).coerceIn(0, 100)
        CollectionProgress(total, completed, percent)
    }

    // ─── Internos ─────────────────────────────────────────────

    private suspend fun downloadSongs(
        userId: String,
        type: String,
        collectionId: String,
        title: String,
        songs: List<Song>,
        onEvent: (CollectionDownloader.SongEvent) -> Unit
    ): Set<String> {
        if (songs.isEmpty()) return emptySet()
        // Análisis completo guardado con cada canción: las transiciones inteligentes funcionan sin red.
        val enriched = downloader.withFullAnalysis(songs)
        downloader.seedResolutions(enriched)
        return downloader.downloadSongs(userId, DownloadCollectionRef(type, collectionId, title), enriched, onEvent)
    }

    /** Canciones de la colección que aún no están descargadas. */
    private suspend fun missingSongs(userId: String, type: String, collectionId: String, songs: List<Song>): List<Song> {
        val completed = songDao.getCompletedCollectionSongIds(userId, type, collectionId).toSet()
        return songs.filterNot { it.id in completed }
    }

    /** Canciones por id con `getSongById` (metadatos y análisis completo), en orden; las que fallan se omiten. */
    private suspend fun resolveSongs(songIds: List<String>): List<Song> = coroutineScope {
        val songManager = SongManager(appContext)
        val permits = Semaphore(4)
        songIds.map { id ->
            async { permits.withPermit { runCatching { songManager.getSongById(id) }.getOrNull() } }
        }.awaitAll().filterNotNull()
    }

    /** Encola los clips listos que no estén ya completos; devuelve cuántos no se pudieron encolar. */
    private suspend fun enqueueClips(userId: String, playmixId: String, clips: List<ClipRef>): Int {
        var failures = 0
        clips.forEach { clip ->
            val key = OfflineClips.keyFor(clip.transitionId, clip.renderRevision)
            runCatching {
                val existing = OfflineDownloadProvider.getDownloadManager(appContext).downloadIndex.getDownload(key)
                if (existing?.state == Download.STATE_COMPLETED) {
                    OfflineClips.markCompleted(key)
                    offlineDao.upsertClip(
                        OfflinePlaymixClip(userId, playmixId, clip.transitionId, clip.renderRevision, key, existing.bytesDownloaded)
                    )
                } else {
                    DownloadService.sendAddDownload(
                        appContext,
                        ResonantDownloadService::class.java,
                        OfflineClipDownloads.request(userId, playmixId, clip.transitionId, clip.renderRevision),
                        false
                    )
                }
            }.onFailure {
                failures++
                Log.w(TAG, "No se pudo encolar el clip ${clip.transitionId}", it)
            }
        }
        if (clips.isNotEmpty()) Log.i(TAG, "Clips encolados para $playmixId: ${clips.size - failures}")
        return failures
    }

    /** Borra el clip del disco si ninguna otra Playmix descargada lo referencia. */
    private suspend fun removeClipIfUnused(key: String) {
        if (offlineDao.countClipReferences(key) > 0) return
        OfflineClips.markRemoved(key)
        DownloadService.sendRemoveDownload(appContext, ResonantDownloadService::class.java, key, false)
    }

    /** Cancela las descargas aún no terminadas de la colección (como al cancelar un álbum). */
    private suspend fun cancelPending(userId: String, type: String, collectionId: String) {
        val all = songDao.getCollectionSongIds(userId, type, collectionId)
        val completed = songDao.getCompletedCollectionSongIds(userId, type, collectionId).toSet()
        all.filterNot { it in completed }.forEach { songId ->
            // Solo si ninguna otra colección o descarga suelta la necesita.
            if (songDao.countCollectionReferences(userId, songId) <= 1 && songDao.getById(userId, songId) == null) {
                DownloadService.sendRemoveDownload(appContext, ResonantDownloadService::class.java, songId, false)
            }
        }
    }

    private fun failureOutcome(error: Exception): Outcome {
        val code = (error as? HttpException)?.code()
        return when {
            CollectionSyncDiff.isUnavailable(code) -> Outcome.Unavailable
            else -> {
                Log.w(TAG, "Colección no disponible ahora", error)
                Outcome.Offline
            }
        }
    }

    companion object {
        private const val TAG = "OfflineCollections"
    }
}
