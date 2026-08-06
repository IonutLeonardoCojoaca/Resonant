package com.example.resonant.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Process-wide bounded cache for streamed media.
 */
@OptIn(UnstableApi::class)
object PlaybackCache {
    private const val MAX_CACHE_BYTES = 256L * 1024L * 1024L

    @Volatile
    private var instance: SimpleCache? = null

    fun get(context: Context): SimpleCache {
        return instance ?: synchronized(this) {
            instance ?: SimpleCache(
                File(context.applicationContext.cacheDir, "media_stream_cache"),
                LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
                StandaloneDatabaseProvider(context.applicationContext)
            ).also { instance = it }
        }
    }

    /** Drops only the cached bytes for one stream after a format mismatch. */
    fun removeResource(context: Context, cacheKey: String): Boolean {
        if (cacheKey.isBlank()) return false
        return runCatching {
            get(context).removeResource(cacheKey)
            true
        }.getOrDefault(false)
    }
}
