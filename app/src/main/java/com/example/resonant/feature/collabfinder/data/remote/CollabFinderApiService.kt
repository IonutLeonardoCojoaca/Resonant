package com.example.resonant.feature.collabfinder.data.remote

import com.example.resonant.feature.collabfinder.data.dto.ArtistSearchItemDto
import com.example.resonant.feature.collabfinder.data.dto.ArtistSearchResponseWrapper
import com.example.resonant.feature.collabfinder.data.dto.CollabDetailResponseDto
import com.example.resonant.feature.collabfinder.data.dto.CollabFinderResponseDto
import com.example.resonant.feature.collabfinder.data.dto.CollabPathResponseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface CollabFinderApiService {

    @GET("api/artists/search")
    suspend fun searchArtists(
        @Query("q") query: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 10
    ): Response<ArtistSearchResponseWrapper>

    @GET("api/artists/{id}/collaborators")
    suspend fun getCollaborators(
        @Path("id") artistId: String,
        @Query("sortBy") sortBy: String = "count",
        @Query("minCollabs") minCollabs: Int = 1,
        @Query("genreId") genreId: String? = null,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 50
    ): Response<CollabFinderResponseDto>

    @GET("api/artists/{id}/collaborators/{collaboratorId}/shared-songs")
    suspend fun getSharedSongs(
        @Path("id") artistId: String,
        @Path("collaboratorId") collaboratorId: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20,
        @Query("sortBy") sortBy: String = "streams"
    ): Response<CollabDetailResponseDto>

    @GET("api/artists/collab-path")
    suspend fun getCollabPath(
        @Query("fromId") fromId: String,
        @Query("toId") toId: String,
        @Query("maxDepth") maxDepth: Int = 4
    ): Response<CollabPathResponseDto>
}
