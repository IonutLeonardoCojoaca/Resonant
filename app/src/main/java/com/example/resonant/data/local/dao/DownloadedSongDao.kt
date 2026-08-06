package com.example.resonant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.resonant.data.local.entities.DownloadedSong
import com.example.resonant.data.local.entities.DownloadCollection
import com.example.resonant.data.local.entities.DownloadCollectionSong
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadedSongDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(song: DownloadedSong)

    @Query("SELECT * FROM downloaded_songs WHERE userId = :userId AND songId = :songId")
    suspend fun getById(userId: String, songId: String): DownloadedSong?

    @Query("SELECT * FROM downloaded_songs WHERE userId = :userId")
    fun getAllByUser(userId: String): Flow<List<DownloadedSong>>

    @Query("SELECT * FROM downloaded_songs WHERE userId = :userId")
    suspend fun getAllSyncByUser(userId: String): List<DownloadedSong>

    @Query("DELETE FROM downloaded_songs WHERE userId = :userId")
    suspend fun deleteAllByUser(userId: String)

    @Query("DELETE FROM downloaded_songs WHERE userId = :userId AND songId = :songId")
    suspend fun deleteSongById(userId: String, songId: String)

    @Query("SELECT COUNT(*) FROM downloaded_songs WHERE songId = :songId")
    suspend fun countBySongId(songId: String): Int

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
}
