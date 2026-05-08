package com.example.resonant.data.network.services

import com.example.resonant.data.network.ApplyPresetRequest
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.data.network.TransitionPresetDTO
import com.example.resonant.data.network.TransitionPresetPreviewDTO
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface TransitionPresetService {

    @GET("api/v1/transition-presets")
    suspend fun getPresets(): List<TransitionPresetDTO>

    @GET("api/v1/transition-presets/{code}")
    suspend fun getPreset(@Path("code") code: String): TransitionPresetDTO

    @POST("api/playmix/{playmixId}/transitions/{transitionId}/preview-preset")
    suspend fun previewPreset(
        @Path("playmixId") playmixId: String,
        @Path("transitionId") transitionId: String,
        @Body body: ApplyPresetRequest
    ): TransitionPresetPreviewDTO

    @POST("api/playmix/{playmixId}/transitions/{transitionId}/apply-preset")
    suspend fun applyPreset(
        @Path("playmixId") playmixId: String,
        @Path("transitionId") transitionId: String,
        @Body body: ApplyPresetRequest
    ): PlaymixTransitionDTO

    @POST("api/playmix/{playmixId}/transitions/{transitionId}/reset-preset")
    suspend fun resetPreset(
        @Path("playmixId") playmixId: String,
        @Path("transitionId") transitionId: String
    ): PlaymixTransitionDTO
}
