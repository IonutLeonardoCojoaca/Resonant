package com.example.resonant.data.network.services

import com.example.resonant.data.network.UniversalSearchResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface UniversalSearchService {
    @GET("api/search/v2")
    suspend fun search(
        @Query("q") query: String,
        @Query("types") types: String? = null,
        @Query("limit") limit: Int = 100
    ): UniversalSearchResponse
}
