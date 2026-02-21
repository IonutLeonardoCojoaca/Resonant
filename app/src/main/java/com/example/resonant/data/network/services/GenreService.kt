package com.example.resonant.data.network.services

import com.example.resonant.data.models.Album
import com.example.resonant.data.network.RecommendationResponse
import com.example.resonant.data.network.SearchResponse
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface AlbumService {
    @GET("api/Album/GetById")
    suspend fun getAlbumById(@Query("id") id: String): Album

    @GET("api/Album/GetAllIds")
    suspend fun getAllAlbumIds(): List<String>

    @GET("api/Album/GetByArtistId")
    suspend fun getByArtistId(@Query("artistId") artistId: String): List<Album>

    @GET("api/Album/SearchByQuery")
    suspend fun searchAlbumsByQuery(@Query("query") query: String): SearchResponse<Album>

    @POST("/api/Album/AddFavorite")
    suspend fun addFavoriteAlbum(
        @Query("userId") userId: String,
        @Query("albumId") albumId: String
    )

    @DELETE("/api/Album/DeleteFavorite")
    suspend fun deleteFavoriteAlbum(
        @Query("userId") userId: String,
        @Query("albumId") albumId: String
    )

    @GET("/api/Album/GetByUserId")
    suspend fun getFavoriteAlbumsByUser(@Query("userId") userId: String): List<Album>

    @GET("api/Album/RecommendedAlbums")
    suspend fun getRecommendedAlbums(
        @Query("userId") userId: String,
        @Query("count") count: Int
    ): List<RecommendationResponse<Album>>
}