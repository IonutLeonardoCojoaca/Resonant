package com.example.resonant.data.network

import android.content.Context

import com.example.resonant.data.network.services.AlbumService
import com.example.resonant.data.network.services.AppService
import com.example.resonant.data.network.services.AriaPicksService
import com.example.resonant.data.network.services.AriaService
import com.example.resonant.data.network.services.ArtistService
import com.example.resonant.data.network.services.AuthService
import com.example.resonant.data.network.services.ExploreService
import com.example.resonant.data.network.services.GenreService
import com.example.resonant.data.network.services.HomeService
import com.example.resonant.data.network.services.LiveService
import com.example.resonant.data.network.services.LyricsService
import com.example.resonant.data.network.services.NotificationService
import com.example.resonant.data.network.services.PlaybackAnalyticsService
import com.example.resonant.data.network.services.PlaybackConnectService
import com.example.resonant.data.network.services.PlatformService
import com.example.resonant.data.network.services.PlaylistService
import com.example.resonant.data.network.services.PlaymixService
import com.example.resonant.data.network.services.RadioService
import com.example.resonant.data.network.services.SongService
import com.example.resonant.data.network.services.StreakService
import com.example.resonant.data.network.services.TransitionPresetService
import com.example.resonant.data.network.services.StudioService
import com.example.resonant.data.network.services.UnisoundService
import com.example.resonant.data.network.services.UniversalSearchService

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

    /** Proxies de Retrofit por interfaz: `create()` construye uno nuevo (reflexión) cada vez que se llama. */
    private val services = java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> service(context: Context, type: Class<T>): T =
        services.getOrPut(type) { getRetrofitInstance(context).create(type) } as T

    /**
     * Client for presigned media and artwork URLs. It shares OkHttp resources
     * with Retrofit but never attaches the API Authorization header.
     */
    fun getMediaHttpClient() = NetworkClientProvider.mediaClient()


    fun getAuthService(context: Context): AuthService {
        return service(context, AuthService::class.java)
    }

    fun getAlbumService(context: Context): AlbumService {
        return service(context, AlbumService::class.java)
    }

    fun getArtistService(context: Context): ArtistService {
        return service(context, ArtistService::class.java)
    }

    fun getSongService(context: Context): SongService {
        return service(context, SongService::class.java)
    }

    fun getPlaylistService(context: Context): PlaylistService {
        return service(context, PlaylistService::class.java)
    }

    fun getUserService(context: Context): UserService {
        return service(context, UserService::class.java)
    }

    fun getAppService(context: Context): AppService {
        return service(context, AppService::class.java)
    }

    fun getGenreService(context: Context): GenreService {
        return service(context, GenreService::class.java)
    }

    fun getHomeService(context: Context): HomeService {
        return service(context, HomeService::class.java)
    }

    fun getPlaybackAnalyticsService(context: Context): PlaybackAnalyticsService {
        return service(context, PlaybackAnalyticsService::class.java)
    }

    fun getPlaybackConnectService(context: Context): PlaybackConnectService {
        return service(context, PlaybackConnectService::class.java)
    }

    fun getAriaService(context: Context): AriaService {
        return service(context, AriaService::class.java)
    }

    fun getLyricsService(context: Context): LyricsService {
        return service(context, LyricsService::class.java)
    }

    fun getPlaymixService(context: Context): PlaymixService {
        return service(context, PlaymixService::class.java)
    }

    fun getRadioService(context: Context): RadioService {
        return service(context, RadioService::class.java)
    }

    fun getTransitionPresetService(context: Context): TransitionPresetService {
        return service(context, TransitionPresetService::class.java)
    }

    fun getStudioService(context: Context): StudioService {
        return service(context, StudioService::class.java)
    }

    fun getUniversalSearchService(context: Context): UniversalSearchService {
        return service(context, UniversalSearchService::class.java)
    }

    fun getPlatformService(context: Context): PlatformService {
        return service(context, PlatformService::class.java)
    }

    fun getUnisoundService(context: Context): UnisoundService {
        return service(context, UnisoundService::class.java)
    }

    fun getExploreService(context: Context): ExploreService {
        return service(context, ExploreService::class.java)
    }

    fun getAriaPicksService(context: Context): AriaPicksService {
        return service(context, AriaPicksService::class.java)
    }

    fun getLiveService(context: Context): LiveService {
        return service(context, LiveService::class.java)
    }

    fun getStreakService(context: Context): StreakService {
        return service(context, StreakService::class.java)
    }

    fun getAutoDownloadService(context: Context): com.example.resonant.data.network.services.AutoDownloadService {
        return service(context, com.example.resonant.data.network.services.AutoDownloadService::class.java)
    }

    fun getNotificationService(context: Context): NotificationService {
        return service(context, NotificationService::class.java)
    }

    fun getListeningStatsService(context: Context): com.example.resonant.data.network.services.ListeningStatsService {
        return service(context, com.example.resonant.data.network.services.ListeningStatsService::class.java)
    }

    fun getRecapService(context: Context): com.example.resonant.data.network.services.RecapService {
        return service(context, com.example.resonant.data.network.services.RecapService::class.java)
    }

    fun baseUrl(): String = BASE_URL
}
