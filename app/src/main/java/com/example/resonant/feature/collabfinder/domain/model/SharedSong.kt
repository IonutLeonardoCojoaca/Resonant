package com.example.resonant.feature.collabfinder.domain.model

data class SharedSong(
    val id: String,
    val title: String,
    val albumId: String?,
    val albumTitle: String?,
    val albumCoverUrl: String?,
    val releaseYear: Int?,
    val durationMs: Long?,
    val streams: Long?,
    val allArtists: List<SongArtist>
)

data class SongArtist(
    val id: String,
    val name: String
)
