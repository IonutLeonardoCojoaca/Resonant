package com.example.resonant.feature.collabfinder.domain.model

data class CollabFinderResult(
    val centralArtist: ArtistSearchItem,
    val summary: CollabSummary,
    val collaborators: List<CollaboratorNode>,
    val totalCollaborators: Int
)

data class CollabSummary(
    val totalCollaborators: Int,
    val totalSharedSongs: Int,
    val yearsSpan: String,
    val topGenres: List<String>
)
