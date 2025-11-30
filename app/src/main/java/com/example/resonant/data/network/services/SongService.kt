package com.example.resonant.data.network.services

import com.example.resonant.data.models.Song
import com.example.resonant.data.network.AddStreamDTO
import com.example.resonant.data.network.RecommendationResponse
import com.example.resonant.data.network.SearchResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query

interface SongService {
    @GET("api/Song/GetAllIds")
    suspend fun getAllSongIds(): List<String>

    @PUT("api/Song/AddStream")
    suspend fun addStream(@Body streamData: AddStreamDTO)

    @GET("api/Song/MostStreamedByArtist")
    suspend fun getMostStreamedSongsByArtist(
        @Query("artistId") artistId: String,
        @Query("count") count: Int
    ): List<Song>

    @GET("api/Song/GetByIdWithMetadata")
    suspend fun getSongByIdWithMetadata(@Query("id") songId: String): Song

    @GET("api/Song/GetByPlaylistIdWithMetadata")
    suspend fun getSongsByPlaylistIdWithMetadata(@Query("playlistId") playlistId: String): List<Song>

    @GET("api/Song/GetByUserIdWithMetadata")
    suspend fun getSongsByUserIdWithMetadata(@Query("userId") userId: String): List<Song>

    @GET("api/Song/GetByAlbumIdWithMetadata")
    suspend fun getSongsByAlbumIdWithMetadata(@Query("albumId") albumId: String): List<Song>

    @GET("api/Song/GetByArtistIdWithMetadata")
    suspend fun getSongsByArtistIdWithMetadata(@Query("artistId") artistId: String): List<Song>

    @GET("api/Song/SearchWithMetadata")
    suspend fun searchSongsWithMetadata(@Query("query") query: String): SearchResponse<Song>

    @POST("/api/Song/AddFavorite")
    suspend fun addFavoriteSong(
        @Query("userId") userId: String,
        @Query("songId") songId: String
    )

    @DELETE("/api/Song/DeleteFavorite")
    suspend fun deleteFavoriteSong(
        @Query("userId") userId: String,
        @Query("songId") songId: String
    )

    @GET("api/Song/RecommendedSongs")
    suspend fun getRecommendedSongs(
        @Query("userId") userId: String,
        @Query("count") count: Int
    ): List<RecommendationResponse<Song>>

    @GET("api/Song/GetHistoryRecent")
    suspend fun getHistorySongByIdWithMetadata(
        @Query("userId") userId: String,
        @Query("limit") count: Int
    ): List<Song> // <--- CAMBIO AQUÍ

}