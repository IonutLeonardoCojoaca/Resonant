package com.example.resonant.data.network.services

import com.example.resonant.data.network.ExploreResponseDTO
import com.example.resonant.data.network.ExploreSectionDTO
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

interface ExploreService {
    /** 200 with the layout, or 304 when [etag] still matches. */
    @GET("api/v2/explore")
    suspend fun getExplore(
        @Query("tz") tz: String,
        @Header("If-None-Match") etag: String? = null
    ): Response<ExploreResponseDTO>

    /** A section that arrived with `items: null`; [tz] must match the layout request. */
    @GET("api/v2/explore/sections/{id}")
    suspend fun getSection(
        @Path("id") sectionId: String,
        @Query("tz") tz: String
    ): Response<ExploreSectionDTO>
}
