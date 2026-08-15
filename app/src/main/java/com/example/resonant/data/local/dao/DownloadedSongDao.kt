package com.example.resonant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.resonant.data.local.entities.DownloadedSong
import com.example.resonant.data.local.entities.DownloadCollection
import com.example.resonant.data.local.entities.DownloadCollectionSong
import kotlinx.coroutines.flow.Flow

data class SongIdCount(val songId: String, val count: Int)

@Dao
interface DownloadedSongDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(song: DownloadedSong)

    @Query("SELECT * FROM downloaded_songs WHERE userId = :userId AND songId = :songId")
    suspend fun getById(userId: String, songId: String): DownloadedSong?

    @Query("SELECT * FROM downloaded_songs WHERE userId = :userId AND songId IN (:songIds)")
    suspend fun getByIds(userId: String, songIds: List<String>): List<DownloadedSong>

    @Query("SELECT * FROM downloaded_songs WHERE userId = :userId")
    fun getAllByUser(userId: String): Flow<List<DownloadedSong>>

    @Query("SELECT songId FROM downloaded_songs WHERE userId = :userId")
    fun getAllSongIdsByUser(userId: String): Flow<List<String>>

    @Query("SELECT * FROM downloaded_songs WHERE userId = :userId")
    suspend fun getAllSyncByUser(userId: String): List<DownloadedSong>

    @Query("DELETE FROM downloaded_songs WHERE userId = :userId")
    suspend fun deleteAllByUser(userId: String)

    @Query("DELETE FROM downloaded_songs WHERE userId = :userId AND songId = :songId")
    suspend fun deleteSongById(userId: String, songId: String)

    @Query("DELETE FROM downloaded_songs WHERE userId = :userId AND songId IN (:songIds)")
    suspend fun deleteSongsByIds(userId: String, songIds: List<String>)

    @Query("SELECT COUNT(*) FROM downloaded_songs WHERE songId = :songId")
    suspend fun countBySongId(songId: String): Int

    // Recuento global (todos los usuarios) por songId en un solo round-trip,
    // en vez de una query countBySongId por cada canción del lote.
    @Query("SELECT songId, COUNT(*) AS count FROM downloaded_songs WHERE songId IN (:songIds) GROUP BY songId")
    suspend fun countBySongIds(songIds: List<String>): List<SongIdCount>

    @Query(
        "UPDATE downloaded_songs SET isIndividuallySaved = :saved " +
            "WHERE userId = :userId AND songId = :songId"
    )
    suspend fun setIndividuallySaved(userId: String, songId: String, saved: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollection(collection: DownloadCollection)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCollectionSongs(items: List<DownloadCollectionSong>)

    @Query(
        "SELECT songId FROM download_collection_songs " +
            "WHERE userId = :userId AND collectionType = :type AND collectionId = :collectionId"
    )
    suspend fun getCollectionSongIds(
        userId: String,
        type: String,
        collectionId: String
    ): List<String>

    @Query(
        "SELECT refs.songId FROM download_collection_songs AS refs " +
            "INNER JOIN downloaded_songs AS downloaded " +
            "ON downloaded.userId = refs.userId AND downloaded.songId = refs.songId " +
            "WHERE refs.userId = :userId AND refs.collectionType = :type " +
            "AND refs.collectionId = :collectionId"
    )
    suspend fun getCompletedCollectionSongIds(
        userId: String,
        type: String,
        collectionId: String
    ): List<String>

    @Query(
        "SELECT COUNT(*) FROM download_collection_songs " +
            "WHERE userId = :userId AND songId = :songId"
    )
    suspend fun countCollectionReferences(userId: String, songId: String): Int

    // Recuento de referencias por songId en un solo round-trip para el lote
    // completo, en vez de una query countCollectionReferences por canción.
    @Query(
        "SELECT songId, COUNT(*) AS count FROM download_collection_songs " +
            "WHERE userId = :userId AND songId IN (:songIds) GROUP BY songId"
    )
    suspend fun countCollectionReferencesForSongs(userId: String, songIds: List<String>): List<SongIdCount>

    @Query(
        "DELETE FROM download_collection_songs " +
            "WHERE userId = :userId AND collectionType = :type AND collectionId = :collectionId"
    )
    suspend fun deleteCollectionSongs(
        userId: String,
        type: String,
        collectionId: String
    )

    @Query(
        "DELETE FROM download_collections " +
            "WHERE userId = :userId AND collectionType = :type AND collectionId = :collectionId"
    )
    suspend fun deleteCollection(userId: String, type: String, collectionId: String)

    @Query("DELETE FROM download_collection_songs WHERE userId = :userId")
    suspend fun deleteAllCollectionSongsByUser(userId: String)

    @Query("DELETE FROM download_collections WHERE userId = :userId")
    suspend fun deleteAllCollectionsByUser(userId: String)

    @Transaction
    suspend fun insertCollectionWithSongs(
        collection: DownloadCollection,
        songs: List<DownloadCollectionSong>
    ) {
        insertCollection(collection)
        insertCollectionSongs(songs)
    }

    @Transaction
    suspend fun deleteCollectionAndSongRefs(userId: String, type: String, collectionId: String) {
        deleteCollectionSongs(userId, type, collectionId)
        deleteCollection(userId, type, collectionId)
    }
}
