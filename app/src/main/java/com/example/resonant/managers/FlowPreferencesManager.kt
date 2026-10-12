package com.example.resonant.managers

import android.content.Context
import android.util.Log
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.FlowPreferencesDTO
import com.example.resonant.playback.flow.FlowPreferencesLocal
import com.example.resonant.playback.flow.FlowPreferencesRemote
import com.example.resonant.playback.flow.FlowPreferencesSync
import com.example.resonant.playback.flow.LocalFlowPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Preferencias de Flow ("Continuar reproduciendo" y nivel de descubrimiento): DataStore como
 * fuente inmediata y, con la capacidad `flowV2`, sincronizadas con el servidor.
 */
object FlowPreferencesManager {
    private const val TAG = "FlowPreferences"

    suspend fun setEnabled(context: Context, enabled: Boolean) {
        val settings = SettingsManager(context.applicationContext)
        update(context, enabled, settings.flowDiscoveryLevelFlow.first())
    }

    suspend fun setDiscoveryLevel(context: Context, level: Int) {
        val settings = SettingsManager(context.applicationContext)
        update(context, settings.autoplayRadioEnabledFlow.first(), level)
    }

    /** Sube cambios pendientes o adopta los del servidor. No hace nada sin `flowV2`. */
    suspend fun syncOnStart(context: Context) = withContext(Dispatchers.IO) {
        CapabilitiesManager.refresh(context)
        if (!CapabilitiesManager.isFlowV2Enabled()) return@withContext
        runCatching { sync(context).syncOnStart() }
            .onFailure { Log.w(TAG, "No se pudieron sincronizar las preferencias de Flow", it) }
    }

    private suspend fun update(context: Context, enabled: Boolean, level: Int) = withContext(Dispatchers.IO) {
        sync(context).update(enabled, level, syncWithServer = CapabilitiesManager.isFlowV2Enabled())
    }

    private fun sync(context: Context): FlowPreferencesSync {
        val settings = SettingsManager(context.applicationContext)
        val service = ApiClient.getRadioService(context.applicationContext)
        return FlowPreferencesSync(
            local = object : FlowPreferencesLocal {
                override suspend fun read() = LocalFlowPreferences(
                    enabled = settings.autoplayRadioEnabledFlow.first(),
                    discoveryLevel = settings.flowDiscoveryLevelFlow.first(),
                    pendingSync = settings.flowPreferencesPendingSyncFlow.first()
                )

                override suspend fun write(preferences: LocalFlowPreferences) =
                    settings.setFlowPreferences(preferences.enabled, preferences.discoveryLevel, preferences.pendingSync)
            },
            remote = object : FlowPreferencesRemote {
                override suspend fun fetch(): Pair<Boolean, Int>? = runCatching {
                    val body = service.getFlowPreferences().takeIf { it.isSuccessful }?.body()
                    body?.let { (it.enabled ?: true) to (it.discoveryLevel ?: 1) }
                }.getOrNull()

                override suspend fun push(enabled: Boolean, discoveryLevel: Int): Boolean = runCatching {
                    service.putFlowPreferences(FlowPreferencesDTO(enabled, discoveryLevel)).isSuccessful
                }.getOrDefault(false)
            }
        )
    }
}
