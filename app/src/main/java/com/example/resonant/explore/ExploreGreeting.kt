package com.example.resonant.explore

/** Header greeting for Explore v2, from the server `daypart` and the user's stored name. */
object ExploreGreeting {

    /** Null for an unknown daypart, so the header keeps its default title. */
    fun of(daypart: String, userName: String?): String? {
        val greeting = when (daypart) {
            "morning" -> "Buenos días"
            "afternoon" -> "Buenas tardes"
            "evening", "night" -> "Buenas noches"
            else -> return null
        }
        val firstName = userName?.trim()?.split(WHITESPACE)?.firstOrNull()?.takeIf(String::isNotBlank)
        return if (firstName == null) greeting else "$greeting, $firstName"
    }

    private val WHITESPACE = Regex("\\s+")
}
