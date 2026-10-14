package com.example.resonant.data.network.services

import com.example.resonant.data.network.LiveChannelsResponseDTO
import com.example.resonant.data.network.LiveNowResponseDTO
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

interface LiveService {
    @GET("api/live/channels")
    suspend fun getChannels(): Response<LiveChannelsResponseDTO>

    /** 200 also off air (`block: null`); 404 `LIVE_CHANNEL_NOT_FOUND` for an unknown or disabled slug. */
    @GET("api/live/channels/{slug}/now")
    suspend fun getNow(@Path("slug") slug: String): Response<LiveNowResponseDTO>
}
