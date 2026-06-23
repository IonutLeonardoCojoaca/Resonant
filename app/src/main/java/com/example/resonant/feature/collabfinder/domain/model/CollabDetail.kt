package com.example.resonant.feature.collabfinder.domain.model

data class CollabDetail(
    val artistA: ArtistSearchItem,
    val artistB: ArtistSearchItem,
    val collaborationCount: Int,
    val yearsSpan: String,
    val collabScore: Int,
    val songs: List<SharedSong>,
    val totalSongs: Int
)
