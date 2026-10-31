package com.example.resonant.explore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreModeResolverTest {

    @Test
    fun `layout loads in parallel with capabilities when v2 was on last time`() {
        assertTrue(ExploreModeResolver.shouldLoadLayoutEagerly(lastKnownV2 = true, hasCopy = false))
    }

    @Test
    fun `layout loads in parallel when a session copy exists`() {
        assertTrue(ExploreModeResolver.shouldLoadLayoutEagerly(lastKnownV2 = false, hasCopy = true))
    }

    @Test
    fun `first ever opening waits for capabilities before asking for the layout`() {
        assertFalse(ExploreModeResolver.shouldLoadLayoutEagerly(lastKnownV2 = false, hasCopy = false))
    }
}
