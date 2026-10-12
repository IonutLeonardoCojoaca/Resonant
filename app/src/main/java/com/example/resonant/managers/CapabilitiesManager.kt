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
 * Any failure counts as "feature off" so a broken endpoint never surfaces UI.
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
        features = runCatching { fetch() }.getOrNull() ?: PlatformFeaturesDTO()
        fetchedAtMs = now()
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

    suspend fun refresh(context: Context, force: Boolean = false): PlatformFeaturesDTO =
        withContext(Dispatchers.IO) { cache(context).refresh(force) }
}
