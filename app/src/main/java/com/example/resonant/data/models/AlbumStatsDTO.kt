package com.example.resonant.data.models

data class AlbumStatsDTO(
    val albumId: String = "",
    val title: String = "",
    val totalSongs: Int = 0,
    val totalStreams: Int = 0,
    val totalDurationSeconds: Int = 0,
    val totalFavorites: Int = 0
)
