package com.example.resonant.data.network

import android.content.Context
import androidx.core.content.edit

/**
 * Prevents an older backend from receiving the same unsupported v2 request on
 * every screen open or song change. Unsupported endpoints are probed again
 * after a short cooldown, so deployment does not require an app update.
 */
object V2EndpointAvailability {
    const val FEATURE_PLAYBACK_BATCH = "playback_batch"
    const val FEATURE_HOME = "home"
    const val FEATURE_QOE = "qoe"
    const val FEATURE_CONNECT = "connect"
    const val FEATURE_RADIO = "radio"

    fun shouldTry(context: Context, feature: String): Boolean {
        val disabledUntil = preferences(context).getLong(key(feature), 0L)
        return System.currentTimeMillis() >= disabledUntil
    }

    fun markUnsupported(context: Context, feature: String) {
        preferences(context).edit {
            putLong(key(feature), System.currentTimeMillis() + UNSUPPORTED_COOLDOWN_MS)
        }
    }

    fun markAvailable(context: Context, feature: String) {
        preferences(context).edit { remove(key(feature)) }
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun key(feature: String) = "unsupported_until:$feature"

    private const val PREFERENCES = "v2_endpoint_availability"
    private const val UNSUPPORTED_COOLDOWN_MS = 2L * 60L * 1000L
}
