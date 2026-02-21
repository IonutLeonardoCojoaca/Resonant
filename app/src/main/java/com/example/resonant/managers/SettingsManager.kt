package com.example.resonant.managers

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class CrossfadeMode {
    SOFT_MIX,
    DIRECT_CUT,
    INTELLIGENT_EQ
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {

    companion object {
        val CROSSFADE_DURATION_KEY = intPreferencesKey("crossfade_duration_seconds")
        val CROSSFADE_MODE_KEY = stringPreferencesKey("crossfade_mode")
        val AUTOMIX_ENABLED_KEY = booleanPreferencesKey("automix_enabled")
        val LOUDNESS_NORMALIZATION_KEY = booleanPreferencesKey("loudness_normalization_enabled")
    }

    val automixEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTOMIX_ENABLED_KEY] ?: false // Desactivado por defecto
    }

    suspend fun setAutomixEnabled(isEnabled: Boolean) {
        context.dataStore.edit { it[AUTOMIX_ENABLED_KEY] = isEnabled }
    }

    val loudnessNormalizationFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[LOUDNESS_NORMALIZATION_KEY] ?: true // Activado por defecto
    }

    suspend fun setLoudnessNormalizationEnabled(isEnabled: Boolean) {
        context.dataStore.edit { it[LOUDNESS_NORMALIZATION_KEY] = isEnabled }
    }


    // Flujo combinado que considera el modo inteligente
    val crossfadeDurationFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        val mode = try {
            CrossfadeMode.valueOf(preferences[CROSSFADE_MODE_KEY] ?: CrossfadeMode.SOFT_MIX.name)
        } catch (e: IllegalArgumentException) {
            CrossfadeMode.SOFT_MIX
        }

        // Si es modo inteligente, duración = 0
        if (mode == CrossfadeMode.INTELLIGENT_EQ) {
            0
        } else {
            preferences[CROSSFADE_DURATION_KEY] ?: 5 // 5 segundos por defecto para modos normales
        }
    }

    val crossfadeModeFlow: Flow<CrossfadeMode> = context.dataStore.data.map { preferences ->
        try {
            CrossfadeMode.valueOf(preferences[CROSSFADE_MODE_KEY] ?: CrossfadeMode.SOFT_MIX.name)
        } catch (e: IllegalArgumentException) {
            CrossfadeMode.SOFT_MIX // Fallback seguro
        }
    }

    suspend fun setCrossfadeDuration(durationInSeconds: Int) {
        context.dataStore.edit { it[CROSSFADE_DURATION_KEY] = durationInSeconds }
    }

    suspend fun setCrossfadeMode(mode: CrossfadeMode) {
        context.dataStore.edit { it[CROSSFADE_MODE_KEY] = mode.name }
    }
}

