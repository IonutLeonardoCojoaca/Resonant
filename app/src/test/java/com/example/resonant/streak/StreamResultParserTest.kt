package com.example.resonant.streak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamResultParserTest {

    @Test
    fun `empty body or empty object means no event`() {
        assertNull(StreamResultParser.parse(null))
        assertNull(StreamResultParser.parse(""))
        assertNull(StreamResultParser.parse("{}"))
    }

    @Test
    fun `qualified today carries its days`() {
        val event = StreamResultParser.parse("""{ "streakEvent": { "type": "qualified_today", "days": 4 } }""")

        assertEquals(StreakEvent.QualifiedToday(4), event)
    }

    @Test
    fun `milestone carries its days`() {
        val event = StreamResultParser.parse("""{ "streakEvent": { "type": "milestone", "days": 7 } }""")

        assertEquals(StreakEvent.Milestone(7), event)
    }

    @Test
    fun `unknown type means no event`() {
        assertNull(StreamResultParser.parse("""{ "streakEvent": { "type": "weekly_bonus", "days": 3 } }"""))
    }

    @Test
    fun `non positive days means no event`() {
        assertNull(StreamResultParser.parse("""{ "streakEvent": { "type": "milestone", "days": 0 } }"""))
        assertNull(StreamResultParser.parse("""{ "streakEvent": { "type": "qualified_today" } }"""))
    }

    @Test
    fun `broken json means no event without throwing`() {
        assertNull(StreamResultParser.parse("""{ "streakEvent": """))
        assertNull(StreamResultParser.parse("not json"))
    }
}
