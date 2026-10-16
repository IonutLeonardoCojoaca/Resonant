package com.example.resonant.managers

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Preferencias de descargas automáticas de un usuario. No hay interruptor general de activación:
 * suscribirse a un artista basta. [paused] solo lo cambia el usuario desde ajustes.
 */
data class AutoDownloadSettings(
    val paused: Boolean = false,
    val wifiOnly: Boolean = true,
    val requiresCharging: Boolean = false,
    val maxStorageMb: Int = DEFAULT_MAX_STORAGE_MB,
    /** 0 = no borrar nunca. */
    val keepDays: Int = 0
) {
    val maxStorageBytes: Long get() = maxStorageMb.toLong() * 1024L * 1024L

    companion object {
        const val DEFAULT_MAX_STORAGE_MB = 2048
    }
}

/** Claves con sufijo `userId`: dos cuentas en el mismo dispositivo no comparten valores. */
object AutoDownloadPreferenceKeys {
    fun paused(userId: String) = booleanPreferencesKey("paused_$userId")
    fun wifiOnly(userId: String) = booleanPreferencesKey("wifiOnly_$userId")
    fun requiresCharging(userId: String) = booleanPreferencesKey("requiresCharging_$userId")
    fun maxStorageMb(userId: String) = intPreferencesKey("maxStorageMb_$userId")
    fun keepDays(userId: String) = intPreferencesKey("keepDays_$userId")

    /** Valores del usuario, con los defaults de [AutoDownloadSettings] para lo que no haya guardado. */
    fun read(preferences: Preferences, userId: String): AutoDownloadSettings {
        val defaults = AutoDownloadSettings()
        return AutoDownloadSettings(
            paused = preferences[paused(userId)] ?: defaults.paused,
            wifiOnly = preferences[wifiOnly(userId)] ?: defaults.wifiOnly,
            requiresCharging = preferences[requiresCharging(userId)] ?: defaults.requiresCharging,
            maxStorageMb = (preferences[maxStorageMb(userId)] ?: defaults.maxStorageMb).coerceAtLeast(MIN_STORAGE_MB),
            keepDays = (preferences[keepDays(userId)] ?: defaults.keepDays).coerceAtLeast(0)
        )
    }

    const val MIN_STORAGE_MB = 100
}

private val Context.autoDownloadDataStore: DataStore<Preferences> by preferencesDataStore(name = "auto_download_prefs")

class AutoDownloadPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val dataStore = appContext.autoDownloadDataStore

    fun settingsFlow(userId: String): Flow<AutoDownloadSettings> =
        dataStore.data.map { AutoDownloadPreferenceKeys.read(it, userId) }

    suspend fun get(userId: String): AutoDownloadSettings = settingsFlow(userId).first()

    suspend fun setPaused(userId: String, value: Boolean) =
        edit { it[AutoDownloadPreferenceKeys.paused(userId)] = value }

    suspend fun setWifiOnly(userId: String, value: Boolean) =
        edit { it[AutoDownloadPreferenceKeys.wifiOnly(userId)] = value }

    suspend fun setRequiresCharging(userId: String, value: Boolean) =
        edit { it[AutoDownloadPreferenceKeys.requiresCharging(userId)] = value }

    suspend fun setMaxStorageMb(userId: String, value: Int) =
        edit { it[AutoDownloadPreferenceKeys.maxStorageMb(userId)] = value.coerceAtLeast(AutoDownloadPreferenceKeys.MIN_STORAGE_MB) }

    suspend fun setKeepDays(userId: String, value: Int) =
        edit { it[AutoDownloadPreferenceKeys.keepDays(userId)] = value.coerceAtLeast(0) }

    /** UUID estable por instalación (no por usuario), enviado al reclamar planes. */
    fun installationId(): String {
        val prefs = appContext.getSharedPreferences(INSTALL_PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTALLATION_ID, null)?.takeIf(String::isNotBlank)?.let { return it }
        return synchronized(this) {
            prefs.getString(KEY_INSTALLATION_ID, null)?.takeIf(String::isNotBlank)
                ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_INSTALLATION_ID, it).apply() }
        }
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit { block(it) }
    }

    companion object {
        private const val INSTALL_PREFS = "auto_download_install"
        private const val KEY_INSTALLATION_ID = "installation_id"
    }
}
