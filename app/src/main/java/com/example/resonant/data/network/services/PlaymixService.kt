package com.example.resonant.data.network.services

import com.example.resonant.data.network.AddSongToPlaymixRequest
import com.example.resonant.data.network.CreatePlaymixRequest
import com.example.resonant.data.network.EditPlaymixSongRequest
import com.example.resonant.data.network.PlaymixDTO
import com.example.resonant.data.network.PlaymixDetailDTO
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.data.network.PlaymixTransitionUpdateDTO
import com.example.resonant.data.network.ReorderSongsRequest
import com.example.resonant.data.network.WaveformResponseDTO
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface PlaymixService {

    @POST("api/playmix")
    suspend fun createPlaymix(@Body request: CreatePlaymixRequest): PlaymixDTO

    @GET("api/playmix")
    suspend fun getMyPlaymixes(): List<PlaymixDTO>

    @GET("api/playmix/{id}")
    suspend fun getPlaymixDetail(@Path("id") id: String): PlaymixDetailDTO

    @DELETE("api/playmix/{id}")
    suspend fun deletePlaymix(@Path("id") id: String): Response<Unit>

    @POST("api/playmix/{id}/songs")
    suspend fun addSongToPlaymix(
        @Path("id") playmixId: String,
        @Body request: AddSongToPlaymixRequest
    ): Response<Unit>

    @DELETE("api/playmix/{id}/songs/{psId}")
    suspend fun removeSongFromPlaymix(
        @Path("id") playmixId: String,
        @Path("psId") playmixSongId: String
    ): Response<Unit>

    @PATCH("api/playmix/{id}/songs/{psId}")
    suspend fun editPlaymixSong(
        @Path("id") playmixId: String,
        @Path("psId") playmixSongId: String,
        @Body request: EditPlaymixSongRequest
    ): Response<Unit>

    @PATCH("api/playmix/{id}/songs/reorder")
    suspend fun reorderSongs(
        @Path("id") playmixId: String,
        @Body request: ReorderSongsRequest
    ): Response<Unit>

    @PUT("api/playmix/{id}/transitions/{tId}")
    suspend fun updateTransition(
        @Path("id") playmixId: String,
        @Path("tId") transitionId: String,
        @Body request: PlaymixTransitionUpdateDTO
    ): PlaymixTransitionDTO

    @GET("api/playmix/{id}/transitions/{tId}/waveforms")
    suspend fun getWaveformData(
        @Path("id") playmixId: String,
        @Path("tId") transitionId: String
    ): WaveformResponseDTO
}
