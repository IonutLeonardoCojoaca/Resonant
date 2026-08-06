package com.example.resonant.managers

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSettingsModelTest {
    @Test
    fun `quality parses stored enum and api values`() {
        assertEquals(
            AudioQuality.HIGH,
            AudioQuality.fromStored("HIGH", AudioQuality.AUTO)
        )
        assertEquals(
            AudioQuality.DATA_SAVER,
            AudioQuality.fromStored("data-saver", AudioQuality.AUTO)
        )
        assertEquals(
            AudioQuality.NORMAL,
            AudioQuality.fromStored("normal", AudioQuality.AUTO)
        )
    }

    @Test
    fun `unknown quality uses explicit fallback`() {
        assertEquals(
            AudioQuality.HIGH,
            AudioQuality.fromStored("lossless-not-supported", AudioQuality.HIGH)
        )
    }

    @Test
    fun `equalizer headroom follows largest positive gain`() {
        assertEquals(
            6f,
            UserEqualizerSettings(
                enabled = true,
                preset = EqualizerPreset.CUSTOM,
                gainsDb = listOf(-4f, 0f, 2f, 6f, 1f)
            ).headroomDb
        )
        assertEquals(
            0f,
            UserEqualizerSettings(
                gainsDb = listOf(-6f, -3f, -1f, -2f, -4f)
            ).headroomDb
        )
    }
}

