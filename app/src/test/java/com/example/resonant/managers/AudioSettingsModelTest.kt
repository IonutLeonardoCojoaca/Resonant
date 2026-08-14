package com.example.resonant.managers

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSettingsModelTest {
    @Test
    fun `quality parses stored enum and api values`() {
        assertEquals(
            AudioQuality.AUTO,
            AudioQuality.fromStored("AUTO", AudioQuality.AUTO)
        )
        assertEquals(
            AudioQuality.AUTO,
            AudioQuality.fromStored("auto", AudioQuality.AUTO)
        )
    }

    @Test
    fun `legacy and unknown qualities use automatic fallback`() {
        listOf("HIGH", "data-saver", "normal", "lossless-not-supported").forEach { stored ->
            assertEquals(
                stored,
                AudioQuality.AUTO,
                AudioQuality.fromStored(stored, AudioQuality.AUTO)
            )
        }
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
