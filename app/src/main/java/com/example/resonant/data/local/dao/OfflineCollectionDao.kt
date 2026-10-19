package com.example.resonant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.resonant.data.local.entities.DownloadCollection
import com.example.resonant.data.local.entities.OfflinePlaylist
import com.example.resonant.data.local.entities.OfflinePlaymix
import com.example.resonant.data.local.entities.OfflinePlaymixClip
import kotlinx.coroutines.flow.Flow

/** Resumen de una colección descargada para la pantalla de Descargas. */
data class DownloadedCollectionSummary(
    val collectionType: String,
    val collectionId: String,
    val title: String,
    val songCount: Int,
    val completedCount: Int,
    val sizeBytes: Long
)

/** Paquetes sin conexión de Playmix y playlists (collection-queue-offline). */
@Dao
interface OfflineCollectionDao {

    // ── Playmix ──

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaymix(playmix: OfflinePlaymix)

    @Query("SELECT * FROM offline_playmix WHERE userId = :userId AND playmixId = :playmixId")
    suspend fun getPlaymix(userId: String, playmixId: String): OfflinePlaymix?

    @Query("SELECT * FROM offline_playmix WHERE userId = :userId")
    suspend fun getAllPlaymixes(userId: String): List<OfflinePlaymix>

    @Query("UPDATE offline_playmix SET unavailable = :unavailable WHERE userId = :userId AND playmixId = :playmixId")
    suspend fun setPlaymixUnavailable(userId: String, playmixId: String, unavailable: Boolean)

    @Query("UPDATE offline_playmix SET pendingUpdate = :pending WHERE userId = :userId AND playmixId = :playmixId")
    suspend fun setPlaymixPendingUpdate(userId: String, playmixId: String, pending: Boolean)

    @Query("DELETE FROM offline_playmix WHERE userId = :userId AND playmixId = :playmixId")
    suspend fun deletePlaymix(userId: String, playmixId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertClip(clip: OfflinePlaymixClip)

    @Query("SELECT * FROM offline_playmix_clip WHERE userId = :userId AND playmixId = :playmixId")
    suspend fun getClips(userId: String, playmixId: String): List<OfflinePlaymixClip>

    @Query("DELETE FROM offline_playmix_clip WHERE userId = :userId AND playmixId = :playmixId AND transitionId IN (:transitionIds)")
    suspend fun deleteClips(userId: String, playmixId: String, transitionIds: List<String>)

    @Query("DELETE FROM offline_playmix_clip WHERE userId = :userId AND playmixId = :playmixId")
    suspend fun deleteAllClips(userId: String, playmixId: String)

    /**
     * Playmix descargada que contiene la transición (para volver a pedir su `render.url` al descargar el clip).
     * El detalle se guarda antes de encolar sus clips, así que basta buscar el id en el JSON.
     */
    @Query("SELECT playmixId FROM offline_playmix WHERE detailJson LIKE '%' || :transitionId || '%' LIMIT 1")
    fun findPlaymixIdForTransitionBlocking(transitionId: String): String?

    /** Otras referencias (de cualquier usuario o Playmix) a la misma clave de caché: el clip no se borra del disco. */
    @Query("SELECT COUNT(*) FROM offline_playmix_clip WHERE cacheKey = :cacheKey")
    suspend fun countClipReferences(cacheKey: String): Int

    @Transaction
    suspend fun deletePlaymixPackage(userId: String, playmixId: String) {
        deleteAllClips(userId, playmixId)
        deletePlaymix(userId, playmixId)
    }

    // ── Playlist ──

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(playlist: OfflinePlaylist)

    @Query("SELECT * FROM offline_playlist WHERE userId = :userId AND playlistId = :playlistId")
    suspend fun getPlaylist(userId: String, playlistId: String): OfflinePlaylist?

    @Query("SELECT * FROM offline_playlist WHERE userId = :userId")
    suspend fun getAllPlaylists(userId: String): List<OfflinePlaylist>

    @Query("UPDATE offline_playlist SET unavailable = :unavailable WHERE userId = :userId AND playlistId = :playlistId")
    suspend fun setPlaylistUnavailable(userId: String, playlistId: String, unavailable: Boolean)

    @Query("UPDATE offline_playlist SET pendingUpdate = :pending WHERE userId = :userId AND playlistId = :playlistId")
    suspend fun setPlaylistPendingUpdate(userId: String, playlistId: String, pending: Boolean)

    @Query("DELETE FROM offline_playlist WHERE userId = :userId AND playlistId = :playlistId")
    suspend fun deletePlaylist(userId: String, playlistId: String)

    // ── Borrar todo (Descargas → "Eliminar todo") ──

    @Query("SELECT * FROM offline_playmix_clip WHERE userId = :userId")
    suspend fun getAllClipsByUser(userId: String): List<OfflinePlaymixClip>

    @Query("DELETE FROM offline_playmix_clip WHERE userId = :userId")
    suspend fun deleteAllClipsByUser(userId: String)

    @Query("DELETE FROM offline_playmix WHERE userId = :userId")
    suspend fun deleteAllPlaymixesByUser(userId: String)

    @Query("DELETE FROM offline_playlist WHERE userId = :userId")
    suspend fun deleteAllPlaylistsByUser(userId: String)

    @Transaction
    suspend fun deleteAllPackagesByUser(userId: String) {
        deleteAllClipsByUser(userId)
        deleteAllPlaymixesByUser(userId)
        deleteAllPlaylistsByUser(userId)
    }

    // ── Colecciones (tablas genéricas de descargas) ──

    @Query(
        "SELECT * FROM download_collections WHERE userId = :userId AND collectionType = :type " +
            "AND collectionId = :collectionId"
    )
    suspend fun getCollection(userId: String, type: String, collectionId: String): DownloadCollection?

    @Query("SELECT * FROM download_collections WHERE userId = :userId AND collectionType IN (:types)")
    suspend fun getCollections(userId: String, types: List<String>): List<DownloadCollection>

    /** Colecciones descargadas con su número de canciones, las ya descargadas y el espacio que ocupan. */
    @Query(
        "SELECT c.collectionType AS collectionType, c.collectionId AS collectionId, c.title AS title, " +
            "COUNT(r.songId) AS songCount, COUNT(d.songId) AS completedCount, " +
            "COALESCE(SUM(d.sizeBytes), 0) AS sizeBytes " +
            "FROM download_collections AS c " +
            "LEFT JOIN download_collection_songs AS r ON r.userId = c.userId " +
            "AND r.collectionType = c.collectionType AND r.collectionId = c.collectionId " +
            "LEFT JOIN downloaded_songs AS d ON d.userId = r.userId AND d.songId = r.songId " +
            "WHERE c.userId = :userId AND c.collectionType IN (:types) " +
            "GROUP BY c.collectionType, c.collectionId, c.title ORDER BY c.createdAtEpochMs DESC"
    )
    fun observeCollectionSummaries(userId: String, types: List<String>): Flow<List<DownloadedCollectionSummary>>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM offline_playmix_clip WHERE userId = :userId AND playmixId = :playmixId")
    suspend fun getClipBytes(userId: String, playmixId: String): Long
}
