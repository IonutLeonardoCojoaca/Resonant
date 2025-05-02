package com.example.spomusicapp

import retrofit2.http.GET
import retrofit2.http.Query

interface FirebaseFunctionService {

    @GET(".")
    suspend fun getSongs(
        @Query("limit") limit: Int,
        @Query("offset") offset: Int
    ): SongResponse

    @GET("searchSongs")
    suspend fun searchSongs(
        @Query("q") query: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int
    ): SongResponse


}

data class SongResponse(
    val songs: List<Song>
)
