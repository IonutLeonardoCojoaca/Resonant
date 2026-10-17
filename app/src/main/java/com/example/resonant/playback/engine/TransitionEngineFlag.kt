package com.example.resonant.playback.engine

import android.content.Context

/**
 * Si el motor de salida único está activo (`features.transitionEngineV2` de `GET api/capabilities`). Se guarda en
 * preferencias cuando llega una respuesta del servidor y el servicio lo lee al crear sus players: un cambio en el
 * servidor se aplica en el siguiente arranque del servicio, nunca a mitad de una canción.
 */
object TransitionEngineFlag {
    private const val PREFS = "resonant_playback_engine"
    private const val KEY = "transition_engine_v2"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun store(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
    }
}
