package com.example.resonant.data.network

import com.google.gson.Gson
import retrofit2.Response

/**
 * Reads the backend error envelope (`ApiErrorDTO`) from a failed response.
 * Returns null when the body is empty, is not JSON or carries no stable `code`.
 */
object ApiErrorParser {
    private val gson = Gson()

    fun parse(response: Response<*>): ApiErrorDTO? =
        parse(runCatching { response.errorBody()?.string() }.getOrNull())

    fun parse(rawBody: String?): ApiErrorDTO? {
        if (rawBody.isNullOrBlank()) return null
        val dto = runCatching { gson.fromJson(rawBody, ApiErrorDTO::class.java) }.getOrNull()
        // Gson bypasses Kotlin defaults, so a missing "code" arrives as null.
        val code: String? = dto?.code
        return if (code.isNullOrBlank()) null else dto
    }
}
