package com.example.resonant.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveSyncLogTest {
    @Test
    fun `writes at most one line every ten seconds`() {
        val log = LiveSyncLog()

        assertEquals("slug=rock block=b1 pos=1000 driftMs=-120", log.lineOrNull("rock", "b1", 1_000L, -120L, 0L))
        assertNull(log.lineOrNull("rock", "b1", 5_000L, 40L, 9_999L))
        assertEquals("slug=rock block=b1 pos=11000 driftMs=35", log.lineOrNull("rock", "b1", 11_000L, 35L, 10_000L))
    }

    @Test
    fun `reset allows the next line right away`() {
        val log = LiveSyncLog()
        log.lineOrNull("rock", "b1", 0L, 0L, 0L)

        log.reset()

        assertEquals("slug=rock block=b2 pos=0 driftMs=0", log.lineOrNull("rock", "b2", 0L, 0L, 1L))
    }
}
