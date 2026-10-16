package com.example.resonant.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningStatsErrorsTest {

    @Test
    fun `feature disabled is detected from the error code`() {
        val body = """{ "code": "FEATURE_DISABLED", "message": "Listening stats are not enabled." }"""

        assertTrue(ListeningStatsErrors.isFeatureDisabled(404, body))
        assertEquals(StatsResult.Disabled, ListeningStatsErrors.fromResponse<String>(404, null, body))
    }

    @Test
    fun `other errors are failures`() {
        assertFalse(ListeningStatsErrors.isFeatureDisabled(404, """{ "code": "NOT_FOUND" }"""))
        assertFalse(ListeningStatsErrors.isFeatureDisabled(400, """{ "code": "FEATURE_DISABLED" }"""))
        assertEquals(StatsResult.Failed, ListeningStatsErrors.fromResponse<String>(500, null, "<html>"))
        assertEquals(StatsResult.Failed, ListeningStatsErrors.fromResponse<String>(200, null, null))
    }

    @Test
    fun `malformed bodies have no code`() {
        assertNull(ListeningStatsErrors.parseCode(null))
        assertNull(ListeningStatsErrors.parseCode("not json"))
        assertNull(ListeningStatsErrors.parseCode("[1,2]"))
        assertEquals("STATS_INVALID_PERIOD", ListeningStatsErrors.parseCode("""{"code":"STATS_INVALID_PERIOD"}"""))
    }

    @Test
    fun `successful body is ok`() {
        assertEquals(StatsResult.Ok("x"), ListeningStatsErrors.fromResponse(200, "x", null))
    }
}
