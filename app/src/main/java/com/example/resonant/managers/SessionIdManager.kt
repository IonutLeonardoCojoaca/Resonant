package com.example.resonant.managers

import android.content.Context
import android.util.Log
import java.util.UUID

object SessionIdManager {
    private const val PREFS_NAME    = "aria_session"
    private const val KEY_ID        = "session_id"
    private const val KEY_LAST_SEEN = "last_activity_ms"
    private const val TIMEOUT_MS    = 20 * 60 * 1000L

    fun getOrCreateSessionId(context: Context): String {
        val prefs     = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSeen  = prefs.getLong(KEY_LAST_SEEN, 0L)
        val now       = System.currentTimeMillis()
        val isExpired = (now - lastSeen) > TIMEOUT_MS
        val existing  = prefs.getString(KEY_ID, null)

        return if (existing == null || isExpired) {
            val newId = UUID.randomUUID().toString()
            prefs.edit()
                .putString(KEY_ID, newId)
                .putLong(KEY_LAST_SEEN, now)
                .apply()
            Log.d("SessionIdManager",
                "Nueva sesión: $newId " +
                "(${if (existing == null) "primera vez" else "timeout"})")
            newId
        } else {
            prefs.edit().putLong(KEY_LAST_SEEN, now).apply()
            existing
        }
    }

    fun clearSession(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ID)
            .remove(KEY_LAST_SEEN)
            .apply()
        Log.d("SessionIdManager", "Sesión borrada manualmente")
    }
}
