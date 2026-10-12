package com.example.resonant.unisound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnisoundJoinCodeTest {
    @Test
    fun `normalizes lowercase and spaces`() {
        assertEquals("K7M2QX", UnisoundJoinCode.normalizedOrNull(" k7m 2qx "))
    }

    @Test
    fun `rejects wrong length`() {
        assertNull(UnisoundJoinCode.normalizedOrNull("K7M2Q"))
        assertNull(UnisoundJoinCode.normalizedOrNull("K7M2QXA"))
    }

    @Test
    fun `rejects ambiguous characters`() {
        listOf("K7M2Q0", "K7M2QO", "K7M2Q1", "K7M2QI", "K7M2QL").forEach {
            assertFalse(it, UnisoundJoinCode.isValid(it))
        }
        assertTrue(UnisoundJoinCode.isValid("ABCDEF"))
    }

    @Test
    fun `extracts code from invite link`() {
        assertEquals("K7M2QX", UnisoundJoinCode.fromLink("resonantapp.ddns.net", "/u/K7M2QX"))
        assertEquals("K7M2QX", UnisoundJoinCode.fromLink("resonantapp.ddns.net", "/u/k7m2qx/"))
    }

    @Test
    fun `ignores other links`() {
        assertNull(UnisoundJoinCode.fromLink("resonantapp.ddns.net", "/song/9b31"))
        assertNull(UnisoundJoinCode.fromLink("resonantapp.ddns.net", "/u/K7M2QX/extra"))
        assertNull(UnisoundJoinCode.fromLink("evil.example", "/u/K7M2QX"))
        assertNull(UnisoundJoinCode.fromLink("resonantapp.ddns.net", "/u/K7M2Q1"))
    }
}
