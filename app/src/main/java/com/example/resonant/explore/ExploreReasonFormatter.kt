package com.example.resonant.explore

import com.example.resonant.data.network.ExploreReasonDTO
import com.google.gson.JsonElement

/**
 * Turns the structured `reason { code, args }` of an Explore section into a Spanish subtitle.
 * Unknown codes or incomplete args yield null: the section then shows only its title.
 */
object ExploreReasonFormatter {

    fun format(reason: ExploreReasonDTO?, title: String): String? {
        val args = reason?.args.orEmpty()
        return when (reason?.code) {
            "because_artist" -> args.string("artistName")?.let { artist ->
                if (title.contains(artist, ignoreCase = true)) "Basado en lo que más escuchas"
                else "Porque escuchas $artist"
            }
            "daypart" -> DAYPART_MOMENTS[args.string("daypart")]?.let { moment ->
                val weekend = if (args.boolean("isWeekend")) " de fin de semana" else ""
                "Elegido para $moment$weekend"
            }
            "forgotten" -> "Canciones que hace tiempo que no escuchas"
            "new_from_followed_artist" -> "De artistas que sigues o escuchas"
            "genre_affinity" -> "Según lo que más escuchas"
            else -> null
        }
    }

    private fun Map<String, JsonElement>.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf(String::isNotBlank)

    private fun Map<String, JsonElement>.boolean(key: String): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true

    private val DAYPART_MOMENTS = mapOf(
        "morning" to "esta mañana",
        "afternoon" to "esta tarde",
        "evening" to "esta noche",
        "night" to "esta noche"
    )
}
