package com.example.resonant.playback

import com.example.resonant.data.network.SongPlaybackDTO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap

/**
 * Resolves stable Resonant song IDs to short-lived playback URLs at data-source
 * open time. This keeps expiring signatures out of the ExoPlayer timeline.
 */
class PlaybackUrlResolver(
    private val loadPlaybackInfo: suspend (songId: String) -> SongPlaybackDTO,
    private val clock: Clock = Clock.systemUTC(),
    private val fallbackTtlMs: Long = DEFAULT_FALLBACK_TTL_MS,
    private val onPlaybackInfoResolved: (SongPlaybackDTO) -> Unit = {}
) {

    private data class CachedUrl(
        val value: String,
        val expiresAtEpochMs: Long
    )

    private val cache = object : LinkedHashMap<String, CachedUrl>(
        MAX_CACHE_ENTRIES,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, CachedUrl>?
        ): Boolean = size > MAX_CACHE_ENTRIES
    }

    private val resolutionLocks = Array(LOCK_STRIPES) { Any() }

    fun seed(
        songId: String,
        url: String?,
        expiresAtUtc: String? = null
    ) {
        if (songId.isBlank() || url.isNullOrBlank() || !url.isHttpUrl()) return
        val now = clock.millis()
        val expiry = parseBackendExpiryEpochMs(expiresAtUtc)
            ?: parsePresignedExpiryEpochMs(url)
            ?: (now + fallbackTtlMs)

        synchronized(cache) {
            if (expiry > now + EXPIRY_SAFETY_MARGIN_MS) {
                cache[songId] = CachedUrl(url, expiry)
            } else {
                cache.remove(songId)
            }
        }
    }

    fun invalidate(songId: String) {
        synchronized(cache) {
            cache.remove(songId)
        }
    }

    fun invalidateAll() {
        synchronized(cache) {
            cache.clear()
        }
    }

    fun invalidateCacheKey(cacheKey: String?) {
        val songId = cacheKey
            ?.takeIf { it.startsWith(CACHE_KEY_PREFIX) }
            ?.removePrefix(CACHE_KEY_PREFIX)
            ?: return
        invalidate(songId)
    }

    /**
     * Called from Media3's loading thread, never from the main thread.
     */
    fun resolveBlocking(songId: String): String {
        require(songId.isNotBlank()) { "A song ID is required to resolve playback" }

        getUsableCachedUrl(songId)?.let { return it }

        val lock = resolutionLocks[(songId.hashCode() and Int.MAX_VALUE) % LOCK_STRIPES]
        synchronized(lock) {
            getUsableCachedUrl(songId)?.let { return it }

            val playbackInfo = try {
                // This runs on a Media3 loading thread. Without an upper bound a
                // stalled resolve call blocks that thread indefinitely and the
                // player reports no error at all, so playback just hangs.
                runBlocking(Dispatchers.IO) {
                    withTimeout(RESOLVE_TIMEOUT_MS) {
                        loadPlaybackInfo(songId)
                    }
                }
            } catch (error: Exception) {
                throw IOException("Unable to resolve playback for song $songId", error)
            }

            val resolvedUrl = playbackInfo.streamUrl
                ?.takeIf { it.isHttpUrl() }
                ?: throw IOException("Playback endpoint returned no stream URL for song $songId")

            seed(
                songId = songId,
                url = resolvedUrl,
                expiresAtUtc = playbackInfo.expiresAtUtc
            )
            onPlaybackInfoResolved(playbackInfo)
            return resolvedUrl
        }
    }

    fun stableUri(songId: String): String {
        return stableUriFor(songId)
    }

    private fun getUsableCachedUrl(songId: String): String? {
        val now = clock.millis()
        return synchronized(cache) {
            val cached = cache[songId] ?: return@synchronized null
            if (now < cached.expiresAtEpochMs - EXPIRY_SAFETY_MARGIN_MS) {
                cached.value
            } else {
                cache.remove(songId)
                null
            }
        }
    }

    companion object {
        const val STABLE_SCHEME = "resonant"
        const val STABLE_AUTHORITY = "song"
        const val CACHE_KEY_PREFIX = "song:"
        const val DELIVERY_MODE_PROGRESSIVE = "progressive"

        private const val MAX_CACHE_ENTRIES = 256
        private const val LOCK_STRIPES = 32
        private const val DEFAULT_FALLBACK_TTL_MS = 10 * 60 * 1000L
        private const val EXPIRY_SAFETY_MARGIN_MS = 30_000L
        private const val RESOLVE_TIMEOUT_MS = 20_000L
        private val AMZ_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

        /**
         * Drops the query string so a URL keeps the same identity across
         * signature refreshes.
         */
        internal fun normalizeUrl(url: String): String? = runCatching {
            val parsed = URI(url)
            val host = parsed.host ?: return@runCatching null
            val path = parsed.path.orEmpty()
            if (path.isBlank()) return@runCatching null
            "${parsed.scheme.orEmpty().lowercase()}://${host.lowercase()}$path"
        }.getOrNull()

        fun stableUriFor(songId: String): String {
            val encodedId = java.net.URLEncoder
                .encode(songId, StandardCharsets.UTF_8.name())
                .replace("+", "%20")
            return "$STABLE_SCHEME://$STABLE_AUTHORITY/$encodedId"
        }

        internal fun parsePresignedExpiryEpochMs(url: String): Long? {
            return runCatching {
                val query = URI(url).rawQuery.orEmpty()
                    .split('&')
                    .mapNotNull { parameter ->
                        val parts = parameter.split('=', limit = 2)
                        if (parts.isEmpty()) return@mapNotNull null
                        val key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name())
                        val value = parts.getOrNull(1)
                            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
                            .orEmpty()
                        key.lowercase() to value
                    }
                    .toMap()

                val signedAt = query["x-amz-date"]
                    ?: return null
                val lifetimeSeconds = query["x-amz-expires"]?.toLongOrNull()
                    ?: return null

                val issuedAt = LocalDateTime
                    .parse(signedAt, AMZ_DATE_FORMATTER)
                    .toInstant(ZoneOffset.UTC)
                issuedAt.plusSeconds(lifetimeSeconds).toEpochMilli()
            }.getOrNull()
        }

        internal fun parseBackendExpiryEpochMs(expiresAtUtc: String?): Long? {
            val value = expiresAtUtc?.takeIf { it.isNotBlank() } ?: return null
            return runCatching {
                OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .toInstant()
                    .toEpochMilli()
            }.recoverCatching {
                Instant.parse(value).toEpochMilli()
            }.getOrNull()
        }

        private fun String.isHttpUrl(): Boolean {
            return startsWith("https://", ignoreCase = true) ||
                startsWith("http://", ignoreCase = true)
        }
    }
}
