package com.example.resonant.data.network.services

import com.example.resonant.data.network.HomeResponseDTO
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header

interface HomeService {
    @GET("api/v2/home")
    suspend fun getHome(
        @Header("If-None-Match") etag: String? = null
    ): Response<HomeResponseDTO>
}
