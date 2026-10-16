package com.example.resonant.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetQuickAccessResolverTest {

    private val rock = QuickAccessTarget.Live("rock", "Resonant Rock")

    private fun resolve(
        config: QuickAccessConfig,
        inRoom: Boolean = false,
        lastLive: QuickAccessTarget.Live? = null,
        liveEnabled: Boolean = true,
        unisoundEnabled: Boolean = true
    ) = WidgetQuickAccessResolver.resolve(config, inRoom, lastLive, liveEnabled, unisoundEnabled)

    @Test
    fun `automatic in a room returns to the room`() {
        assertEquals(QuickAccessTarget.Room, resolve(QuickAccessConfig.Auto, inRoom = true, lastLive = rock))
    }

    @Test
    fun `automatic outside a room uses the last channel`() {
        assertEquals(rock, resolve(QuickAccessConfig.Auto, lastLive = rock))
    }

    @Test
    fun `automatic with nothing shows nothing`() {
        assertNull(resolve(QuickAccessConfig.Auto))
    }

    @Test
    fun `automatic skips what is switched off`() {
        assertEquals(rock, resolve(QuickAccessConfig.Auto, inRoom = true, lastLive = rock, unisoundEnabled = false))
        assertNull(resolve(QuickAccessConfig.Auto, lastLive = rock, liveEnabled = false))
    }

    @Test
    fun `fixed channel with live off is hidden`() {
        assertNull(resolve(QuickAccessConfig.Live("rock", "Resonant Rock"), liveEnabled = false))
        assertEquals(rock, resolve(QuickAccessConfig.Live("rock", "Resonant Rock")))
    }

    @Test
    fun `back to my room with unisound off is hidden`() {
        assertNull(resolve(QuickAccessConfig.Room, inRoom = true, unisoundEnabled = false))
        assertEquals(QuickAccessTarget.Room, resolve(QuickAccessConfig.Room, inRoom = false))
    }
}
