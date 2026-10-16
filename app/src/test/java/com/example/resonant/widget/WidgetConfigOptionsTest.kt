package com.example.resonant.widget

import com.example.resonant.data.network.LiveChannelDTO
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetConfigOptionsTest {

    private val channels = listOf(
        LiveChannelDTO(channelId = "1", slug = "rock", kind = "channel", name = "Resonant Rock", status = "on_air"),
        LiveChannelDTO(channelId = "2", slug = "noche", kind = "special", name = "Especial noche", status = "upcoming"),
        LiveChannelDTO(channelId = "3", slug = "jazz", kind = "channel", name = "Jazz", status = "off_air")
    )

    @Test
    fun `everything on lists auto, room and channels on air or upcoming`() {
        assertEquals(
            listOf(
                QuickAccessConfig.Auto,
                QuickAccessConfig.Room,
                QuickAccessConfig.Live("rock", "Resonant Rock"),
                QuickAccessConfig.Live("noche", "Especial noche")
            ),
            WidgetConfigOptions.build(liveEnabled = true, unisoundEnabled = true, channels = channels)
        )
    }

    @Test
    fun `live off lists no channels`() {
        assertEquals(
            listOf(QuickAccessConfig.Auto, QuickAccessConfig.Room),
            WidgetConfigOptions.build(liveEnabled = false, unisoundEnabled = true, channels = channels)
        )
    }

    @Test
    fun `unisound off has no room option`() {
        assertEquals(
            listOf(QuickAccessConfig.Auto, QuickAccessConfig.Live("rock", "Resonant Rock"), QuickAccessConfig.Live("noche", "Especial noche")),
            WidgetConfigOptions.build(liveEnabled = true, unisoundEnabled = false, channels = channels)
        )
    }

    @Test
    fun `network error offers only the local options`() {
        assertEquals(
            listOf(QuickAccessConfig.Auto, QuickAccessConfig.Room),
            WidgetConfigOptions.build(liveEnabled = true, unisoundEnabled = true, channels = null)
        )
    }
}
