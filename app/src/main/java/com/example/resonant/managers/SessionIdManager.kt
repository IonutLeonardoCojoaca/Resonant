package com.example.resonant.managers

import android.content.Context
import android.util.Log
import java.util.UUID

/** Keeps one Aria session for the whole conversation, including clarification turns. */
object SessionIdManager {
    private const val PREFS_NAME = "aria_session"
    private const val KEY_ID = "session_id"

    @Synchronized
    fun getOrCreateSessionId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }

        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_ID, newId).apply()
        Log.d("SessionIdManager", "Nueva sesión: $newId")
        return newId
    }

    @Synchronized
    fun clearSession(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ID)
            .apply()
        Log.d("SessionIdManager", "Sesión borrada manualmente")
    }
}
