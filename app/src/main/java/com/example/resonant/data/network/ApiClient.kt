package com.example.resonant.data.network

import android.content.Context

import com.example.resonant.data.network.services.AlbumService
import com.example.resonant.data.network.services.AppService
import com.example.resonant.data.network.services.ArtistService
import com.example.resonant.data.network.services.AuthService
import com.example.resonant.data.network.services.GenreService
import com.example.resonant.data.network.services.PlaylistService
import com.example.resonant.data.network.services.SongService

import com.example.resonant.data.network.services.UserService
import com.example.resonant.managers.SessionManager
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ApiClient {
    private const val BASE_URL = "https://resonantapp.ddns.net/"

    private var retrofit: Retrofit? = null

    private fun getRetrofitInstance(context: Context): Retrofit {
        if (retrofit == null) {
            val appContext = context.applicationContext
            val session = SessionManager(appContext, BASE_URL)

            val okHttpClient = OkHttpClient.Builder()
                .addInterceptor(AuthInterceptor(appContext, session))
                .authenticator(TokenAuthenticator(appContext))
                .build()

            retrofit = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
        return retrofit!!
    }


    fun getAuthService(context: Context): AuthService {
        return getRetrofitInstance(context).create(AuthService::class.java)
    }

    fun getAlbumService(context: Context): AlbumService {
        return getRetrofitInstance(context).create(AlbumService::class.java)
    }

    fun getArtistService(context: Context): ArtistService {
        return getRetrofitInstance(context).create(ArtistService::class.java)
    }

    fun getSongService(context: Context): SongService {
        return getRetrofitInstance(context).create(SongService::class.java)
    }

    fun getPlaylistService(context: Context): PlaylistService {
        return getRetrofitInstance(context).create(PlaylistService::class.java)
    }

    fun getUserService(context: Context): UserService {
        return getRetrofitInstance(context).create(UserService::class.java)
    }

    fun getAppService(context: Context): AppService {
        return getRetrofitInstance(context).create(AppService::class.java)
    }

    fun getGenreService(context: Context): GenreService {
        return getRetrofitInstance(context).create(GenreService::class.java)
    }



    fun baseUrl(): String = BASE_URL
}