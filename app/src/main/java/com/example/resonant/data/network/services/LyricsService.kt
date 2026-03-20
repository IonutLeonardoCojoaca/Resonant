package com.example.resonant.data.network.services

import com.example.resonant.data.network.LyricsResponse
import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Path

interface LyricsService {

    @GET("api/songs/{id}/lyrics")
    suspend fun getLyrics(@Path("id") songId: String): LyricsResponse
}
