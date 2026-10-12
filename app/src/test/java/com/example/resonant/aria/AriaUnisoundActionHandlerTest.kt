package com.example.resonant.aria

import com.example.resonant.ui.viewmodels.AriaAction
import com.example.resonant.unisound.UnisoundControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AriaUnisoundActionHandlerTest {
    private val sent = mutableListOf<UnisoundControl>()
    private val events = mutableListOf<AriaActionTelemetryEvent>()
    private val claimed = mutableSetOf<String>()
    private var currentRoom: String? = "room-1"

    private val handler = AriaUnisoundActionHandler(
        gateway = object : AriaUnisoundGateway {
            override fun currentSessionId(): String? = currentRoom
            override fun control(control: UnisoundControl) {
                sent += control
            }
        },
        idempotency = { id -> id == null || claimed.add(id) },
        telemetry = { events += it }
    )

    private fun action(command: String, room: String? = "room-1", id: String? = "a1") = AriaAction(
        type = "controlar_reproduccion",
        actionId = id,
        executionStatus = ARIA_PENDING_CLIENT_STATUS,
        clientSide = true,
        playbackCommand = command,
        unisoundSessionId = room
    )

    @Test
    fun `pause in the current room goes to the room`() {
        assertTrue(handler.handle(action("pause")))

        assertEquals(listOf(UnisoundControl.PAUSE), sent)
    }

    @Test
    fun `order for another room is dropped`() {
        currentRoom = "room-2"

        assertTrue(handler.handle(action("next")))

        assertTrue(sent.isEmpty())
        assertEquals(AriaUnisoundActionHandler.REASON_SESSION_MISMATCH, events.single().reason)
    }

    @Test
    fun `order after leaving the room is dropped`() {
        currentRoom = null

        assertTrue(handler.handle(action("play")))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `repeated action id is sent once`() {
        handler.handle(action("next", id = "same"))
        handler.handle(action("next", id = "same"))

        assertEquals(listOf(UnisoundControl.NEXT), sent)
    }

    @Test
    fun `orders without a room keep the normal path`() {
        assertFalse(handler.handle(action("pause", room = null)))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `client context carries the room only inside one`() {
        assertEquals(mapOf("unisound_session_id" to "room-1"), AriaUnisoundContext.sessionFields("room-1"))
        assertNull(AriaUnisoundContext.sessionFields(null))
        assertNull(AriaUnisoundContext.sessionFields(" "))
    }
}
