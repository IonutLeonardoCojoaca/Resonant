package com.example.resonant.explore

import android.content.Context

/**
 * Último valor conocido de `features.exploreV2` (de `GET api/capabilities`), guardado en preferencias cuando
 * llega una respuesta real del servidor. Permite a Explorar pedir el layout v2 **a la vez** que revalida las
 * capacidades también en arranque frío, en vez de esperar dos viajes de red seguidos antes de pintar nada.
 * Espejo de `TransitionEngineFlag`.
 */
object ExploreV2Flag {
    private const val PREFS = "resonant_explore"
    private const val KEY = "explore_v2"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun store(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
    }
}
