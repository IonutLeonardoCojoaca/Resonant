package com.example.resonant.data.network.services

import com.example.resonant.data.network.ApplyPresetRequest
import com.example.resonant.data.network.MixLaneOptionsDTO
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.data.network.TransitionPresetDTO
import com.example.resonant.data.network.TransitionPresetPreviewDTO
import com.example.resonant.data.network.TransitionSuggestRequest
import com.example.resonant.data.network.TransitionSuggestionsDTO
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface TransitionPresetService {

    @GET("api/v1/transition-presets")
    suspend fun getPresets(): List<TransitionPresetDTO>

    /** Catálogo de opciones por carril de los documentos v2. */
    @GET("api/v1/transition-presets/lane-options")
    suspend fun getLaneOptions(): MixLaneOptionsDTO

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

    /** Presets v2 ordenados para una pareja (404 si el backend no tiene activadas las recetas). */
    @POST("api/transitions/suggest")
    suspend fun suggest(@Body body: TransitionSuggestRequest): Response<TransitionSuggestionsDTO>
}
