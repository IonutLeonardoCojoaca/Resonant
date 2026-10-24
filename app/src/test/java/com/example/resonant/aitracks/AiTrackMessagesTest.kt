package com.example.resonant.aitracks

import com.example.resonant.data.network.AiTrackDTO
import com.example.resonant.managers.AiTrackException
import com.example.resonant.managers.AiTrackMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTrackMessagesTest {

    @Test
    fun `each generation error has its own text and unknown codes fall back`() {
        val codes = listOf(
            "LYRIA_PROMPT_FILTERED", "LYRIA_QUOTA_EXCEEDED", "LYRIA_AUTH_FAILED",
            "LYRIA_TIMEOUT", "LYRIA_EMPTY_AUDIO", "IMPORT_FAILED"
        )
        val texts = codes.map { AiTrackMessages.forGenerationError(it) }
        assertEquals(codes.size, texts.toSet().size)
        val fallback = AiTrackMessages.forGenerationError("LYRIA_UNAVAILABLE")
        assertEquals(fallback, AiTrackMessages.forGenerationError(null))
        assertFalse(texts.contains(fallback))
    }

    @Test
    fun `request errors use the code and the invalid field`() {
        assertTrue(AiTrackMessages.forRequestError(AiTrackException("AI_TRACK_DAILY_LIMIT")).contains("límite"))
        val bpm = AiTrackMessages.forRequestError(AiTrackException("AI_TRACK_INVALID", field = "bpm"))
        val key = AiTrackMessages.forRequestError(AiTrackException("AI_TRACK_INVALID", field = "key"))
        assertTrue(bpm.contains("BPM"))
        assertNotEquals(bpm, key)
        assertEquals(
            AiTrackMessages.forRequestError(AiTrackException("HTTP_500")),
            AiTrackMessages.forRequestError(AiTrackException("OTRO"))
        )
    }

    @Test
    fun `open states are queued generating and importing`() {
        assertTrue(AiTrackDTO(status = "queued").isOpen)
        assertTrue(AiTrackDTO(status = "generating").isOpen)
        assertTrue(AiTrackDTO(status = "importing").isOpen)
        assertFalse(AiTrackDTO(status = "completed").isOpen)
        assertFalse(AiTrackDTO(status = "failed").isOpen)
        assertTrue(AiTrackDTO(status = "failed").isFailed)
    }
}
