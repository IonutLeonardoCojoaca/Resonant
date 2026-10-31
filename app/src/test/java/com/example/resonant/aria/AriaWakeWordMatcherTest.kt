package com.example.resonant.aria

import org.junit.Assert.assertEquals
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
            "Hello Area",
            "oye Arias ponme algo de Wisin y Yandel",
            "Aria ponme algo de rock"
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

    @Test
    fun `strips the leading wake phrase before sending`() {
        assertEquals("ponme algo de Wisin y Yandel", AriaWakeWordMatcher.stripWakePhrase("Oye Arias, ponme algo de Wisin y Yandel"))
        assertEquals("ponme algo melancólico", AriaWakeWordMatcher.stripWakePhrase("área ponme algo melancólico"))
        assertEquals("área de servicio, pon algo", AriaWakeWordMatcher.stripWakePhrase("área de servicio, pon algo"))
        assertEquals("Hola Aria", AriaWakeWordMatcher.stripWakePhrase("Hola Aria"))
        assertEquals("pon rock", AriaWakeWordMatcher.stripWakePhrase("pon rock"))
    }
}
