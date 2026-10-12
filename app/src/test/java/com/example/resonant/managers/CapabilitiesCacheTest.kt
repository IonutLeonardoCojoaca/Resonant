package com.example.resonant.managers

import com.example.resonant.data.network.PlatformFeaturesDTO
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CapabilitiesCacheTest {
    private var nowMs = 0L
    private var calls = 0

    private fun cache(result: () -> PlatformFeaturesDTO?) = CapabilitiesCache(
        fetch = { calls++; result() },
        now = { nowMs }
    )

    @Test
    fun `enabled flag is exposed`() = runTest {
        val cache = cache { PlatformFeaturesDTO(unisound = true) }

        assertTrue(cache.refresh().unisound)
        assertTrue(cache.features.unisound)
    }

    @Test
    fun `disabled flag is exposed`() = runTest {
        val cache = cache { PlatformFeaturesDTO(unisound = false) }

        assertFalse(cache.refresh().unisound)
    }

    @Test
    fun `failure counts as disabled`() = runTest {
        var fail = false
        val cache = cache { if (fail) throw IOException("offline") else PlatformFeaturesDTO(unisound = true) }
        cache.refresh()
        fail = true
        nowMs += CapabilitiesCache.CACHE_DURATION_MS + 1

        assertFalse(cache.refresh().unisound)
    }

    @Test
    fun `missing features object counts as disabled`() = runTest {
        assertFalse(cache { null }.refresh().unisound)
    }

    @Test
    fun `fresh cache is reused and expired cache refetches`() = runTest {
        val cache = cache { PlatformFeaturesDTO(unisound = true) }
        cache.refresh()
        nowMs += CapabilitiesCache.CACHE_DURATION_MS - 1
        cache.refresh()
        assertEquals(1, calls)

        nowMs += 2
        cache.refresh()
        assertEquals(2, calls)
    }

    @Test
    fun `force bypasses fresh cache`() = runTest {
        val cache = cache { PlatformFeaturesDTO(unisound = true) }
        cache.refresh()
        cache.refresh(force = true)

        assertEquals(2, calls)
    }
}
