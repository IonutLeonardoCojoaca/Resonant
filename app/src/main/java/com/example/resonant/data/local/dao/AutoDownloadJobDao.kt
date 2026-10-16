package com.example.resonant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.resonant.data.local.entities.AutoDownloadJob
import kotlinx.coroutines.flow.Flow

@Dao
interface AutoDownloadJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: AutoDownloadJob)

    @Query("SELECT * FROM auto_download_jobs WHERE userId = :userId AND planId = :planId")
    suspend fun get(userId: String, planId: String): AutoDownloadJob?

    @Query("SELECT * FROM auto_download_jobs WHERE userId = :userId AND state = :state ORDER BY updatedAtEpochMs")
    suspend fun getByState(userId: String, state: String): List<AutoDownloadJob>

    @Query(
        "UPDATE auto_download_jobs SET state = :state, updatedAtEpochMs = :nowMs " +
            "WHERE userId = :userId AND planId = :planId"
    )
    suspend fun updateState(userId: String, planId: String, state: String, nowMs: Long)

    @Query("SELECT * FROM auto_download_jobs WHERE userId = :userId ORDER BY updatedAtEpochMs DESC LIMIT :limit")
    fun recentByUser(userId: String, limit: Int): Flow<List<AutoDownloadJob>>
}
