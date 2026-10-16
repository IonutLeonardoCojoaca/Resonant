package com.example.resonant.data.network

import com.google.gson.JsonParser
import retrofit2.HttpException

/** Errores del backend relacionados con Resonant Studio. */
object StudioErrors {

    /** El backend rechaza editar con el editor simple una transición que tiene documento de Studio (409). */
    const val STUDIO_MANAGED = "TRANSITION_STUDIO_MANAGED"

    const val STUDIO_MANAGED_MESSAGE = "Esta transición se edita en Resonant Studio"

    fun isStudioManaged(error: Throwable): Boolean {
        val http = error as? HttpException ?: return false
        if (http.code() != 409) return false
        val body = runCatching { http.response()?.errorBody()?.string() }.getOrNull() ?: return false
        return isStudioManagedBody(body)
    }

    fun isStudioManagedBody(body: String): Boolean = runCatching {
        JsonParser.parseString(body).asJsonObject.get("code")?.asString == STUDIO_MANAGED
    }.getOrDefault(false)
}
