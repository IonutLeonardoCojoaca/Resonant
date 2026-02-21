package com.example.resonant.data.network.services

import com.example.resonant.data.models.Playlist
import com.example.resonant.data.models.Song
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface PlaylistService {
    @POST("api/playlists")
    suspend fun createPlaylist(@Body playlist: Playlist): Playlist

    @GET("api/playlists/{id}")
    suspend fun getPlaylistById(@Path("id") id: String): Playlist

    @PUT("api/playlists/{id}")
    suspend fun updatePlaylist(@Path("id") id: String, @Body playlist: Playlist): Response<Unit>

    @GET("api/playlists/mine")
    suspend fun getMyPlaylists(): List<Playlist>

    @GET("api/playlists/public")
    suspend fun getAllPublicPlaylists(): List<Playlist>

    @DELETE("api/playlists/{id}")
    suspend fun deletePlaylist(@Path("id") id: String): Response<Unit>

    @GET("api/playlists/{id}/songs")
    suspend fun getPlaylistSongs(@Path("id") playlistId: String): List<Song>

    @POST("api/playlists/{id}/songs")
    suspend fun addSongToPlaylist(
        @Path("id") playlistId: String,
        @Body songId: String
    ): Response<Void>

    @GET("api/playlists/{id}/songs/{songId}/exists")
    suspend fun isSongInPlaylist(
        @Path("id") playlistId: String,
        @Path("songId") songId: String
    ): Boolean

    @DELETE("api/playlists/{id}/songs/{songId}")
    suspend fun deleteSongFromPlaylist(
        @Path("id") playlistId: String,
        @Path("songId") songId: String
    )

    @PATCH("api/playlists/{id}/name")
    suspend fun updatePlaylistName(
        @Path("id") id: String,
        @Query("name") name: String
    ): Response<Unit>

    @PATCH("api/playlists/{id}/visibility")
    suspend fun updatePlaylistVisibility(
        @Path("id") id: String,
        @Query("isPublic") isPublic: Boolean
    ): Response<Unit>
}