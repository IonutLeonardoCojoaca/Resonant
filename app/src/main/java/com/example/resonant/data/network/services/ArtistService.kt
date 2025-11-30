package com.example.resonant.data.network.services

import com.example.resonant.data.models.Artist
import com.example.resonant.data.network.RecommendationResponse
import com.example.resonant.data.network.SearchResponse
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface ArtistService {
    @GET("api/Artist/GetById")
    suspend fun getArtistById(@Query("id") id: String): Artist

    @GET("api/Artist/GetAllIds")
    suspend fun getAllArtistIds(): List<String>

    @GET("api/Artist/SearchByQuery")
    suspend fun searchArtistsByQuery(@Query("query") query: String): SearchResponse<Artist>

    @GET("api/Artist/GetBySongId")
    suspend fun getArtistsBySongId(@Query("id") songId: String): List<Artist>

    @GET("api/Artist/GetByAlbumId")
    suspend fun getArtistsByAlbumId(@Query("id") songId: String): List<Artist>

    @POST("/api/Artist/AddFavorite")
    suspend fun addFavoriteArtist(
        @Query("userId") userId: String,
        @Query("artistId") artistId: String
    )

    @DELETE("/api/Artist/DeleteFavorite")
    suspend fun deleteFavoriteArtist(
        @Query("userId") userId: String,
        @Query("artistId") artistId: String
    )

    @GET("/api/Artist/GetByUserId")
    suspend fun getFavoriteArtistsByUser(@Query("userId") userId: String): List<Artist>

    @GET("api/Artist/RecommendedArtist") // Asumo que la ruta es esta
    suspend fun getRecommendedArtists(
        @Query("userId") userId: String,
        @Query("count") count: Int
    ): List<RecommendationResponse<Artist>> // <--- Devuelve una lista de Artist envueltos
}