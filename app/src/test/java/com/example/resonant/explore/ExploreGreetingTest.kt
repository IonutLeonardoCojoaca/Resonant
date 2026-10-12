package com.example.resonant.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExploreGreetingTest {

    @Test
    fun `four dayparts with name`() {
        assertEquals("Buenos días, Ionut", ExploreGreeting.of("morning", "Ionut"))
        assertEquals("Buenas tardes, Ionut", ExploreGreeting.of("afternoon", "Ionut"))
        assertEquals("Buenas noches, Ionut", ExploreGreeting.of("evening", "Ionut"))
        assertEquals("Buenas noches, Ionut", ExploreGreeting.of("night", "Ionut"))
    }

    @Test
    fun `without name only the greeting`() {
        assertEquals("Buenos días", ExploreGreeting.of("morning", null))
        assertEquals("Buenos días", ExploreGreeting.of("morning", "   "))
    }

    @Test
    fun `compound name keeps the first name`() {
        assertEquals("Buenas tardes, Ionut", ExploreGreeting.of("afternoon", "  Ionut Leonardo Cojoaca "))
    }

    @Test
    fun `unknown daypart yields null`() {
        assertNull(ExploreGreeting.of("weekend", "Ionut"))
        assertNull(ExploreGreeting.of("", "Ionut"))
    }
}
