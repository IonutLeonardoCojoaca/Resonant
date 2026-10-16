package com.example.resonant.playback

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadService
import com.example.resonant.data.local.dao.DownloadedSongDao
import com.example.resonant.data.local.entities.DownloadedSong
import com.example.resonant.services.ResonantDownloadService
import java.io.File

/** Borra del dispositivo el audio y la portada de una canción que ya no usa ningún usuario. */
fun interface OfflineMediaRemover {
    fun remove(songId: String, audioPath: String, imagePath: String?)
}

/** Ficheros offline: audio de Media3 (URI estable) o fichero legacy, y portada. */
@OptIn(UnstableApi::class)
object OfflineMediaFiles {
    private const val TAG = "OfflineMediaFiles"

    fun removeAudio(context: Context, songId: String, storedPath: String) {
        if (storedPath.isStablePlaybackUri()) {
            DownloadService.sendRemoveDownload(
                context,
                ResonantDownloadService::class.java,
                songId,
                false
            )
            return
        }
        val audioFile = File(storedPath).takeIf { it.isAbsolute }
            ?: File(context.filesDir, storedPath.removePrefix("/"))
        if (audioFile.exists()) {
            Log.d(TAG, "Archivo de audio borrado: ${audioFile.delete()}")
        }
    }

    fun deleteCover(context: Context, path: String?) {
        if (path.isNullOrBlank()) return
        val file = File(path).takeIf { it.isAbsolute }
            ?: File(context.filesDir, path.removePrefix("/"))
        if (file.exists()) file.delete()
    }

    fun isStableUri(path: String): Boolean {
        return path.startsWith(
            "${PlaybackUrlResolver.STABLE_SCHEME}://" +
                "${PlaybackUrlResolver.STABLE_AUTHORITY}/",
            ignoreCase = true
        )
    }

    private fun String.isStablePlaybackUri(): Boolean = isStableUri(this)

    fun remover(context: Context): OfflineMediaRemover {
        val appContext = context.applicationContext
        return OfflineMediaRemover { songId, audioPath, imagePath ->
            removeAudio(appContext, songId, audioPath)
            deleteCover(appContext, imagePath)
        }
    }
}

/**
 * Quita referencias de colecciones de descarga (álbum, `auto_artist`…) y borra solo las canciones
 * que quedan huérfanas: no guardadas a mano, sin otras referencias del usuario. El audio solo se
 * borra del dispositivo si ningún otro usuario la tiene descargada. Se llama desde un hilo de IO.
 */
class OfflineDownloadCleaner(
    private val dao: DownloadedSongDao,
    private val mediaRemover: OfflineMediaRemover
) {
    /** Quita una colección completa (p. ej. un álbum) y devuelve las canciones borradas. */
    suspend fun removeCollection(userId: String, type: String, collectionId: String): List<String> {
        val songIds = dao.getCollectionSongIds(userId, type, collectionId)
        dao.deleteCollectionAndSongRefs(userId, type, collectionId)
        return deleteOrphans(userId, songIds)
    }

    /** Quita las referencias de [type] de esas canciones y devuelve las que se han borrado. */
    suspend fun removeFromCollectionType(userId: String, type: String, songIds: List<String>): List<String> {
        if (songIds.isEmpty()) return emptyList()
        dao.removeSongsFromCollectionType(userId, type, songIds)
        return deleteOrphans(userId, songIds)
    }

    private suspend fun deleteOrphans(userId: String, songIds: List<String>): List<String> {
        if (songIds.isEmpty()) return emptyList()
        val entitiesById = dao.getByIds(userId, songIds).associateBy { it.songId }
        val referenceCounts = dao.countCollectionReferencesForSongs(userId, songIds)
            .associate { it.songId to it.count }

        val orphanedSongIds = selectOrphans(songIds, entitiesById, referenceCounts)
        if (orphanedSongIds.isEmpty()) return emptyList()

        dao.deleteSongsByIds(userId, orphanedSongIds)
        val remainingGlobalCounts = dao.countBySongIds(orphanedSongIds)
            .associate { it.songId to it.count }
        orphanedSongIds.forEach { songId ->
            if ((remainingGlobalCounts[songId] ?: 0) == 0) {
                val entity = entitiesById.getValue(songId)
                mediaRemover.remove(songId, entity.localAudioPath, entity.localImagePath)
            }
        }
        return orphanedSongIds
    }

    companion object {
        /** Huérfana = descargada, no guardada a mano y sin referencias de ninguna colección. */
        fun selectOrphans(
            songIds: List<String>,
            entitiesById: Map<String, DownloadedSong>,
            referenceCounts: Map<String, Int>
        ): List<String> = songIds.filter { songId ->
            val entity = entitiesById[songId]
            entity != null &&
                !entity.isIndividuallySaved &&
                (referenceCounts[songId] ?: 0) == 0
        }
    }
}
