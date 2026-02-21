package com.example.resonant.data.network.services

import com.example.resonant.data.models.Artist
import com.example.resonant.data.network.RecommendationResponse
import com.example.resonant.data.network.SearchResponse
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ArtistService {
    @GET("api/artists/{id}")
    suspend fun getArtistById(@Path("id") id: String): Artist

    @GET("api/artists/search")
    suspend fun searchArtistsByQuery(@Query("q") query: String): SearchResponse<Artist>

    @GET("api/artists")
    suspend fun getArtistsByGenreId(@Query("genreId") genreId: String): List<Artist>

    @POST("api/artists/{id}/favorites")
    suspend fun addFavoriteArtist(@Path("id") artistId: String)

    @DELETE("api/artists/{id}/favorites")
    suspend fun deleteFavoriteArtist(@Path("id") artistId: String)

    @GET("api/artists/favorites")
    suspend fun getFavoriteArtistsByUser(): List<Artist>

    @GET("api/artists/{id}/favorites/check")
    suspend fun isFavoriteArtist(@Path("id") artistId: String): Boolean

    @GET("api/artists/recommendations")
    suspend fun getRecommendedArtists(
        @Query("count") count: Int
    ): List<RecommendationResponse<Artist>>

    @GET("api/artists/{id}/songs")
    suspend fun getArtistSongs(@Path("id") artistId: String): List<com.example.resonant.data.models.Song>

    @GET("api/artists/{id}/top-songs")
    suspend fun getTopSongsByArtist(
        @Path("id") artistId: String,
        @Query("limit") limit: Int = 10
    ): List<com.example.resonant.data.models.Song>

    @GET("api/artists/{id}/singles")
    suspend fun getArtistSingles(@Path("id") artistId: String): List<com.example.resonant.data.models.Song>

    @GET("api/artists/{id}/albums")
    suspend fun getArtistAlbums(@Path("id") artistId: String): List<com.example.resonant.data.models.Album>

    @GET("api/artists/{id}/related")
    suspend fun getRelatedArtists(@Path("id") artistId: String): List<Artist>

    @GET("api/artists/charts/essentials/songs")
    suspend fun getEssentials(@Query("artistId") artistId: String): List<com.example.resonant.data.models.Song>

    @GET("api/artists/charts/radio/songs")
    suspend fun getRadios(@Query("artistId") artistId: String): List<com.example.resonant.data.models.Song>

    @GET("api/artists/{id}/playlists")
    suspend fun getArtistSmartPlaylists(@Path("id") artistId: String): List<com.example.resonant.data.models.ArtistSmartPlaylist>

    @GET("api/artists/charts/top")
    suspend fun getTopArtists(
        @Query("period") period: String,
        @Query("limit") limit: Int = 50
    ): List<Artist>

    @GET("api/artists/{id}/images")
    suspend fun getArtistImages(@Path("id") artistId: String): com.example.resonant.data.models.ArtistImagesResponse
}