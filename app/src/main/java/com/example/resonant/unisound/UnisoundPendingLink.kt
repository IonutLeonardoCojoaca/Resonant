package com.example.resonant.unisound

/** Invite code opened before logging in; handled once the user reaches the main screen. */
object UnisoundPendingLink {
    @Volatile
    private var code: String? = null

    fun store(code: String) {
        this.code = code
    }

    fun consume(): String? = code.also { code = null }
}
