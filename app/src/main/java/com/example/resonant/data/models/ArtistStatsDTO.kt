package com.example.resonant.data.models

data class ArtistStatsDTO(
    val artistId: String = "",
    val name: String = "",
    val totalSongs: Int = 0,
    val totalAlbums: Int = 0,
    val totalStreams: Int = 0,
    val totalFavorites: Int = 0
)
