package com.example.resonant.unisound

import com.example.resonant.unisound.UnisoundFixtures.room
import org.junit.Assert.assertEquals
import org.junit.Test

class UnisoundLinkDecisionTest {
    @Test
    fun `feature off never joins`() {
        assertEquals(UnisoundLinkAction.Unavailable, UnisoundLinkDecision.decide("K7M2QX", false, null))
    }

    @Test
    fun `outside a room asks to join`() {
        assertEquals(UnisoundLinkAction.ConfirmJoin("K7M2QX"), UnisoundLinkDecision.decide("K7M2QX", true, null))
    }

    @Test
    fun `link to the current room just opens it`() {
        assertEquals(UnisoundLinkAction.OpenCurrentRoom, UnisoundLinkDecision.decide("K7M2QX", true, room()))
    }

    @Test
    fun `link to another room asks to switch`() {
        assertEquals(UnisoundLinkAction.ConfirmSwitch("ABCDEF"), UnisoundLinkDecision.decide("ABCDEF", true, room()))
    }
}
