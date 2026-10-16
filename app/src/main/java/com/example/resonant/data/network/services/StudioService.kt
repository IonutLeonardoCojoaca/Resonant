package com.example.resonant.data.network.services

import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.data.network.StudioPresetRequest
import com.example.resonant.data.network.StudioPresetResponseDTO
import com.example.resonant.data.network.StudioSaveRequest
import com.example.resonant.data.network.StudioTransitionDTO
import com.example.resonant.data.network.TransitionSuggestionsDTO
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Resonant Studio: documento de mezcla de una transición de Playmix (404 si Studio está apagado). */
interface StudioService {

    @GET("api/playmix/{id}/transitions/{tId}/studio")
    suspend fun get(@Path("id") playmixId: String, @Path("tId") transitionId: String): StudioTransitionDTO

    /** 409 `MIX_DOCUMENT_REVISION_CONFLICT` si la revisión no es la actual. */
    @PUT("api/playmix/{id}/transitions/{tId}/studio")
    suspend fun save(
        @Path("id") playmixId: String,
        @Path("tId") transitionId: String,
        @Body body: StudioSaveRequest
    ): StudioTransitionDTO

    /** Documento que produce un estilo para esas dos canciones, sin guardarlo. */
    @POST("api/playmix/{id}/transitions/{tId}/studio/preset")
    suspend fun preset(
        @Path("id") playmixId: String,
        @Path("tId") transitionId: String,
        @Body body: StudioPresetRequest
    ): StudioPresetResponseDTO

    @GET("api/playmix/{id}/transitions/{tId}/studio/suggestions")
    suspend fun suggestions(
        @Path("id") playmixId: String,
        @Path("tId") transitionId: String,
        @Query("bars") bars: Int? = null
    ): TransitionSuggestionsDTO

    /** Quita el documento: la transición vuelve al editor antiguo con sus campos derivados. */
    @POST("api/playmix/{id}/transitions/{tId}/studio/detach")
    suspend fun detach(@Path("id") playmixId: String, @Path("tId") transitionId: String): PlaymixTransitionDTO
}
