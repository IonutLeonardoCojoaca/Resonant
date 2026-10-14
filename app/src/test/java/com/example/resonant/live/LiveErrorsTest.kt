package com.example.resonant.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveErrorsTest {
    @Test
    fun `feature disabled hides live`() {
        assertEquals(LiveFailure.DISABLED, LiveErrors.classify(404, LiveErrors.FEATURE_DISABLED))
        assertEquals(LiveErrors.MESSAGE_DISABLED, LiveErrors.message(LiveFailure.DISABLED))
    }

    @Test
    fun `unknown channel is not found`() {
        assertEquals(LiveFailure.NOT_FOUND, LiveErrors.classify(404, LiveErrors.CHANNEL_NOT_FOUND))
        assertEquals(LiveErrors.MESSAGE_NOT_FOUND, LiveErrors.message(LiveFailure.NOT_FOUND))
    }

    @Test
    fun `404 without code counts as disabled`() {
        assertEquals(LiveFailure.DISABLED, LiveErrors.classify(404, null))
    }

    @Test
    fun `rate limit, server errors and network are transient and silent`() {
        assertEquals(LiveFailure.TRANSIENT, LiveErrors.classify(429, null))
        assertEquals(LiveFailure.TRANSIENT, LiveErrors.classify(503, null))
        assertEquals(LiveFailure.TRANSIENT, LiveErrors.classify(null, null))
        assertNull(LiveErrors.message(LiveFailure.TRANSIENT))
    }

    @Test
    fun `gap message never shows negative seconds`() {
        assertEquals("Esta canción no está disponible; la emisión sigue en 42 s", LiveErrors.gapMessage(42))
        assertEquals("Esta canción no está disponible; la emisión sigue en 0 s", LiveErrors.gapMessage(-3))
    }
}
