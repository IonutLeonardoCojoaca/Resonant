package com.example.resonant.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetStateTest {

    @Test
    fun `quick access configs round-trip`() {
        listOf(
            QuickAccessConfig.Auto,
            QuickAccessConfig.Room,
            QuickAccessConfig.Live("rock", "Resonant Rock")
        ).forEach { config -> assertEquals(config, QuickAccessConfig.decode(config.encode())) }
    }

    @Test
    fun `encoded format is AUTO, ROOM or LIVE slug name`() {
        assertEquals("AUTO", QuickAccessConfig.Auto.encode())
        assertEquals("ROOM", QuickAccessConfig.Room.encode())
        assertEquals("LIVE:rock:Resonant Rock", QuickAccessConfig.Live("rock", "Resonant Rock").encode())
    }

    @Test
    fun `a channel name with colons survives`() {
        val config = QuickAccessConfig.Live("jazz", "Jazz: noches 24:00")
        assertEquals(config, QuickAccessConfig.decode(config.encode()))
    }

    @Test
    fun `corrupt or missing values fall back to Auto`() {
        listOf(null, "", "auto", "LIVE:", "LIVE:rock", "LIVE::Name", "SOMETHING").forEach { raw ->
            assertEquals("raw=$raw", QuickAccessConfig.Auto, QuickAccessConfig.decode(raw))
        }
    }
}
