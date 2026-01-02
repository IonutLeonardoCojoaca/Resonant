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

    @Query("SELECT * FROM downloaded_songs WHERE songId = :id")
    suspend fun getById(id: String): DownloadedSong?

    @Query("DELETE FROM downloaded_songs WHERE songId = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM downloaded_songs")
    fun getAll(): Flow<List<DownloadedSong>>

    @Query("SELECT * FROM downloaded_songs WHERE songId = :songId")
    suspend fun getDownloadedSong(songId: String): DownloadedSong?

    @Query("SELECT * FROM downloaded_songs") // Asegúrate de que el nombre de la tabla es correcto
    suspend fun getAllSync(): List<DownloadedSong>

    @Query("DELETE FROM downloaded_songs")
    suspend fun deleteAll()

    @Query("DELETE FROM downloaded_songs WHERE songId = :songId")
    suspend fun deleteSongById(songId: String)
}