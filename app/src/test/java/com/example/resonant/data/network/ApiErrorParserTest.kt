package com.example.resonant.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiErrorParserTest {
    @Test
    fun `parses backend error envelope`() {
        val error = ApiErrorParser.parse(
            """
            { "code": "UNISOUND_SESSION_FULL", "message": "The Unisound session is full.",
              "correlationId": "0HN1", "retryable": false, "retryAfterSeconds": null }
            """.trimIndent()
        )

        assertEquals("UNISOUND_SESSION_FULL", error?.code)
        assertEquals("0HN1", error?.correlationId)
    }

    @Test
    fun `parses retry hints`() {
        val error = ApiErrorParser.parse(
            """{ "code": "RATE_LIMITED", "retryable": true, "retryAfterSeconds": 30 }"""
        )

        assertTrue(error?.retryable == true)
        assertEquals(30, error?.retryAfterSeconds)
    }

    @Test
    fun `empty body yields null`() {
        assertNull(ApiErrorParser.parse(""))
        assertNull(ApiErrorParser.parse(null as String?))
    }

    @Test
    fun `html body yields null`() {
        assertNull(ApiErrorParser.parse("<html><body>502 Bad Gateway</body></html>"))
    }

    @Test
    fun `json without code yields null`() {
        assertNull(ApiErrorParser.parse("""{ "message": "boom" }"""))
    }
}
