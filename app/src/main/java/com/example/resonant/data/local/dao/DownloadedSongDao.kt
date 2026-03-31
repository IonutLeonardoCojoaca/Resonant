package com.example.resonant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.resonant.data.local.entities.DownloadedSong
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
}