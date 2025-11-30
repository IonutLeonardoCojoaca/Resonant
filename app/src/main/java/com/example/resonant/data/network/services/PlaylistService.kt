package com.example.resonant.data.network.services

import com.example.resonant.data.models.Playlist
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query

interface PlaylistService {
    @POST("api/Playlist/Create")
    suspend fun createPlaylist(@Body playlist: Playlist): Playlist

    @GET("api/Playlist/GetById")
    suspend fun getPlaylistById(@Query("id") id: String): Playlist

    @GET("api/Playlist/GetByUserId")
    suspend fun getPlaylistByUserId(@Query("userId") userId: String): List<Playlist>

    @DELETE("api/Playlist/Delete")
    suspend fun deletePlaylist(@Query("id") id: String): Response<Unit>

    @PUT("api/Playlist/AddToPlaylist")
    suspend fun addSongToPlaylist(
        @Query("songId") songId: String,
        @Query("playlistId") playlistId: String
    ): Response<Void>

    @GET("api/Playlist/IsSongInPlaylist")
    suspend fun isSongInPlaylist(
        @Query("songId") songId: String,
        @Query("playlistId") playlistId: String
    ): Boolean

    @DELETE("api/Playlist/DeleteFromPlaylist")
    suspend fun deleteSongFromPlaylist(
        @Query("songId") songId: String,
        @Query("playlistId") playlistId: String
    )
}