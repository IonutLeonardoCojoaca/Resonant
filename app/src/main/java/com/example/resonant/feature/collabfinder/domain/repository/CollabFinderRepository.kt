package com.example.resonant.feature.collabfinder.domain.repository

import com.example.resonant.feature.collabfinder.domain.model.ArtistSearchItem
import com.example.resonant.feature.collabfinder.domain.model.CollabDetail
import com.example.resonant.feature.collabfinder.domain.model.CollabFinderResult
import com.example.resonant.feature.collabfinder.domain.model.CollabPath

interface CollabFinderRepository {
    suspend fun searchArtist(query: String): Result<List<ArtistSearchItem>>
    suspend fun getCollaborators(
        artistId: String,
        sortBy: String = "count",
        minCollabs: Int = 1
    ): Result<CollabFinderResult>
    suspend fun getSharedSongs(
        artistId: String,
        collaboratorId: String,
        page: Int = 0
    ): Result<CollabDetail>
    suspend fun getCollabPath(
        fromId: String,
        toId: String
    ): Result<CollabPath>
}
