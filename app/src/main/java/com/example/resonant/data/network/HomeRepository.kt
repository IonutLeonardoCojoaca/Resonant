package com.example.resonant.data.network

import android.content.Context
import androidx.core.content.edit
import com.example.resonant.data.network.services.HomeService
import com.example.resonant.managers.UserManager
import com.google.gson.Gson
import retrofit2.HttpException
import java.text.Normalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class HomeSectionKind {
    RECOMMENDED_SONGS,
    HISTORY,
    RECOMMENDED_ARTISTS,
    RECOMMENDED_ALBUMS,
    RECENT_ARTISTS,
    RECENT_ALBUMS,
    TOP_SONGS,
    TOP_ARTISTS,
    TOP_ALBUMS
}

object HomeSectionClassifier {
    fun classify(section: HomeSectionDTO): HomeSectionKind? {
        val id = section.id.normalized()
        val type = section.type.normalized()
        val title = section.title.normalized()
        val descriptor = "$id $type $title"

        val isSong = descriptor.containsAny("song", "cancion", "track")
        val isArtist = descriptor.containsAny("artist", "artista")
        val isAlbum = descriptor.containsAny("album")
        val isHistory = descriptor.containsAny(
            "history",
            "historial",
            "recently played",
            "escuchado recientemente"
        )
        val isTop = descriptor.containsAny(
            "top",
            "most listened",
            "mas escuch",
            "most played"
        )
        val isRecent = descriptor.containsAny(
            "recent",
            "new release",
            "newly added",
            "recient",
            "nuevo lanzamiento"
        )
        val isRecommended = descriptor.containsAny(
            "recommend",
            "recomend",
            "for you",
            "para ti"
        )

        return when {
            isHistory -> HomeSectionKind.HISTORY
            isTop && isSong -> HomeSectionKind.TOP_SONGS
            isTop && isArtist -> HomeSectionKind.TOP_ARTISTS
            isTop && isAlbum -> HomeSectionKind.TOP_ALBUMS
            isRecent && isArtist -> HomeSectionKind.RECENT_ARTISTS
            isRecent && isAlbum -> HomeSectionKind.RECENT_ALBUMS
            isRecommended && isSong -> HomeSectionKind.RECOMMENDED_SONGS
            isRecommended && isArtist -> HomeSectionKind.RECOMMENDED_ARTISTS
            isRecommended && isAlbum -> HomeSectionKind.RECOMMENDED_ALBUMS
            id in SONG_RECOMMENDATION_IDS -> HomeSectionKind.RECOMMENDED_SONGS
            id in ARTIST_RECOMMENDATION_IDS -> HomeSectionKind.RECOMMENDED_ARTISTS
            id in ALBUM_RECOMMENDATION_IDS -> HomeSectionKind.RECOMMENDED_ALBUMS
            type in SONG_TYPES -> HomeSectionKind.RECOMMENDED_SONGS
            type in ARTIST_TYPES -> HomeSectionKind.RECOMMENDED_ARTISTS
            type in ALBUM_TYPES -> HomeSectionKind.RECOMMENDED_ALBUMS
            else -> null
        }
    }

    private fun String.normalized(): String {
        return Normalizer.normalize(this, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .lowercase()
            .replace(Regex("[_-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun String.containsAny(vararg values: String): Boolean {
        return values.any(::contains)
    }

    private val SONG_RECOMMENDATION_IDS = setOf("songs", "recommended songs")
    private val ARTIST_RECOMMENDATION_IDS = setOf("artists", "recommended artists")
    private val ALBUM_RECOMMENDATION_IDS = setOf("albums", "recommended albums")
    private val SONG_TYPES = setOf("song", "songs", "track", "tracks")
    private val ARTIST_TYPES = setOf("artist", "artists")
    private val ALBUM_TYPES = setOf("album", "albums")
}

class HomeRepository(
    context: Context,
    private val service: HomeService = ApiClient.getHomeService(context),
    private val gson: Gson = Gson()
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /**
     * Devuelve la copia local inmediatamente, aislada por usuario. La lectura y
     * deserialización no se hacen en Main porque un Home grande puede provocar
     * un frame lento durante el arranque.
     */
    suspend fun getCachedHome(maxAgeMs: Long? = null): HomeResponseDTO? {
        val owner = UserManager(appContext).getUserId()?.takeIf(String::isNotBlank)
            ?: return null
        return withContext(Dispatchers.IO) {
            val cached = readCache(owner) ?: return@withContext null
            val ageMs = System.currentTimeMillis() - cached.storedAtEpochMs
            if (maxAgeMs != null && ageMs > maxAgeMs) null else cached.payload
        }
    }

    suspend fun getHome(): HomeResponseDTO {
        val owner = UserManager(appContext).getUserId()?.takeIf(String::isNotBlank)
        val cached = withContext(Dispatchers.IO) {
            owner?.let(::readCache)
        }
        if (!V2EndpointAvailability.shouldTry(
                appContext,
                V2EndpointAvailability.FEATURE_HOME
            )
        ) {
            return cached?.payload
                ?: throw UnsupportedOperationException("Home v2 is not enabled")
        }

        return try {
            val response = service.getHome(cached?.etag)
            when {
                response.code() == 304 && cached != null -> {
                    V2EndpointAvailability.markAvailable(
                        appContext,
                        V2EndpointAvailability.FEATURE_HOME
                    )
                    cached.payload
                }
                response.isSuccessful -> {
                    val payload = response.body()
                        ?: throw IllegalStateException("Home v2 returned an empty body")
                    if (owner != null) {
                        withContext(Dispatchers.IO) {
                            writeCache(
                                owner = owner,
                                etag = response.headers()["ETag"],
                                payload = payload
                            )
                        }
                    }
                    V2EndpointAvailability.markAvailable(
                        appContext,
                        V2EndpointAvailability.FEATURE_HOME
                    )
                    payload
                }
                else -> {
                    if (response.code() in UNSUPPORTED_HTTP_CODES) {
                        V2EndpointAvailability.markUnsupported(
                            appContext,
                            V2EndpointAvailability.FEATURE_HOME
                        )
                    }
                    throw HttpException(response)
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            cached?.payload ?: throw error
        }
    }

    private fun readCache(owner: String): CachedHome? {
        val json = preferences.getString(payloadKey(owner), null) ?: return null
        val payload = runCatching { gson.fromJson(json, HomeResponseDTO::class.java) }
            .getOrNull()
            ?: return null
        return CachedHome(
            etag = preferences.getString(etagKey(owner), null),
            payload = payload,
            storedAtEpochMs = preferences.getLong(storedAtKey(owner), 0L)
        )
    }

    private fun writeCache(owner: String, etag: String?, payload: HomeResponseDTO) {
        preferences.edit {
            putString(payloadKey(owner), gson.toJson(payload))
            putString(etagKey(owner), etag)
            putLong(storedAtKey(owner), System.currentTimeMillis())
        }
    }

    private fun payloadKey(owner: String) = "payload:$owner"
    private fun etagKey(owner: String) = "etag:$owner"
    private fun storedAtKey(owner: String) = "stored_at:$owner"

    private data class CachedHome(
        val etag: String?,
        val payload: HomeResponseDTO,
        val storedAtEpochMs: Long
    )

    companion object {
        private const val PREFERENCES = "home_v2_cache"
        private val UNSUPPORTED_HTTP_CODES = setOf(404, 405, 501)
    }
}
