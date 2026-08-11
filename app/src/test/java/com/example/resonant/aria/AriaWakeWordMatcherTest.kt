package com.example.resonant.aria

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AriaWakeWordMatcherTest {
    @Test
    fun `detects supported wake word transcriptions`() {
        listOf(
            "Hola Aria",
            "hola, área",
            "Oye Aria por favor",
            "ola haría",
            "Hey Aria",
            "Hello Area"
        ).forEach { transcript ->
            assertTrue(transcript, AriaWakeWordMatcher.matches(transcript))
        }
    }

    @Test
    fun `does not trigger when Aria is mentioned without the wake phrase`() {
        listOf(
            "La canción de Aria",
            "Hola María",
            "área de reproducción",
            "oye esta canción"
        ).forEach { transcript ->
            assertFalse(transcript, AriaWakeWordMatcher.matches(transcript))
        }
    }
}
