package com.example.resonant.playback.flow

/** Preferencias de Flow tal y como las guarda el móvil. */
data class LocalFlowPreferences(
    val enabled: Boolean,
    val discoveryLevel: Int,
    val pendingSync: Boolean
)

/** Almacén local (DataStore en la app; en memoria en los tests). */
interface FlowPreferencesLocal {
    suspend fun read(): LocalFlowPreferences
    suspend fun write(preferences: LocalFlowPreferences)
}

/** `GET/PUT api/me/flow-preferences`. `null`/`false` significa que la llamada falló. */
interface FlowPreferencesRemote {
    suspend fun fetch(): Pair<Boolean, Int>?
    suspend fun push(enabled: Boolean, discoveryLevel: Int): Boolean
}

/**
 * El móvil aplica cada cambio al momento y lo sube en segundo plano; si la subida falla
 * queda pendiente y se reintenta al arrancar. Sin cambios pendientes, al arrancar manda
 * el servidor (último PUT de cualquier dispositivo).
 */
class FlowPreferencesSync(
    private val local: FlowPreferencesLocal,
    private val remote: FlowPreferencesRemote
) {
    suspend fun syncOnStart() {
        val current = local.read()
        if (current.pendingSync) {
            if (remote.push(current.enabled, current.discoveryLevel)) {
                local.write(current.copy(pendingSync = false))
            }
            return
        }
        val (enabled, level) = remote.fetch() ?: return
        local.write(LocalFlowPreferences(enabled, clampLevel(level), pendingSync = false))
    }

    suspend fun update(enabled: Boolean, discoveryLevel: Int, syncWithServer: Boolean) {
        val level = clampLevel(discoveryLevel)
        local.write(LocalFlowPreferences(enabled, level, pendingSync = syncWithServer))
        if (syncWithServer && remote.push(enabled, level)) {
            local.write(LocalFlowPreferences(enabled, level, pendingSync = false))
        }
    }

    companion object {
        fun clampLevel(level: Int): Int = level.coerceIn(0, 2)
    }
}
