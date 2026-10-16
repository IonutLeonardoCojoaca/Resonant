package com.example.resonant.managers

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDownloadPreferencesTest {

    @Test
    fun `keys are suffixed with the user id`() {
        assertEquals("paused_u1", AutoDownloadPreferenceKeys.paused("u1").name)
        assertNotEquals(AutoDownloadPreferenceKeys.paused("u1"), AutoDownloadPreferenceKeys.paused("u2"))
        assertNotEquals(AutoDownloadPreferenceKeys.maxStorageMb("u1"), AutoDownloadPreferenceKeys.maxStorageMb("u2"))
    }

    @Test
    fun `two users do not share values`() {
        val prefs = mutablePreferencesOf()
        prefs[AutoDownloadPreferenceKeys.paused("u1")] = true
        prefs[AutoDownloadPreferenceKeys.wifiOnly("u1")] = false
        prefs[AutoDownloadPreferenceKeys.keepDays("u1")] = 30

        val first = AutoDownloadPreferenceKeys.read(prefs, "u1")
        val second = AutoDownloadPreferenceKeys.read(prefs, "u2")

        assertTrue(first.paused)
        assertFalse(first.wifiOnly)
        assertEquals(30, first.keepDays)
        assertEquals(AutoDownloadSettings(), second)
    }

    @Test
    fun `user without saved preferences is not paused and uses defaults`() {
        val settings = AutoDownloadPreferenceKeys.read(mutablePreferencesOf(), "nuevo")

        assertFalse(settings.paused)
        assertTrue(settings.wifiOnly)
        assertFalse(settings.requiresCharging)
        assertEquals(2048, settings.maxStorageMb)
        assertEquals(0, settings.keepDays)
        assertEquals(2048L * 1024 * 1024, settings.maxStorageBytes)
    }

    @Test
    fun `out of range values are clamped`() {
        val prefs = mutablePreferencesOf()
        prefs[AutoDownloadPreferenceKeys.maxStorageMb("u1")] = 1
        prefs[AutoDownloadPreferenceKeys.keepDays("u1")] = -4

        val settings = AutoDownloadPreferenceKeys.read(prefs, "u1")

        assertEquals(AutoDownloadPreferenceKeys.MIN_STORAGE_MB, settings.maxStorageMb)
        assertEquals(0, settings.keepDays)
    }
}
