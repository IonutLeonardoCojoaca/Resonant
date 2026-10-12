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
    fun `flow v2 flag is exposed and defaults to off`() = runTest {
        assertTrue(cache { PlatformFeaturesDTO(flowV2 = true) }.refresh().flowV2)
        assertFalse(cache { PlatformFeaturesDTO() }.refresh().flowV2)
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
    fun `failure is not cached and the next refresh asks again`() = runTest {
        var fail = true
        val cache = cache { if (fail) throw IOException("401") else PlatformFeaturesDTO(unisound = true) }
        assertFalse(cache.refresh().unisound)

        fail = false
        nowMs += 1_000L

        assertTrue(cache.refresh().unisound)
        assertEquals(2, calls)
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
    fun `explore v2 flag is exposed`() = runTest {
        assertTrue(cache { PlatformFeaturesDTO(exploreV2 = true) }.refresh().exploreV2)
        assertFalse(cache { PlatformFeaturesDTO(exploreV2 = false) }.refresh().exploreV2)
    }

    @Test
    fun `forced refresh replaces the cached value`() = runTest {
        var exploreV2 = true
        val cache = cache { PlatformFeaturesDTO(exploreV2 = exploreV2) }
        assertTrue(cache.refresh().exploreV2)

        exploreV2 = false

        assertTrue(cache.refresh().exploreV2)
        assertFalse(cache.refresh(force = true).exploreV2)
        assertFalse(cache.features.exploreV2)
    }

    @Test
    fun `force bypasses fresh cache`() = runTest {
        val cache = cache { PlatformFeaturesDTO(unisound = true) }
        cache.refresh()
        cache.refresh(force = true)

        assertEquals(2, calls)
    }
}
