package com.example.resonant.managers

import android.content.Context
import android.util.Log
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.PlatformFeaturesDTO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Server feature flags (`GET api/capabilities`) cached in memory for [CACHE_DURATION_MS].
 * Any failure counts as "feature off" so a broken endpoint never surfaces UI; failures are
 * not cached, so the next refresh asks again.
 */
class CapabilitiesCache(
    private val fetch: suspend () -> PlatformFeaturesDTO?,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()

    @Volatile
    var features: PlatformFeaturesDTO = PlatformFeaturesDTO()
        private set
    private var fetchedAtMs: Long? = null

    suspend fun refresh(force: Boolean = false): PlatformFeaturesDTO = mutex.withLock {
        val last = fetchedAtMs
        if (!force && last != null && now() - last < CACHE_DURATION_MS) return@withLock features
        val fetched = runCatching { fetch() }.getOrNull()
        // Only a real answer is cached: a failure (e.g. 401 before logging in again) must be
        // retried on the next refresh instead of hiding the feature for the whole window.
        features = fetched ?: PlatformFeaturesDTO()
        fetchedAtMs = if (fetched != null) now() else null
        features
    }

    companion object {
        const val CACHE_DURATION_MS = 10 * 60 * 1000L
    }
}

object CapabilitiesManager {
    private const val TAG = "CapabilitiesManager"

    @Volatile
    private var cache: CapabilitiesCache? = null

    private fun cache(context: Context): CapabilitiesCache =
        cache ?: synchronized(this) {
            cache ?: run {
                val service = ApiClient.getPlatformService(context.applicationContext)
                CapabilitiesCache(fetch = {
                    runCatching { service.getCapabilities().features }
                        .onFailure { Log.w(TAG, "capabilities unavailable: ${it.javaClass.simpleName}") }
                        .getOrThrow()
                })
            }.also { cache = it }
        }

    /** Last known value; false until the first successful [refresh]. */
    fun isUnisoundEnabled(): Boolean = cache?.features?.unisound == true

    /** Last known value; false until the first successful [refresh]. */
    fun isExploreV2Enabled(): Boolean = cache?.features?.exploreV2 == true

    /** Last known value; false until the first successful [refresh]. */
    fun isFlowV2Enabled(): Boolean = cache?.features?.flowV2 == true

    suspend fun refresh(context: Context, force: Boolean = false): PlatformFeaturesDTO =
        withContext(Dispatchers.IO) { cache(context).refresh(force) }
}
