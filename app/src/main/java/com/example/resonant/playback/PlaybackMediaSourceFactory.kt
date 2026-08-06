package com.example.resonant.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource

@OptIn(UnstableApi::class)
class PlaybackMediaSourceFactory(
    private val context: Context,
    private val urlResolver: PlaybackUrlResolver
) {

    fun create(): MediaSource.Factory {
        val httpFactory = MediaHttpDataSourceProvider.create(
            context,
            "Resonant/Android"
        )

        val resolvingHttpFactory = ResolvingDataSource.Factory(httpFactory) { dataSpec ->
            val uri = dataSpec.uri
            if (uri.scheme == PlaybackUrlResolver.STABLE_SCHEME &&
                uri.authority == PlaybackUrlResolver.STABLE_AUTHORITY
            ) {
                val songId = uri.pathSegments.firstOrNull()
                    ?: throw IllegalArgumentException("Stable playback URI has no song ID: $uri")
                dataSpec.withUri(Uri.parse(urlResolver.resolveBlocking(songId)))
            } else {
                dataSpec
            }
        }

        val upstreamFactory: DataSource.Factory = DefaultDataSource.Factory(
            context.applicationContext,
            resolvingHttpFactory
        )

        val streamingCacheFactory = CacheDataSource.Factory()
            .setCache(PlaybackCache.get(context))
            .setUpstreamDataSourceFactory(upstreamFactory)
            // Progressive loads carry a stable `song:<id>` cache key. If a
            // request ever arrives without one, fall back to the URL without
            // its query string so a refreshed signature does not create a
            // brand-new cache entry for bytes already on disk.
            .setCacheKeyFactory { dataSpec ->
                dataSpec.key
                    ?: PlaybackUrlResolver.normalizeUrl(dataSpec.uri.toString())
                    ?: dataSpec.uri.toString()
            }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val offlineFirstFactory = CacheDataSource.Factory()
            .setCache(OfflineDownloadProvider.getCache(context))
            .setUpstreamDataSourceFactory(streamingCacheFactory)
            // Permanent storage is written only by DownloadManager.
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return DefaultMediaSourceFactory(offlineFirstFactory)
            .setLoadErrorHandlingPolicy(PlaybackLoadErrorPolicy(urlResolver))
    }
}
