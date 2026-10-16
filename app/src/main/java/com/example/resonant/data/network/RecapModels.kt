package com.example.resonant.data.network

import com.google.gson.JsonElement

/** Elemento de `GET api/me/recaps` (resumen mensual). */
data class RecapSummaryDTO(
    val periodKey: String = "",
    val seen: Boolean = false,
    /** ISO-8601 UTC. */
    val generatedAt: String? = null
)

/**
 * `GET api/me/recaps/{periodKey}`. Las tarjetas se dejan como [JsonElement] y las interpreta
 * `recap.RecapCardParser`, que descarta los tipos desconocidos o rotos.
 */
data class RecapDetailDTO(
    val periodKey: String = "",
    val year: Int = 0,
    val month: Int = 0,
    val monthName: String? = null,
    val seen: Boolean = false,
    val generatedAt: String? = null,
    val ariaText: String? = null,
    /** `aria` o `template`. */
    val ariaOrigin: String? = null,
    val playlistId: String? = null,
    val cards: List<JsonElement>? = null
)

/** `POST api/me/recaps/{periodKey}/playlist`: 201 creada, 200 la ya existente. */
data class RecapPlaylistDTO(
    val playlistId: String = "",
    val name: String = "",
    val songCount: Int = 0
)
