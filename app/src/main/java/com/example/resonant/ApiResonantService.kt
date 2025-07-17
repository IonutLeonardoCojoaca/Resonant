package com.example.resonant

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface ApiResonantService {

    // Login
    @POST("Resonant/Auth/Google")
    suspend fun loginWithGoogle(@Body request: GoogleTokenDTO): AuthResponse

    @POST("Resonant/Auth/Refresh")
    fun refreshToken(@Body request: RefreshTokenDTO): Call<AuthResponse>



    // Album
    @GET("Resonant/Album/GetById")
    suspend fun getAlbumById(@Query("id") id: String): Album

    @GET("Resonant/Album/GetAllIds")
    suspend fun getAllAlbumIds(): List<String>

    @POST("Resonant/Minio/GetMultipleAlbumUrls")
    suspend fun getMultipleAlbumUrls(@Body fileNames: List<String>): List<AlbumUrlDTO>



    // Artist
    @GET("Resonant/Artist/GetById")
    suspend fun getArtistById(@Query("id") id: String): Artist

    @GET("Resonant/Artist/GetAllIds")
    suspend fun getAllArtistIds(): List<String>

    @POST("Resonant/Minio/GetMultipleArtistUrls")
    suspend fun getMultipleArtistUrls(@Body fileNames: List<String>): List<ArtistUrlDTO>



    // Song
    @GET("Resonant/Song/GetById")
    suspend fun getSongById(@Query("id") id: String): Song

    @GET("Resonant/Song/GetAllIds")
    suspend fun getAllSongIds(): List<String>

    @GET("Resonant/Artist/GetBySongId")
    suspend fun getArtistsBySongId(@Query("id") songId: String): List<Artist>

    @POST("Resonant/Minio/GetMultipleSongUrls")
    suspend fun getMultipleSongUrls(@Body fileNames: List<String>): List<SongUrlDTO>

}