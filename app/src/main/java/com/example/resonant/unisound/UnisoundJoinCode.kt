package com.example.resonant.unisound

/** Join code rules shared by manual entry and `https://resonantapp.ddns.net/u/{code}` links. */
object UnisoundJoinCode {
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH = 6
    const val LINK_HOST = "resonantapp.ddns.net"

    fun normalize(input: String?): String =
        input.orEmpty().filterNot(Char::isWhitespace).uppercase()

    fun isValid(code: String): Boolean =
        code.length == LENGTH && code.all { it in ALPHABET }

    /** Normalized code, or null when it can never be valid (so no request is sent). */
    fun normalizedOrNull(input: String?): String? = normalize(input).takeIf(::isValid)

    /** Code from a `/u/{code}` link (trailing slash tolerated), or null for any other path. */
    fun fromLink(host: String?, path: String?): String? {
        if (!host.equals(LINK_HOST, ignoreCase = true)) return null
        val segments = path.orEmpty().split('/').filter(String::isNotEmpty)
        if (segments.size != 2 || segments[0] != "u") return null
        return normalizedOrNull(segments[1])
    }
}
