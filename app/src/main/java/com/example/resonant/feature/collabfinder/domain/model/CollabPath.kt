package com.example.resonant.feature.collabfinder.domain.model

data class CollabPath(
    val found: Boolean,
    val hops: Int,
    val path: List<CollabPathStep>
)

data class CollabPathStep(
    val artist: ArtistSearchItem,
    val connectedVia: CollabPathSong?
)

data class CollabPathSong(
    val songId: String,
    val songTitle: String,
    val albumCoverUrl: String?,
    val releaseYear: Int?
)
