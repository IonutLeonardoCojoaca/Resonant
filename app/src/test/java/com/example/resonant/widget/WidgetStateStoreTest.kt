package com.example.resonant.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStateStoreTest {

    @Test
    fun `an older write is discarded`() {
        assertFalse(WidgetStorePolicy.shouldApply(sequence = 4, lastWrittenSequence = 5))
        assertFalse(WidgetStorePolicy.shouldApply(sequence = 5, lastWrittenSequence = 5))
        assertTrue(WidgetStorePolicy.shouldApply(sequence = 6, lastWrittenSequence = 5))
    }

    @Test
    fun `no key holds urls, tokens or queue ids`() {
        val forbidden = listOf("url", "token", "queue", "entry", "songid", "cover")
        WidgetStorePolicy.ALL_KEYS.forEach { key ->
            forbidden.forEach { word -> assertFalse("$key contains $word", key.lowercase().contains(word)) }
        }
    }

    @Test
    fun `persisted values are only display data`() {
        val snapshot = WidgetSnapshot(
            state = WidgetState(title = "Canción A", artist = "Artista A", hasSong = true, isPlaying = true),
            inRoom = true,
            lastLive = QuickAccessTarget.Live("rock", "Resonant Rock"),
            liveEnabled = true,
            unisoundEnabled = false
        )
        val values = WidgetStorePolicy.valuesOf(snapshot)
        assertTrue(values.keys.all { it in WidgetStorePolicy.ALL_KEYS })
        values.values.filterIsInstance<String>().forEach { value ->
            assertFalse(value.startsWith("http"))
        }
        assertEquals("Canción A", values[WidgetStorePolicy.KEY_TITLE])
        assertEquals(true, values[WidgetStorePolicy.KEY_WAS_PLAYING])
        assertEquals("rock", values[WidgetStorePolicy.KEY_LAST_LIVE_SLUG])
    }

    @Test
    fun `logout clears the user state but keeps capabilities and widget configuration`() {
        val user = WidgetStorePolicy.USER_STATE_KEYS
        assertTrue(WidgetStorePolicy.KEY_TITLE in user)
        assertTrue(WidgetStorePolicy.KEY_LAST_LIVE_SLUG in user)
        assertFalse(WidgetStorePolicy.KEY_LIVE_ENABLED in user)
        assertFalse(user.any { it.startsWith(WidgetStorePolicy.KEY_QUICK_ACCESS_PREFIX) })
    }

    @Test
    fun `quick access key is per widget`() {
        assertEquals("quickAccess.12", WidgetStorePolicy.quickAccessKey(12))
    }

    @Test
    fun `an all-off capability reading keeps the previous flags`() {
        assertEquals(true to true, WidgetStorePolicy.mergeCapabilities(true, true, false, false, anyFeatureOn = false))
        assertEquals(false to true, WidgetStorePolicy.mergeCapabilities(true, true, false, true, anyFeatureOn = true))
    }
}
