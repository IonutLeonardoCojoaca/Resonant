package com.example.resonant.playback

import com.example.resonant.data.models.AlbumSimpleDTO
import com.example.resonant.data.models.Song
import com.google.gson.Gson

data class OfflineDownloadMetadata(
    val userId: String,
    val songId: String,
    val title: String,
    val artistName: String,
    val album: AlbumSimpleDTO?,
    val duration: String?,
    val coverUrl: String?,
    val audioAnalysisJson: String?,
    val collectionType: String? = null,
    val collectionId: String? = null
) {
    fun encode(): ByteArray = Gson().toJson(this).toByteArray(Charsets.UTF_8)

    companion object {
        fun from(
            song: Song,
            userId: String,
            collectionType: String? = null,
            collectionId: String? = null
        ): OfflineDownloadMetadata {
            val gson = Gson()
            return OfflineDownloadMetadata(
                userId = userId,
                songId = song.id,
                title = song.title,
                artistName = song.artistName
                    ?: song.artists.joinToString(", ") { it.name }
                        .ifBlank { "Desconocido" },
                album = song.album,
                duration = song.duration,
                coverUrl = song.coverUrl,
                audioAnalysisJson = song.audioAnalysis?.let(gson::toJson),
                collectionType = collectionType,
                collectionId = collectionId
            )
        }

        fun decode(data: ByteArray): OfflineDownloadMetadata? {
            if (data.isEmpty()) return null
            return runCatching {
                Gson().fromJson(data.toString(Charsets.UTF_8), OfflineDownloadMetadata::class.java)
            }.getOrNull()
        }
    }
}
