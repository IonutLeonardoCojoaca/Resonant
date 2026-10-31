package com.example.resonant.managers

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaymixManagerTest {
    private var nowMs = 1_000_000L
    private val service = FakePlaymixService()
    private val manager = PlaymixManager(context = null, service = service, now = { nowMs })

    @Before
    fun setUp() {
        PlaymixManager.clear()
        service.details["p1"] = playmixDetail("p1")
    }

    @After
    fun tearDown() = PlaymixManager.clear()

    @Test
    fun `the detail is served from cache for a minute`() = runTest {
        val first = manager.getPlaymixDetail("p1")
        nowMs += 59_000
        val second = manager.getPlaymixDetail("p1")

        assertSame(first, second)
        assertEquals(1, service.detailCalls)
        assertTrue(manager.isDetailFresh("p1"))
    }

    @Test
    fun `the detail expires after a minute but stays available as last known value`() = runTest {
        val first = manager.getPlaymixDetail("p1")
        nowMs += 61_000

        assertFalse(manager.isDetailFresh("p1"))
        assertSame(first, manager.cachedDetail("p1"))
        manager.getPlaymixDetail("p1")
        assertEquals(2, service.detailCalls)
    }

    @Test
    fun `forceRefresh skips the cache`() = runTest {
        manager.getPlaymixDetail("p1")
        manager.getPlaymixDetail("p1", forceRefresh = true)

        assertEquals(2, service.detailCalls)
    }

    @Test
    fun `changing the set drops the cached detail`() = runTest {
        manager.getPlaymixDetail("p1")
        manager.removeSongFromPlaymix("p1", "ps-1")

        assertNull(manager.cachedDetail("p1"))
        manager.getPlaymixDetail("p1")
        assertEquals(2, service.detailCalls)
    }

    @Test
    fun `renaming keeps the renamed detail in cache`() = runTest {
        manager.getPlaymixDetail("p1")
        val renamed = manager.renamePlaymix("p1", "Nuevo")

        assertSame(renamed, manager.getPlaymixDetail("p1"))
        assertEquals("Nuevo", manager.cachedDetail("p1")?.name)
        assertEquals(1, service.detailCalls)
    }

    @Test
    fun `invalidateDetail is shared by every instance`() = runTest {
        manager.getPlaymixDetail("p1")
        PlaymixManager.invalidateDetail("p1")

        assertNull(PlaymixManager(context = null, service = service, now = { nowMs }).cachedDetail("p1"))
    }
}
