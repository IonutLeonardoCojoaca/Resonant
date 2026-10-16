package com.example.resonant.data.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class StudioErrorsTest {

    private fun httpError(code: Int, body: String) = HttpException(
        Response.error<Any>(code, body.toResponseBody("application/json".toMediaType()))
    )

    @Test
    fun `conflict with studio code is recognised`() {
        val error = httpError(409, """{"code":"TRANSITION_STUDIO_MANAGED","message":"This transition is edited in Resonant Studio."}""")
        assertTrue(StudioErrors.isStudioManaged(error))
    }

    @Test
    fun `other conflicts and errors are not`() {
        assertFalse(StudioErrors.isStudioManaged(httpError(409, """{"error":"Transition has no preset assigned"}""")))
        assertFalse(StudioErrors.isStudioManaged(httpError(400, """{"code":"TRANSITION_STUDIO_MANAGED"}""")))
        assertFalse(StudioErrors.isStudioManaged(httpError(409, "no es json")))
        assertFalse(StudioErrors.isStudioManaged(IllegalStateException("boom")))
    }
}
