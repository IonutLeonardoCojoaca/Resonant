package com.example.resonant.data.network

import android.content.Context
import android.util.Log
import com.example.resonant.data.network.services.ExploreService
import kotlinx.coroutines.CancellationException

sealed class ExploreLoadResult {
    data class Ok(val session: ExploreSession) : ExploreLoadResult()
    /** 304: the session copy is still the current layout. */
    object NotModified : ExploreLoadResult()
    /** 404 `FEATURE_DISABLED`: the server turned Explore v2 off. */
    object FeatureDisabled : ExploreLoadResult()
    object Failed : ExploreLoadResult()
}

sealed class ExploreSectionResult {
    data class Loaded(val section: ExploreSectionDTO) : ExploreSectionResult()
    /** 404 `EXPLORE_SECTION_NOT_FOUND`: the id is no longer part of the layout. */
    object Missing : ExploreSectionResult()
    object Failed : ExploreSectionResult()
}

/** Last Explore v2 layout of this app session, with what was loaded or dismissed on top of it. */
data class ExploreSession(
    val userId: String,
    val tz: String,
    val etag: String?,
    val response: ExploreResponseDTO,
    val deferredLoaded: Map<String, ExploreSectionDTO> = emptyMap(),
    val dismissedAriaSongIds: Set<String> = emptySet()
)

/**
 * Explore v2 calls plus a single in-memory session slot (not persisted: the server already caches
 * the layout for 10 min and it expires with the daypart). Reopening Explore paints the slot at once
 * and revalidates it with `If-None-Match`.
 */
class ExploreRepository internal constructor(
    private val service: ExploreService,
    private val userId: () -> String?,
    private val log: (String) -> Unit = {}
) {
    @Volatile
    private var session: ExploreSession? = null

    /** The session copy, only if it belongs to the current user and the same time zone. */
    fun cached(tz: String): ExploreSession? {
        val owner = userId()?.takeIf(String::isNotBlank) ?: return null
        return session?.takeIf { it.userId == owner && it.tz == tz }
    }

    suspend fun load(tz: String): ExploreLoadResult {
        val owner = userId()?.takeIf(String::isNotBlank) ?: return ExploreLoadResult.Failed
        val previous = cached(tz)
        return try {
            val response = service.getExplore(tz, previous?.etag)
            val body = response.body()
            when {
                response.code() == 304 && previous != null -> ExploreLoadResult.NotModified
                response.isSuccessful && body != null -> {
                    val etag = response.headers()["ETag"]
                    val next = ExploreSession(
                        userId = owner,
                        tz = tz,
                        etag = etag,
                        response = body,
                        dismissedAriaSongIds = previous
                            ?.takeIf { etag != null && it.etag == etag }
                            ?.dismissedAriaSongIds
                            .orEmpty()
                    )
                    session = next
                    ExploreLoadResult.Ok(next)
                }
                response.code() == 404 && ApiErrorParser.parse(response)?.code == CODE_FEATURE_DISABLED ->
                    ExploreLoadResult.FeatureDisabled
                else -> ExploreLoadResult.Failed.also { log("explore HTTP ${response.code()}") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("explore failed: ${e.javaClass.simpleName}")
            ExploreLoadResult.Failed
        }
    }

    suspend fun loadSection(id: String, tz: String): ExploreSectionResult = try {
        val response = service.getSection(id, tz)
        val body = response.body()
        when {
            response.isSuccessful && body != null -> {
                session = cached(tz)?.let { it.copy(deferredLoaded = it.deferredLoaded + (id to body)) } ?: session
                ExploreSectionResult.Loaded(body)
            }
            response.code() == 404 && ApiErrorParser.parse(response)?.code == CODE_SECTION_NOT_FOUND ->
                ExploreSectionResult.Missing
            else -> ExploreSectionResult.Failed.also { log("explore section HTTP ${response.code()}") }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("explore section failed: ${e.javaClass.simpleName}")
        ExploreSectionResult.Failed
    }

    /** Keeps the dismissed Aria picks of the current layout so they stay hidden on reopen. */
    fun rememberDismissed(tz: String, etag: String?, songIds: Set<String>) {
        session = cached(tz)?.takeIf { it.etag == etag }?.copy(dismissedAriaSongIds = songIds) ?: session
    }

    companion object {
        private const val TAG = "ExploreRepo"
        private const val CODE_FEATURE_DISABLED = "FEATURE_DISABLED"
        private const val CODE_SECTION_NOT_FOUND = "EXPLORE_SECTION_NOT_FOUND"

        @Volatile
        private var instance: ExploreRepository? = null

        fun get(context: Context): ExploreRepository =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        private fun create(context: Context) = ExploreRepository(
            service = ApiClient.getExploreService(context),
            userId = {
                context.getSharedPreferences("user_data", Context.MODE_PRIVATE).getString("USER_ID", null)
            },
            log = { Log.w(TAG, it) }
        )
    }
}
