package com.example.resonant.aria

import java.text.Normalizer
import java.util.Locale

internal object AriaWakeWordMatcher {
    /** "Aria" as the recognizer usually hears it ("Arias", "área", "haría"). */
    private const val NAME = "(?:aria|arias|area|areas|haria|harias)"

    private val patterns = listOf(
        Regex("\\b(?:hola|ola|oye|hello|hey)\\s+$NAME\\b"),
        // "Aria, ponme..." said straight away; "la canción de Aria" must not trigger.
        Regex("^(?:aria|arias)\\s+\\S+")
    )

    /** Leading wake phrase of a transcript, e.g. "Oye Arias, " in "Oye Arias, ponme rock". */
    private val leadingWakePhrase = Regex(
        "^\\s*(?:(?:hola|ola|oye|hello|hey)[\\s,]+)?(?:aria|arias|área|areas|area|haría|harías|haria|harias)\\b[\\s,.:;!¡?¿]*",
        RegexOption.IGNORE_CASE
    )

    fun matchesAny(transcripts: List<String>): Boolean = transcripts.any(::matches)

    fun matches(transcript: String): Boolean {
        val normalized = normalize(transcript)
        return patterns.any { it.containsMatchIn(normalized) }
    }

    /**
     * Removes a leading wake phrase so the quick sheet never sends it as part of the request.
     * "área de servicio…" is kept: there "área" is a word, not the name.
     */
    fun stripWakePhrase(transcript: String): String {
        val match = leadingWakePhrase.find(transcript) ?: return transcript.trim()
        val rest = transcript.substring(match.range.last + 1).trim()
        val firstWord = rest.substringBefore(' ').trim(',', '.').lowercase(Locale.ROOT)
        return if (rest.isEmpty() || firstWord in COMPLEMENTS) transcript.trim() else rest
    }

    private fun normalize(transcript: String): String =
        Normalizer.normalize(transcript, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .replace("[^a-z0-9 ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()

    private val COMPLEMENTS = setOf("de", "del", "en", "con", "para", "que")
}
