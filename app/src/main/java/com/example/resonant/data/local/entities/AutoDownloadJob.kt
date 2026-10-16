package com.example.resonant.data.local.entities

import androidx.room.Entity
import androidx.room.Index

/**
 * Trabajo local de un plan de descarga automática. Guarda el resultado pendiente de informar
 * (`downloaded_unreported`) para reenviarlo en la siguiente ejecución aunque falle la red.
 * El esquema debe coincidir exactamente con `MIGRATION_5_6` de `AppDatabase`.
 */
@Entity(
    tableName = "auto_download_jobs",
    primaryKeys = ["userId", "planId"],
    indices = [Index(value = ["userId", "state"])]
)
data class AutoDownloadJob(
    val userId: String,
    val planId: String,
    val songId: String,
    val artistId: String,
    val artistName: String,
    /** Uno de [AutoDownloadJobState]. */
    val state: String,
    val attempts: Int,
    val sizeBytes: Long,
    val updatedAtEpochMs: Long
)

object AutoDownloadJobState {
    const val CLAIMED = "claimed"
    const val DOWNLOADED_UNREPORTED = "downloaded_unreported"
    const val REPORTED = "reported"
    const val FAILED = "failed"
}
