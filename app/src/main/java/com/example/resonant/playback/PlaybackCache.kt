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

    /** Bytes que ocupa ahora la caché de streaming (no incluye las descargas). */
    fun sizeBytes(context: Context): Long = runCatching { get(context).cacheSpace }.getOrDefault(0L)

    /**
     * Vacía la caché de streaming desde Ajustes. Incluye lo que esté sonando: el reproductor sigue
     * con los ficheros que ya tiene abiertos y lo que falte se vuelve a descargar. Las descargas
     * (OfflineDownloadProvider) son otra caché y no se tocan. Devuelve los bytes liberados.
     */
    fun clear(context: Context): Long {
        val cache = get(context)
        val before = sizeBytes(context)
        cache.keys.toList().forEach { key -> runCatching { cache.removeResource(key) } }
        return (before - sizeBytes(context)).coerceAtLeast(0L)
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
