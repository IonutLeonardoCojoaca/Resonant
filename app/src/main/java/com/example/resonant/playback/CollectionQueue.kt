package com.example.resonant.playback

import android.content.Context
import android.util.Log
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.managers.PlaylistManager
import com.example.resonant.managers.PlaymixManager
import com.example.resonant.managers.SongManager
import com.example.resonant.playback.flow.FlowQueuePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Inserción de un bloque de canciones en la cola (collection-queue-offline D2). Puro. */
object CollectionQueueInsertion {
    /**
     * Misma política que una canción suelta: tras la actual con "Reproducir a continuación"; al final de la cola
     * manual (antes de Flow) con "Añadir a la cola".
     */
    fun insertionIndex(
        playNext: Boolean,
        currentIndex: Int,
        size: Int,
        entryIds: List<String>,
        flowEntryIds: Set<String>
    ): Int = if (playNext) {
        (currentIndex + 1).coerceIn(0, size)
    } else {
        FlowQueuePolicy.insertionIndexForAdd(entryIds, flowEntryIds, currentIndex).coerceIn(0, size)
    }

    /** Inserta [block] en [index] como un bloque contiguo. */
    fun <T> insertBlock(items: List<T>, index: Int, block: List<T>): List<T> =
        items.toMutableList().apply { addAll(index.coerceIn(0, items.size), block) }
}

/**
 * Canciones de una playlist o Playmix para añadirlas a la cola (collection-queue-offline D2). Con la colección
 * descargada y sin red se usa lo guardado; si no, el servidor (y lo guardado si la petición falla).
 */
class CollectionQueueResolver(private val context: Context) {

    sealed class Result {
        data class Songs(
            val songs: List<Song>,
            /** Playmix de la que salen (para registrar sus mezclas), o `null` en una playlist. */
            val playmixId: String? = null,
            val bySongPair: Map<String, PlaymixTransitionDTO> = emptyMap()
        ) : Result()
        object Empty : Result()
        object Offline : Result()
        object Unsupported : Result()
    }

    suspend fun resolve(type: String, collectionId: String): Result = when (type) {
        TYPE_PLAYLIST -> resolvePlaylist(collectionId)
        TYPE_PLAYMIX -> resolvePlaymix(collectionId)
        else -> Result.Unsupported
    }

    private suspend fun resolvePlaylist(playlistId: String): Result {
        val online = OfflineCollectionStore.isOnline(context)
        if (!online) {
            return OfflineCollectionStore.playlistSongs(context, playlistId)?.let { Result.Songs(it) } ?: Result.Offline
        }
        val songs = try {
            PlaylistManager(context).getSongsByPlaylistId(playlistId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo pedir la playlist $playlistId", e)
            return OfflineCollectionStore.playlistSongs(context, playlistId)?.let { Result.Songs(it) } ?: Result.Offline
        }
        return if (songs.isEmpty()) Result.Empty else Result.Songs(songs)
    }

    private suspend fun resolvePlaymix(playmixId: String): Result {
        val online = OfflineCollectionStore.isOnline(context)
        val detail = if (online) {
            try {
                PlaymixManager(context).getPlaymixDetail(playmixId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo pedir la Playmix $playmixId", e)
                null
            }
        } else null
        val stored = detail ?: OfflineCollectionStore.playmixDetail(context, playmixId) ?: return Result.Offline
        val ordered = stored.songs.sortedBy { it.position }
        if (ordered.isEmpty()) return Result.Empty

        // Descargada: canciones y análisis guardados (no se pide nada al servidor).
        val offlineSongs = OfflineCollectionStore.playmixSongs(context, playmixId)
        val songs = if (offlineSongs != null && (detail == null || offlineSongs.size == ordered.size)) {
            offlineSongs
        } else if (detail != null) {
            fetchSongs(ordered.map { it.songId })
        } else {
            return Result.Offline
        }
        if (songs.isEmpty()) return Result.Offline
        return Result.Songs(songs, playmixId, QueueMixIndex.songPairMap(stored))
    }

    /** Como `PlaymixDetailViewModel.fetchSongsForPlayback`: cada canción con `getSongById`, en orden. */
    private suspend fun fetchSongs(songIds: List<String>): List<Song> = coroutineScope {
        val songManager = SongManager(context)
        val permits = Semaphore(6)
        songIds.map { id ->
            async { permits.withPermit { runCatching { songManager.getSongById(id) }.getOrNull() } }
        }.awaitAll().filterNotNull()
    }

    companion object {
        private const val TAG = "CollectionQueue"
        const val TYPE_PLAYLIST = OfflineCollectionStore.TYPE_PLAYLIST
        const val TYPE_PLAYMIX = OfflineCollectionStore.TYPE_PLAYMIX
    }
}
