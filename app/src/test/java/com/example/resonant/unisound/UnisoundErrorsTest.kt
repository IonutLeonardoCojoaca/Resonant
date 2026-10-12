package com.example.resonant.unisound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnisoundErrorsTest {
    @Test
    fun `reads plain hub exception message`() {
        assertEquals("UNISOUND_NOT_ALLOWED", UnisoundErrors.codeOf(RuntimeException("UNISOUND_NOT_ALLOWED")))
    }

    @Test
    fun `reads wrapped hub exception message`() {
        val error = RuntimeException(
            "wrapper",
            RuntimeException("An unexpected error occurred invoking 'Play' on the server. HubException: UNISOUND_STALE_REVISION")
        )

        assertEquals("UNISOUND_STALE_REVISION", UnisoundErrors.codeOf(error))
    }

    @Test
    fun `reads rest rate limit code`() {
        assertEquals("RATE_LIMITED", UnisoundErrors.codeOf(RuntimeException("RATE_LIMITED")))
        assertNull(UnisoundErrors.codeOf(RuntimeException("timeout")))
    }

    @Test
    fun `classifies retryable and room ending codes`() {
        assertTrue(UnisoundErrors.isRetryable(UnisoundErrors.UNAVAILABLE))
        assertFalse(UnisoundErrors.isRetryable(UnisoundErrors.STALE_REVISION))
        listOf(UnisoundErrors.NOT_MEMBER, UnisoundErrors.SESSION_ENDED, UnisoundErrors.NOT_AVAILABLE)
            .forEach { assertTrue(it, UnisoundErrors.endsRoom(it)) }
        assertFalse(UnisoundErrors.endsRoom(UnisoundErrors.NOT_ALLOWED))
    }

    @Test
    fun `not allowed depends on context`() {
        assertEquals(
            "El anfitrión controla la reproducción",
            UnisoundErrors.message(UnisoundErrors.NOT_ALLOWED, UnisoundErrorContext.COMMAND)
        )
        assertEquals(
            "Solo el anfitrión puede hacerlo",
            UnisoundErrors.message(UnisoundErrors.NOT_ALLOWED, UnisoundErrorContext.HOST_ACTION)
        )
    }

    @Test
    fun `join messages do not reveal whether a code existed`() {
        assertEquals("Código no válido", UnisoundErrors.message(UnisoundErrors.CODE_INVALID, UnisoundErrorContext.JOIN))
        assertEquals("La sala está llena", UnisoundErrors.message(UnisoundErrors.SESSION_FULL, UnisoundErrorContext.JOIN))
        assertEquals(
            "Demasiados intentos. Espera un momento",
            UnisoundErrors.message(UnisoundErrors.RATE_LIMITED, UnisoundErrorContext.JOIN)
        )
    }
}
