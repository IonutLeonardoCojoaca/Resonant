package com.example.resonant.autodownload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDownloadStorageRulesTest {
    private val now = 1_000L * AutoDownloadStorageRules.DAY_MS
    private val old = now - 40 * AutoDownloadStorageRules.DAY_MS
    private val recent = now - 2 * AutoDownloadStorageRules.DAY_MS

    private fun song(
        id: String,
        manual: Boolean = false,
        types: Set<String> = setOf(AUTO_ARTIST_COLLECTION),
        date: Long = old,
        size: Long = 100
    ) = StoredDownload(id, size, date, manual, types)

    private val autoOnly = song("auto")
    private val manualToo = song("manual", manual = true)
    private val albumToo = song("album", types = setOf(AUTO_ARTIST_COLLECTION, "album"))
    private val albumOnly = song("albumOnly", types = setOf("album"))
    private val plainManual = song("plain", manual = true, types = emptySet())
    private val recentAuto = song("recent", date = recent)
    private val all = listOf(autoOnly, manualToo, albumToo, albumOnly, plainManual, recentAuto)

    @Test
    fun `cleanup never returns manual or album songs`() {
        val candidates = AutoDownloadStorageRules.cleanupCandidates(all, keepDays = 30, nowMs = now)

        assertEquals(listOf("auto"), candidates)
    }

    @Test
    fun `keep days zero disables cleanup`() {
        assertNull(AutoDownloadStorageRules.cleanupCutoff(0, now))
        assertTrue(AutoDownloadStorageRules.cleanupCandidates(all, keepDays = 0, nowMs = now).isEmpty())
    }

    @Test
    fun `used bytes only count auto only songs`() {
        assertEquals(200L, AutoDownloadStorageRules.usedBytes(all))
    }

    @Test
    fun `auto only rule`() {
        assertTrue(AutoDownloadStorageRules.isAutoOnly(autoOnly))
        assertFalse(AutoDownloadStorageRules.isAutoOnly(manualToo))
        assertFalse(AutoDownloadStorageRules.isAutoOnly(albumToo))
        assertFalse(AutoDownloadStorageRules.isAutoOnly(albumOnly))
    }

    @Test
    fun `quota and free space`() {
        val mb = 1024L * 1024L
        val quota = 2048 * mb
        assertFalse(AutoDownloadStorageRules.fits(usedBytes = 2040 * mb, contentLength = 30 * mb, quotaBytes = quota, freeBytes = 10_000 * mb))
        assertTrue(AutoDownloadStorageRules.fits(usedBytes = 100 * mb, contentLength = 30 * mb, quotaBytes = quota, freeBytes = 10_000 * mb))
        assertFalse(AutoDownloadStorageRules.fits(usedBytes = 0, contentLength = 30 * mb, quotaBytes = quota, freeBytes = 520 * mb))
        assertTrue(AutoDownloadStorageRules.fits(usedBytes = 0, contentLength = null, quotaBytes = quota, freeBytes = 600 * mb))
    }
}
