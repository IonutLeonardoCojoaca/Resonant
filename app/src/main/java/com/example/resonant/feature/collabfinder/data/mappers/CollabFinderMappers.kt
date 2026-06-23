package com.example.resonant.feature.collabfinder.data.mappers

import com.example.resonant.feature.collabfinder.data.dto.*
import com.example.resonant.feature.collabfinder.domain.model.*

fun ArtistSearchItemDto.toDomain(): ArtistSearchItem {
    return ArtistSearchItem(
        id = id,
        name = name,
        imageUrl = imageUrl
    )
}

fun ArtistPreviewDto.toDomain(): ArtistSearchItem {
    return ArtistSearchItem(
        id = id,
        name = name,
        imageUrl = imageUrl
    )
}

fun SharedSongPreviewDto.toDomain(): SharedSongPreview {
    return SharedSongPreview(
        id = id,
        title = title,
        albumTitle = albumTitle,
        albumCoverUrl = albumCoverUrl,
        releaseYear = releaseYear,
        durationMs = durationMs,
        streams = streams
    )
}

fun CollaboratorDto.toDomain(): CollaboratorNode {
    val tier = when {
        collaborationCount >= 5 -> CollabTier.GOLD
        collaborationCount >= 3 -> CollabTier.SILVER
        else -> CollabTier.BRONZE
    }
    
    return CollaboratorNode(
        id = artist.id,
        name = artist.name,
        imageUrl = artist.imageUrl,
        collaborationCount = collaborationCount,
        firstCollabYear = firstCollabYear,
        lastCollabYear = lastCollabYear,
        topSongs = topSongs.map { it.toDomain() },
        tier = tier
    )
}

fun CollabSummaryDto.toDomain(): CollabSummary {
    val yearsSpan = if (firstCollabYear != null && lastCollabYear != null) {
        if (firstCollabYear == lastCollabYear) "$firstCollabYear" else "$firstCollabYear – $lastCollabYear"
    } else {
        "Unknown"
    }
    
    return CollabSummary(
        totalCollaborators = totalCollaborators,
        totalSharedSongs = totalSharedSongs,
        yearsSpan = yearsSpan,
        topGenres = topGenresInCollabs
    )
}

fun CollabFinderResponseDto.toDomain(): CollabFinderResult {
    return CollabFinderResult(
        centralArtist = artist.toDomain(),
        summary = summary.toDomain(),
        collaborators = collaborators.map { it.toDomain() },
        totalCollaborators = summary.totalCollaborators
    )
}

fun SongArtistDto.toDomain(): SongArtist {
    return SongArtist(
        id = id,
        name = name
    )
}

fun SharedSongDto.toDomain(): SharedSong {
    return SharedSong(
        id = id,
        title = title,
        albumId = albumId,
        albumTitle = albumTitle,
        albumCoverUrl = albumCoverUrl,
        releaseYear = releaseYear,
        durationMs = durationMs,
        streams = streams,
        allArtists = allArtists.map { it.toDomain() }
    )
}

fun CollabDetailResponseDto.toDomain(): CollabDetail {
    val yearsSpan = if (firstCollabYear != null && lastCollabYear != null) {
        if (firstCollabYear == lastCollabYear) "$firstCollabYear" else "$firstCollabYear – $lastCollabYear"
    } else {
        "Unknown"
    }
    
    return CollabDetail(
        artistA = artistA.toDomain(),
        artistB = artistB.toDomain(),
        collaborationCount = collaborationCount,
        yearsSpan = yearsSpan,
        collabScore = collabScore,
        songs = songs.map { it.toDomain() },
        totalSongs = totalElements
    )
}

fun CollabPathSongDto.toDomain(): CollabPathSong {
    return CollabPathSong(
        songId = songId,
        songTitle = songTitle,
        albumCoverUrl = albumCoverUrl,
        releaseYear = releaseYear
    )
}

fun CollabPathStepDto.toDomain(): CollabPathStep {
    return CollabPathStep(
        artist = artist.toDomain(),
        connectedVia = connectedVia?.toDomain()
    )
}

fun CollabPathResponseDto.toDomain(): CollabPath {
    return CollabPath(
        found = found,
        hops = hops,
        path = path.map { it.toDomain() }
    )
}
