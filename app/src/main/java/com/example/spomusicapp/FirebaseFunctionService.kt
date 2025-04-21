package com.example.spomusicapp

import retrofit2.http.GET

interface FirebaseFunctionService {
    @GET(".")
    suspend fun getSongs(): SongResponse
}

data class SongResponse(
    val songs: List<Song>
)
