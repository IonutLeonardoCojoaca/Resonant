package com.example.resonant.data.network.services

import com.example.resonant.data.models.Playlist
import com.example.resonant.data.network.AlbumUrlDTO
import com.example.resonant.data.network.ArtistUrlDTO
import com.example.resonant.data.network.CoverRequestDTO
import com.example.resonant.data.network.CoverResponse
import com.example.resonant.data.network.SongCoverDTO
import com.example.resonant.data.network.SongUrlDTO
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

interface StorageService {
    @POST("api/Minio/GetMultipleAlbumUrls")
    suspend fun getMultipleAlbumUrls(@Body fileNames: List<String>): List<AlbumUrlDTO>

    @POST("api/Minio/GetMultipleArtistUrls")
    suspend fun getMultipleArtistUrls(@Body fileNames: List<String>): List<ArtistUrlDTO>

    @POST("api/Minio/GetMultipleSongUrls")
    suspend fun getMultipleSongUrls(@Body fileNames: List<String>): List<SongUrlDTO>

    @POST("api/Minio/GetSongCoverUrl")
    suspend fun getSongCoverUrl(
        @Query("imageFileName") imageFileName: String,
        @Query("albumId") albumId: String
    ): SongCoverDTO

    @POST("api/Minio/GetMultipleSongCoverUrls")
    suspend fun getMultipleSongCoverUrls(@Body requests: List<CoverRequestDTO>): List<CoverResponse>

    @POST("api/Minio/GetAlbumUrl")
    suspend fun getAlbumUrl(@Query("fileName") fileName: String): AlbumUrlDTO

    @POST("api/Minio/GetArtistUrl")
    suspend fun getArtistUrl(@Query("fileName") fileName: String): ArtistUrlDTO

    @Multipart
    @POST("api/Minio/UploadPlaylist")
    suspend fun uploadPlaylistCover(
        @Part file: MultipartBody.Part
    ): Response<Playlist>
}