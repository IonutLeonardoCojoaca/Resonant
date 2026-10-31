package com.example.resonant.data.network

import android.content.Context
import com.example.resonant.BuildConfig
import com.example.resonant.managers.SessionManager
import com.example.resonant.playback.engine.TransitionEngineFlag
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Owns the process-wide OkHttp resources.
 *
 * The media client deliberately has no authentication interceptor. Presigned
 * object-storage URLs must never receive the Resonant API bearer token. The API
 * client is derived with [OkHttpClient.newBuilder], so both clients still share
 * the same dispatcher, connection pool and TLS resources.
 */
object NetworkClientProvider {

    private val dispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 8
    }

    private val connectionPool = ConnectionPool(
        8,
        5,
        TimeUnit.MINUTES
    )

    private val mediaClient: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(connectionPool)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Volatile
    private var apiClient: OkHttpClient? = null

    private const val API_HTTP_CACHE_BYTES = 20L * 1024 * 1024

    fun mediaClient(): OkHttpClient = mediaClient

    fun apiClient(context: Context, session: SessionManager): OkHttpClient {
        return apiClient ?: synchronized(this) {
            apiClient ?: mediaClient.newBuilder()
                // Caché HTTP: OkHttp hace GET condicional con los ETag que ya envía el servidor (Home, Explore,
                // Playlist v2) y servirá sin red lo que llegue con Cache-Control. Solo la API: audio e imágenes
                // tienen sus propias cachés.
                .cache(Cache(java.io.File(context.applicationContext.cacheDir, "http"), API_HTTP_CACHE_BYTES))
                .apply { if (BuildConfig.DEBUG) addInterceptor(RequestCountInterceptor()) }
                .addInterceptor(ClientCompatibilityInterceptor { TransitionEngineFlag.isEnabled(context.applicationContext) })
                .addInterceptor(AuthInterceptor(context.applicationContext, session))
                .authenticator(TokenAuthenticator(context.applicationContext))
                .callTimeout(45, TimeUnit.SECONDS)
                .build()
                .also { apiClient = it }
        }
    }
}
