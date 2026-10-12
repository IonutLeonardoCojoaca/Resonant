package com.example.resonant.data.network.services

import com.example.resonant.data.network.CapabilitiesDTO
import com.example.resonant.data.network.ServerTimeDTO
import retrofit2.http.GET

interface PlatformService {
    @GET("api/time")
    suspend fun getServerTime(): ServerTimeDTO

    @GET("api/capabilities")
    suspend fun getCapabilities(): CapabilitiesDTO
}
