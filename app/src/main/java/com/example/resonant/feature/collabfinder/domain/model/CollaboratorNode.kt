package com.example.resonant.feature.collabfinder.domain.model

data class CollaboratorNode(
    val id: String,
    val name: String,
    val imageUrl: String?,
    val collaborationCount: Int,
    val firstCollabYear: Int?,
    val lastCollabYear: Int?,
    val topSongs: List<SharedSongPreview>,
    val tier: CollabTier
)

enum class CollabTier {
    GOLD,
    SILVER,
    BRONZE
}

data class SharedSongPreview(
    val id: String,
    val title: String,
    val albumTitle: String?,
    val albumCoverUrl: String?,
    val releaseYear: Int?,
    val durationMs: Long?,
    val streams: Long?
)
