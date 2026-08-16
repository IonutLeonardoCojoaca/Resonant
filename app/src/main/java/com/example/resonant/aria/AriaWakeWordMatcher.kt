package com.example.resonant.aria

import java.text.Normalizer
import java.util.Locale

internal object AriaWakeWordMatcher {
    private val patterns = listOf(
        Regex("\\b(?:hola|ola|oye|hello|hey)\\s+(?:aria|area|haria)\\b")
    )

    fun matchesAny(transcripts: List<String>): Boolean = transcripts.any(::matches)

    fun matches(transcript: String): Boolean {
        val normalized = Normalizer.normalize(transcript, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .replace("[^a-z0-9 ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
        return patterns.any { it.containsMatchIn(normalized) }
    }
}
