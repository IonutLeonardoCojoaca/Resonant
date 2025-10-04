package com.example.resonant

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query

interface ApiResonantService {

    // Login
    @POST("api/Auth/Google")
    suspend fun loginWithGoogle(
        @Body request: GoogleTokenDTO
    ): AuthResponse

    @POST("api/Auth/Refresh")
    fun refreshToken(
        @Body request: RefreshTokenDTO
    ): Call<AuthResponse>

    // Compartir canción (TOKEN TEMPORAL)
    @POST("api/Auth/ShareSongLink")
    suspend fun getShareSongLink(
        @Query("songId") songId: String
    ): String





    // Album
    @GET("api/Album/GetById")
    suspend fun getAlbumById(
        @Query("id") id: String
    ): Album

    @GET("api/Album/GetAllIds")
    suspend fun getAllAlbumIds(): List<String>

    @GET("api/Album/GetByArtistId")
    suspend fun getByArtistId(
        @Query("artistId") artistId: String
    ): List<Album>

    @GET("api/Album/SearchByQuery")
    suspend fun searchAlbumsByQuery(
        @Query("query") query: String
    ): List<Album>

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
    suspend fun getFavoriteAlbumsByUser(
        @Query("userId") userId: String
    ): List<Album>






    // Artist
    @GET("api/Artist/GetById")
    suspend fun getArtistById(
        @Query("id") id: String
    ): Artist

    @GET("api/Artist/GetAllIds")
    suspend fun getAllArtistIds(): List<String>

    @GET("api/Artist/SearchByQuery")
    suspend fun searchArtistsByQuery(
        @Query("query") query: String
    ): List<Artist>

    @GET("api/Artist/GetBySongId")
    suspend fun getArtistsBySongId(
        @Query("id") songId: String
    ): List<Artist>

    @GET("api/Artist/GetByAlbumId")
    suspend fun getArtistsByAlbumId(
        @Query("id") songId: String
    ): List<Artist>

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
    suspend fun getFavoriteArtistsByUser(
        @Query("userId") userId: String
    ): List<Artist>





    // Song
    @GET("api/Song/GetById")
    suspend fun getSongById(
        @Query("id") id: String): Song

    @GET("api/Song/GetAllIds")
    suspend fun getAllSongIds(): List<String>

    @GET("api/Song/SearchByQuery")
    suspend fun searchSongsByQuery(
        @Query("query") query: String): List<Song>

    @GET("api/Song/GetByAlbumId")
    suspend fun getSongsByAlbumId(
        @Query("albumId") albumId: String
    ): List<Song>

    @PUT("api/Song/AddStream")
    suspend fun incrementStream(
        @Query("id") songId: String
    )

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

    @GET("/api/Song/GetByUserId")
    suspend fun getFavoriteSongsByUser(
        @Query("userId") userId: String
    ): List<Song>

    @GET("api/Song/GetByPlaylistId")
    suspend fun getSongsByPlaylistIdWithArtists(
        @Query("playlistId") playlistId: String
    ): List<Song>

    @GET("api/Song/MostStreamedByArtist")
    suspend fun getMostStreamedSongsByArtist(
        @Query("artistId") artistId: String,
        @Query("count") count: Int
    ): List<Song>




    // Minio
    @POST("api/Minio/GetMultipleAlbumUrls")
    suspend fun getMultipleAlbumUrls(
        @Body fileNames: List<String>
    ): List<AlbumUrlDTO>

    @POST("api/Minio/GetMultipleArtistUrls")
    suspend fun getMultipleArtistUrls(
        @Body fileNames: List<String>
    ): List<ArtistUrlDTO>

    @POST("api/Minio/GetMultipleSongUrls")
    suspend fun getMultipleSongUrls(
        @Body fileNames: List<String>
    ): List<SongUrlDTO>

    @POST("api/Minio/GetSongCoverUrl")
    suspend fun getSongCoverUrl(
        @Query("imageFileName") imageFileName: String,
        @Query("albumId") albumId: String
    ): SongCoverDTO

    @POST("api/Minio/GetMultipleSongCoverUrls")
    suspend fun getMultipleSongCoverUrls(
        @Body imageFileNames: List<String>,
        @Query("albumIds") albumIds: List<String>
    ): List<CoverResponse>

    @POST("api/Minio/GetAlbumUrl")
    suspend fun getAlbumUrl(
        @Query("fileName") fileName: String
    ): AlbumUrlDTO

    @POST("api/Minio/GetArtistUrl")
    suspend fun getArtistUrl(
        @Query("fileName") fileName: String
    ): ArtistUrlDTO






    // Playlist
    @POST("api/Playlist/Create")
    suspend fun createPlaylist(
        @Body playlist: Playlist
    ): Playlist // o puedes usar Response<Playlist> si quieres capturar status/error

    // Obtener playlist por Id
    @GET("api/Playlist/GetById")
    suspend fun getPlaylistById(
        @Query("id") id: String
    ): Playlist

    @GET("api/Playlist/GetByUserId")
    suspend fun getPlaylistByUserId(
        @Query("userId") userId: String
    ): List<Playlist>

    // Eliminar playlist por Id
    @DELETE("api/Playlist/Delete")
    suspend fun deletePlaylist(
        @Query("id") id: String
    ): Response<Unit>

    @PUT("api/Playlist/AddToPlaylist")
    suspend fun addSongToPlaylist(
        @Query("songId") songId: String,
        @Query("playlistId") playlistId: String
    ): Response<Void>

    // Comprobar si la canción está en la playlist
    @GET("api/Playlist/IsSongInPlaylist")
    suspend fun isSongInPlaylist(
        @Query("songId") songId: String,
        @Query("playlistId") playlistId: String
    ): Boolean

    // Eliminar canción de una playlist
    @DELETE("api/Playlist/DeleteFromPlaylist")
    suspend fun deleteSongFromPlaylist(
        @Query("songId") songId: String,
        @Query("playlistId") playlistId: String
    )



    // App Updates

    @GET("api/App/LatestVersion")
    suspend fun getLatestAppVersion(
        @Query("platform") platform: String
    ): AppUpdate

    @GET("api/App/Download")
    suspend fun getPresignedDownloadUrl(
        @Query("version") version: String,
        @Query("platform") platform: String
    ): Response<ResponseBody>

    // User
    @GET("/api/User/GetByEmail")
    suspend fun getUserByEmail(
        @Query("email") email: String
    ): User

    @GET("/api/User/GetById")
    suspend fun getUserById(
        @Query("id") id: String
    ): User

}