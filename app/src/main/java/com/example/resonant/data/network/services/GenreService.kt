package com.example.resonant.data.network.services

import com.example.resonant.data.models.Genre
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface GenreService {

    @GET("api/genres")
    suspend fun getAllGenres(): List<Genre>

    @GET("api/genres/{id}")
    suspend fun getGenreById(@Path("id") id: String): Genre

    @GET("api/genres/by-artist/{artistId}")
    suspend fun getGenresByArtistId(
        @Path("artistId") artistId: String
    ): List<Genre>

    @GET("api/genres/popular")
    suspend fun getPopularGenres(
        @Query("count") count: Int = 10
    ): List<Genre>

    @GET("api/genres/{id}/related")
    suspend fun getRelatedGenres(
        @Path("id") genreId: String,
        @Query("count") count: Int = 5
    ): List<Genre>

    @GET("api/genres/favorites")
    suspend fun getFavoriteGenres(
        @Query("userId") userId: String
    ): List<Genre>
}