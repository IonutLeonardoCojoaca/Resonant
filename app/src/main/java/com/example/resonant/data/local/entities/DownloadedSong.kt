package com.example.resonant.data.local.entities

import androidx.room.Entity
import com.example.resonant.data.models.AlbumSimpleDTO

@Entity(tableName = "downloaded_songs", primaryKeys = ["userId", "songId"])
data class DownloadedSong(
    val userId: String,
    val songId: String,
    val title: String,
    val artistName: String,
    val album: AlbumSimpleDTO?,
    val duration: String?,

    val localAudioPath: String,
    val localImagePath: String?,

    // Metadata importante
    val downloadDate: Long,
    val sizeBytes: Long,

    val audioAnalysisJson: String?
)