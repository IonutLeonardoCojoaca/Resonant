package com.example.resonant.data.network.services

import com.example.resonant.data.models.Song
import com.example.resonant.data.network.AddStreamDTO
import com.example.resonant.data.network.RecommendationResponse
import com.example.resonant.data.network.SearchResponse
import com.example.resonant.data.network.SongAudioAnalysisDTO
import com.example.resonant.data.network.SongMetadataDTO
import com.example.resonant.data.network.SongPlaybackDTO
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface SongService {

    @POST("api/analytics/stream")
    suspend fun addStream(@Body streamData: AddStreamDTO)

    @GET("api/songs/{id}")
    suspend fun getSongById(@Path("id") songId: String): Song

    @GET("api/songs/{id}/playback")
    suspend fun getSongPlaybackInfo(@Path("id") songId: String): SongPlaybackDTO

    @GET("api/songs/search")
    suspend fun searchSongs(@Query("q") query: String, @Query("limit") limit: Int = 30): SearchResponse<Song>

    @GET("api/songs/favorites")
    suspend fun getFavoriteSongs(): List<Song>

    @GET("api/songs/{id}/favorites/check")
    suspend fun isFavoriteSong(@Path("id") songId: String): Boolean

    @POST("api/songs/{id}/favorites")
    suspend fun addFavoriteSong(@Path("id") songId: String)

    @DELETE("api/songs/{id}/favorites")
    suspend fun deleteFavoriteSong(@Path("id") songId: String)

    @GET("api/songs/recommendations")
    suspend fun getRecommendedSongs(
        @Query("count") count: Int
    ): List<RecommendationResponse<Song>>

    @GET("api/songs/charts/top")
    suspend fun getTopSongs(
        @Query("period") period: Int,
        @Query("limit") limit: Int = 20
    ): List<Song>

    @GET("api/songs/charts/trending")
    suspend fun getTrendingSongs(
        @Query("limit") limit: Int = 20
    ): List<Song>

    @GET("api/songs/sonic-match")
    suspend fun getSonicMatch(
        @Query("songId") songId: String
    ): List<Song>

    @GET("api/songs/{id}/metadata")
    suspend fun getSongMetadata(@Path("id") songId: String): SongMetadataDTO

    @GET("api/songs/{id}/analysis")
    suspend fun getSongAnalysis(@Path("id") songId: String): SongAudioAnalysisDTO

    @GET("api/songs/history")
    suspend fun getPlaybackHistory(@Query("limit") limit: Int = 20): List<Song>
}