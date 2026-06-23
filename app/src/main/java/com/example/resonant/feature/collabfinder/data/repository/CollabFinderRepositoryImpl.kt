package com.example.resonant.feature.collabfinder.data.repository

import com.example.resonant.feature.collabfinder.data.mappers.toDomain
import com.example.resonant.feature.collabfinder.data.remote.CollabFinderApiService
import com.example.resonant.feature.collabfinder.domain.model.ArtistSearchItem
import com.example.resonant.feature.collabfinder.domain.model.CollabDetail
import com.example.resonant.feature.collabfinder.domain.model.CollabFinderResult
import com.example.resonant.feature.collabfinder.domain.model.CollabPath
import com.example.resonant.feature.collabfinder.domain.repository.CollabFinderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CollabFinderRepositoryImpl(
    private val apiService: CollabFinderApiService
) : CollabFinderRepository {

    override suspend fun searchArtist(query: String): Result<List<ArtistSearchItem>> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.searchArtists(query)
            if (response.isSuccessful) {
                val wrapper = response.body()
                val dtoList = wrapper?.getArtists() ?: emptyList()
                val items = dtoList.map { it.toDomain() }
                Result.success(items)
            } else {
                Result.failure(Exception("Error searching artists: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getCollaborators(
        artistId: String,
        sortBy: String,
        minCollabs: Int
    ): Result<CollabFinderResult> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getCollaborators(artistId, sortBy, minCollabs)
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    Result.success(body.toDomain())
                } else {
                    Result.failure(Exception("Empty response"))
                }
            } else {
                Result.failure(Exception("Error getting collaborators: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getSharedSongs(
        artistId: String,
        collaboratorId: String,
        page: Int
    ): Result<CollabDetail> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getSharedSongs(artistId, collaboratorId, page)
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    Result.success(body.toDomain())
                } else {
                    Result.failure(Exception("Empty response"))
                }
            } else {
                Result.failure(Exception("Error getting shared songs: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getCollabPath(fromId: String, toId: String): Result<CollabPath> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getCollabPath(fromId, toId)
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    Result.success(body.toDomain())
                } else {
                    Result.failure(Exception("Empty response"))
                }
            } else {
                Result.failure(Exception("Error getting collab path: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
