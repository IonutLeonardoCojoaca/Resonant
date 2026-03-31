package com.example.resonant.data.network.services

import com.example.resonant.data.models.Album
import com.example.resonant.data.models.AlbumStatsDTO
import com.example.resonant.data.models.Artist
import com.example.resonant.data.network.RecommendationResponse
import com.example.resonant.data.network.SearchResponse
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface AlbumService {
    @GET("api/albums/{id}")
    suspend fun getAlbumById(@Path("id") id: String): Album

    @GET("api/albums")
    suspend fun getByArtistId(@Query("artistId") artistId: String): List<Album>

    @GET("api/albums/search")
    suspend fun searchAlbumsByQuery(@Query("q") query: String): SearchResponse<Album>

    @POST("api/albums/{id}/favorites")
    suspend fun addFavoriteAlbum(@Path("id") albumId: String)

    @DELETE("api/albums/{id}/favorites")
    suspend fun deleteFavoriteAlbum(@Path("id") albumId: String)

    @GET("api/albums/favorites")
    suspend fun getFavoriteAlbumsByUser(): List<Album>

    @GET("api/albums/{id}/favorites/check")
    suspend fun isFavoriteAlbum(@Path("id") albumId: String): Boolean

    @GET("api/albums/recommendations")
    suspend fun getRecommendedAlbums(
        @Query("count") count: Int
    ): List<RecommendationResponse<Album>>

    @GET("api/albums/charts/top")
    suspend fun getTopAlbums(
        @Query("period") period: String,
        @Query("limit") limit: Int = 50
    ): List<Album>

    @GET("api/albums/{id}/songs")
    suspend fun getAlbumSongs(@Path("id") albumId: String): List<com.example.resonant.data.models.Song>

    @GET("api/albums/charts/new-releases")
    suspend fun getNewReleaseAlbums(
        @Query("limit") limit: Int = 20
    ): List<Album>

    @GET("api/albums/by-year")
    suspend fun getAlbumsByYear(
        @Query("yearFrom") yearFrom: Int? = null,
        @Query("yearTo") yearTo: Int? = null,
        @Query("limit") limit: Int = 50
    ): List<Album>

    @GET("api/albums/most-listened")
    suspend fun getMostListenedAlbums(
        @Query("limit") limit: Int = 20
    ): List<Album>

    @GET("api/albums/{id}/stats")
    suspend fun getAlbumStats(@Path("id") albumId: String): AlbumStatsDTO

    @GET("api/albums/{id}/artists")
    suspend fun getAlbumArtists(@Path("id") albumId: String): List<Artist>
}