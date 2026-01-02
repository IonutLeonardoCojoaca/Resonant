package com.example.resonant.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloaded_songs")
data class DownloadedSong(
    @PrimaryKey val songId: String,
    val title: String,
    val artistName: String,
    val albumId: String?,
    val duration: String?,

    // Rutas locales (donde guardaremos los archivos)
    val localAudioPath: String,   // Archivo encriptado (.enc)
    val localImagePath: String?,  // Portada descargada (.jpg)

    // Metadata importante
    val downloadDate: Long,
    val sizeBytes: Long,

    // Guardaremos el AudioAnalysis completo como un String JSON
    // para poder reconstruirlo al reproducir offline.
    val audioAnalysisJson: String?
)