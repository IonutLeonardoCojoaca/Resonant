package com.example.resonant

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query

interface ApiResonantService {

    // Login
    @POST("api/Auth/Google")
    suspend fun loginWithGoogle(@Body request: GoogleTokenDTO): AuthResponse

    @POST("api/Auth/Refresh")
    fun refreshToken(@Body request: RefreshTokenDTO): Call<AuthResponse>



    // Album
    @GET("api/Album/GetById")
    suspend fun getAlbumById(@Query("id") id: String): Album

    @GET("api/Album/GetAllIds")
    suspend fun getAllAlbumIds(): List<String>

    @GET("api/Album/GetByArtistId")
    suspend fun getByArtistId(@Query("artistId") artistId: String): List<Album>

    @GET("api/Album/SearchByQuery")
    suspend fun searchAlbumsByQuery(@Query("query") query: String): List<Album>



    // Artist
    @GET("api/Artist/GetById")
    suspend fun getArtistById(@Query("id") id: String): Artist

    @GET("api/Artist/GetAllIds")
    suspend fun getAllArtistIds(): List<String>

    @GET("api/Artist/SearchByQuery")
    suspend fun searchArtistsByQuery(@Query("query") query: String): List<Artist>

    @GET("api/Artist/GetBySongId")
    suspend fun getArtistsBySongId(@Query("id") songId: String): List<Artist>

    @GET("api/Artist/GetByAlbumId")
    suspend fun getArtistsByAlbumId(@Query("id") songId: String): List<Artist>





    // Song
    @GET("api/Song/GetById")
    suspend fun getSongById(@Query("id") id: String): Song

    @GET("api/Song/GetAllIds")
    suspend fun getAllSongIds(): List<String>

    @GET("api/Song/SearchByQuery")
    suspend fun searchSongsByQuery(@Query("query") query: String): List<Song>

    @GET("api/Song/GetByAlbumId")
    suspend fun getSongsByAlbumId(@Query("albumId") albumId: String): List<Song>

    @PUT("api/Song/AddStream")
    suspend fun incrementStream(@Query("id") songId: String)





    // Minio
    @POST("api/Minio/GetMultipleAlbumUrls")
    suspend fun getMultipleAlbumUrls(@Body fileNames: List<String>): List<AlbumUrlDTO>

    @POST("api/Minio/GetMultipleArtistUrls")
    suspend fun getMultipleArtistUrls(@Body fileNames: List<String>): List<ArtistUrlDTO>

    @POST("api/Minio/GetMultipleSongUrls")
    suspend fun getMultipleSongUrls(@Body fileNames: List<String>): List<SongUrlDTO>

    @POST("api/Minio/GetAlbumUrl")
    suspend fun getAlbumUrl(@Query("fileName") fileName: String): AlbumUrlDTO

    @POST("api/Minio/GetArtistUrl")
    suspend fun getArtistUrl(@Query("fileName") fileName: String): ArtistUrlDTO

}