package com.example.resonant.data.network

import android.content.Context

import com.example.resonant.data.network.services.AlbumService
import com.example.resonant.data.network.services.AppService
import com.example.resonant.data.network.services.AriaService
import com.example.resonant.data.network.services.ArtistService
import com.example.resonant.data.network.services.AuthService
import com.example.resonant.data.network.services.GenreService
import com.example.resonant.data.network.services.HomeService
import com.example.resonant.data.network.services.LyricsService
import com.example.resonant.data.network.services.PlaybackAnalyticsService
import com.example.resonant.data.network.services.PlaybackConnectService
import com.example.resonant.data.network.services.PlaylistService
import com.example.resonant.data.network.services.PlaymixService
import com.example.resonant.data.network.services.RadioService
import com.example.resonant.data.network.services.SongService
import com.example.resonant.data.network.services.TransitionPresetService

import com.example.resonant.data.network.services.UserService
import com.example.resonant.managers.SessionManager
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ApiClient {
    private const val BASE_URL = "https://resonantapp.ddns.net/"

    @Volatile
    private var retrofit: Retrofit? = null

    fun getRetrofitInstance(context: Context): Retrofit {
        return retrofit ?: synchronized(this) {
            retrofit ?: run {
            val appContext = context.applicationContext
            val session = SessionManager.getInstance(appContext)

                Retrofit.Builder()
                .baseUrl(BASE_URL)
                    .client(NetworkClientProvider.apiClient(appContext, session))
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                    .also { retrofit = it }
            }
        }
    }

    /**
     * Client for presigned media and artwork URLs. It shares OkHttp resources
     * with Retrofit but never attaches the API Authorization header.
     */
    fun getMediaHttpClient() = NetworkClientProvider.mediaClient()


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

    fun getHomeService(context: Context): HomeService {
        return getRetrofitInstance(context).create(HomeService::class.java)
    }

    fun getPlaybackAnalyticsService(context: Context): PlaybackAnalyticsService {
        return getRetrofitInstance(context).create(PlaybackAnalyticsService::class.java)
    }

    fun getPlaybackConnectService(context: Context): PlaybackConnectService {
        return getRetrofitInstance(context).create(PlaybackConnectService::class.java)
    }

    fun getAriaService(context: Context): AriaService {
        return getRetrofitInstance(context).create(AriaService::class.java)
    }

    fun getLyricsService(context: Context): LyricsService {
        return getRetrofitInstance(context).create(LyricsService::class.java)
    }

    fun getPlaymixService(context: Context): PlaymixService {
        return getRetrofitInstance(context).create(PlaymixService::class.java)
    }

    fun getRadioService(context: Context): RadioService {
        return getRetrofitInstance(context).create(RadioService::class.java)
    }

    fun getTransitionPresetService(context: Context): TransitionPresetService {
        return getRetrofitInstance(context).create(TransitionPresetService::class.java)
    }

    fun baseUrl(): String = BASE_URL
}
