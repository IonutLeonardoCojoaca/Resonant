package com.example.resonant.recap

import com.example.resonant.data.network.RecapSummaryDTO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class RecapBannerPolicyTest {
    private val now = Instant.parse("2026-10-03T10:00:00Z").toEpochMilli()
    private val fresh = RecapSummaryDTO("2026-09", seen = false, generatedAt = "2026-10-01T01:04:40.000Z")

    @Test
    fun `unseen and recent shows the banner`() {
        assertEquals(fresh, RecapBannerPolicy.bannerFor(listOf(fresh), now, emptySet()))
    }

    @Test
    fun `only the most recent counts`() {
        val older = RecapSummaryDTO("2026-08", seen = false, generatedAt = "2026-09-01T01:00:00Z")
        val seen = fresh.copy(seen = true)
        assertNull(RecapBannerPolicy.bannerFor(listOf(older, seen), now, emptySet()))
    }

    @Test
    fun `seen, dismissed, old or empty hide it`() {
        assertNull(RecapBannerPolicy.bannerFor(listOf(fresh.copy(seen = true)), now, emptySet()))
        assertNull(RecapBannerPolicy.bannerFor(listOf(fresh), now, setOf("2026-09")))
        val later = Instant.parse("2026-11-02T10:00:00Z").toEpochMilli()
        assertNull(RecapBannerPolicy.bannerFor(listOf(fresh), later, emptySet()))
        assertNull(RecapBannerPolicy.bannerFor(emptyList(), now, emptySet()))
    }

    @Test
    fun `latest ignores invalid periods and keeps seen recaps`() {
        val seen = RecapSummaryDTO("2026-09", seen = true, generatedAt = "2026-10-01T01:00:00Z")
        val older = RecapSummaryDTO("2026-08", seen = false, generatedAt = "2026-09-01T01:00:00Z")
        val broken = RecapSummaryDTO("zzzz", seen = false, generatedAt = "2026-10-01T01:00:00Z")

        assertEquals(seen, RecapBannerPolicy.latest(listOf(older, broken, seen)))
        assertNull(RecapBannerPolicy.latest(listOf(broken)))
        assertNull(RecapBannerPolicy.latest(emptyList()))
    }
}
