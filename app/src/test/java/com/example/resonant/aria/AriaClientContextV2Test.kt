package com.example.resonant.aria

import com.example.resonant.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AriaClientContextV2Test {

    private fun input(
        playback: AriaClientContextV2.Playback? = null,
        room: String? = null,
        live: String? = null
    ) = AriaClientContextV2.Input(
        appVersion = "4.0.0",
        timeZone = "Europe/Madrid",
        playback = playback,
        unisoundSessionId = room,
        liveChannelSlug = live
    )

    @Test
    fun `always sends schema version, client and time zone`() {
        val fields = AriaClientContextV2.fields(input())

        assertEquals(2, fields["schema_version"])
        assertEquals("Europe/Madrid", fields["time_zone"])
        val client = fields["client"] as Map<*, *>
        assertEquals("android", client["platform"])
        assertEquals("4.0.0", client["version"])
        assertEquals(AriaClientContextV2.capabilities(), client["capabilities"])
    }

    @Test
    fun `listening to a Live channel sends its slug and LIVE source`() {
        val fields = AriaClientContextV2.fields(
            input(playback = AriaClientContextV2.Playback("LIVE", sourceId = "live:rock"), live = "rock")
        )

        val playback = fields["playback"] as Map<*, *>
        assertEquals("LIVE", playback["source_type"])
        assertEquals("rock", playback["source_id"])
        assertEquals(mapOf("live_channel_slug" to "rock"), AriaClientContextV2.sessionFields(null, "rock"))
    }

    @Test
    fun `inside a Unisound room the session never carries the Live slug`() {
        val session = AriaClientContextV2.sessionFields("room-1", "rock")

        assertEquals(mapOf("unisound_session_id" to "room-1"), session)
        assertFalse(session!!.containsKey("live_channel_slug"))
    }

    @Test
    fun `nothing playing sends no playback`() {
        val fields = AriaClientContextV2.fields(input(playback = null))

        assertFalse(fields.containsKey("playback"))
        assertNull(AriaClientContextV2.sessionFields(null, null))
        assertNull(AriaClientContextV2.sessionFields(" ", " "))
    }

    @Test
    fun `unknown source types are sent as UNKNOWN and FLOW is kept`() {
        assertEquals(
            "UNKNOWN",
            AriaClientContextV2.playbackFields(AriaClientContextV2.Playback("SOMETHING_NEW"))!!["source_type"]
        )
        assertEquals(
            "FLOW",
            AriaClientContextV2.playbackFields(AriaClientContextV2.Playback("FLOW", "album-1"))!!["source_type"]
        )
    }

    @Test
    fun `capabilities list the seven V2a families plus optional screens`() {
        val base = AriaClientContextV2.capabilities(hasListeningStats = false, hasMonthlyRecap = false)
        assertEquals(
            listOf("unisound", "live", "flow", "explore", "streak", "aria_picks", "navigation"),
            base
        )
        val full = AriaClientContextV2.capabilities(hasListeningStats = true, hasMonthlyRecap = true)
        assertTrue(full.containsAll(base))
        assertTrue("listening_stats" in full)
        assertTrue("monthly_recap" in full)
    }

    @Test
    fun `new screens and live channel entity are allowed values`() {
        assertTrue(AriaClientContextV2.SCREEN_VALUES.containsAll(listOf("explore", "live_channel", "unisound_room")))
        assertTrue("live_channel" in AriaClientContextV2.ENTITY_TYPES)
    }

    @Test
    fun `opening Aria from Explore keeps explore as the screen`() {
        AriaScreenContextHolder.updateDestination("explore")
        AriaScreenContextHolder.enterAriaDestination()

        assertEquals("explore", AriaScreenContextHolder.snapshot().screen)
        AriaScreenContextHolder.updateDestination(null)
    }

    @Test
    fun `live channel screen publishes the channel as visible entity`() {
        val source = Any()
        AriaScreenContextHolder.updateDestination("live_channel")
        AriaScreenContextHolder.update(
            "live_channel",
            AriaScreenContextHolder.VisibleEntity("live_channel", "chill", "Chill"),
            source
        )

        assertEquals(
            AriaScreenContextHolder.VisibleEntity("live_channel", "chill", "Chill"),
            AriaScreenContextHolder.snapshot().entity
        )
        AriaScreenContextHolder.updateDestination(null)
    }

    @Test
    fun `screen mapper knows Explore, the Live channel and the Unisound room`() {
        assertEquals("explore", AriaScreenMapper.fromDestinationId(R.id.exploreFragment))
        assertEquals("live_channel", AriaScreenMapper.fromDestinationId(R.id.liveChannelFragment))
        assertEquals("unisound_room", AriaScreenMapper.fromDestinationId(R.id.unisoundRoomFragment))
        assertEquals("search", AriaScreenMapper.fromDestinationId(R.id.searchFragment))
        assertTrue(AriaScreenMapper.isAriaDestination(R.id.ariaFragment))
    }
}
