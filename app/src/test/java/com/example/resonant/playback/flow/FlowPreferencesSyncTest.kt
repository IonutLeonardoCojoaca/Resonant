package com.example.resonant.playback.flow

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowPreferencesSyncTest {

    private class FakeLocal(var value: LocalFlowPreferences) : FlowPreferencesLocal {
        override suspend fun read() = value
        override suspend fun write(preferences: LocalFlowPreferences) {
            value = preferences
        }
    }

    private class FakeRemote(var server: Pair<Boolean, Int>? = null, var pushSucceeds: Boolean = true) :
        FlowPreferencesRemote {
        val pushes = mutableListOf<Pair<Boolean, Int>>()
        override suspend fun fetch() = server
        override suspend fun push(enabled: Boolean, discoveryLevel: Int): Boolean {
            pushes += enabled to discoveryLevel
            if (pushSucceeds) server = enabled to discoveryLevel
            return pushSucceeds
        }
    }

    @Test
    fun `server value is adopted at start when nothing is pending`() = runTest {
        val local = FakeLocal(LocalFlowPreferences(true, 1, pendingSync = false))
        val sync = FlowPreferencesSync(local, FakeRemote(server = false to 2))

        sync.syncOnStart()

        assertEquals(LocalFlowPreferences(false, 2, pendingSync = false), local.value)
    }

    @Test
    fun `failed push stays pending and is retried at start`() = runTest {
        val local = FakeLocal(LocalFlowPreferences(true, 1, pendingSync = false))
        val remote = FakeRemote(server = true to 1, pushSucceeds = false)
        val sync = FlowPreferencesSync(local, remote)

        sync.update(enabled = true, discoveryLevel = 2, syncWithServer = true)
        assertTrue(local.value.pendingSync)

        remote.pushSucceeds = true
        sync.syncOnStart()

        assertFalse(local.value.pendingSync)
        assertEquals(true to 2, remote.server)
        assertEquals(2, local.value.discoveryLevel)
    }

    @Test
    fun `without flowV2 changes stay local`() = runTest {
        val local = FakeLocal(LocalFlowPreferences(true, 1, pendingSync = false))
        val remote = FakeRemote()

        FlowPreferencesSync(local, remote).update(enabled = false, discoveryLevel = 0, syncWithServer = false)

        assertEquals(LocalFlowPreferences(false, 0, pendingSync = false), local.value)
        assertTrue(remote.pushes.isEmpty())
    }

    @Test
    fun `offline start keeps the local value`() = runTest {
        val local = FakeLocal(LocalFlowPreferences(false, 2, pendingSync = false))

        FlowPreferencesSync(local, FakeRemote(server = null)).syncOnStart()

        assertEquals(LocalFlowPreferences(false, 2, pendingSync = false), local.value)
    }
}
