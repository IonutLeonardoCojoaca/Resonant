package com.example.resonant.playback

import android.content.Context
import android.net.http.HttpEngine
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresExtension
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpEngineDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.example.resonant.data.network.ApiClient
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Uses the platform HttpEngine (HTTP/2 and HTTP/3/QUIC when available) on
 * Android 14+, with the shared OkHttp transport as a fallback on Android 13.
 */
@OptIn(UnstableApi::class)
object MediaHttpDataSourceProvider {
    private val httpEngineExecutor: ExecutorService by lazy {
        Executors.newFixedThreadPool(4)
    }

    @Volatile
    private var platformEngine: HttpEngine? = null

    fun create(context: Context, userAgent: String): HttpDataSource.Factory {
        return if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= HTTP_ENGINE_EXTENSION
        ) {
            createPlatformFactory(context.applicationContext, userAgent)
        } else {
            OkHttpDataSource.Factory(ApiClient.getMediaHttpClient())
                .setUserAgent(userAgent)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @RequiresExtension(extension = Build.VERSION_CODES.S, version = HTTP_ENGINE_EXTENSION)
    private fun createPlatformFactory(
        context: Context,
        userAgent: String
    ): HttpDataSource.Factory {
        val engine = platformEngine ?: synchronized(this) {
            platformEngine ?: HttpEngine.Builder(context)
                .build()
                .also { platformEngine = it }
        }
        return HttpEngineDataSource.Factory(engine, httpEngineExecutor)
            .setConnectionTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
            .setUserAgent(userAgent)
    }

    private const val HTTP_ENGINE_EXTENSION = 7
}
