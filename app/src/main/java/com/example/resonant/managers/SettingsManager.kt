package com.example.resonant.managers

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.max

enum class CrossfadeMode {
    SOFT_MIX,
    DIRECT_CUT,
    INTELLIGENT_EQ
}

enum class AudioQuality(
    val apiValue: String,
    val displayName: String,
    val maxStreamingBitrate: Int
) {
    AUTO("auto", "Automática", Int.MAX_VALUE),
    DATA_SAVER("data-saver", "Ahorro de datos · 96 kbps", 96_000),
    NORMAL("normal", "Normal · 160 kbps", 160_000),
    HIGH("high", "Alta · hasta 320 kbps", 320_000);

    companion object {
        fun fromStored(value: String?, fallback: AudioQuality): AudioQuality {
            return entries.firstOrNull {
                it.name == value || it.apiValue == value
            } ?: fallback
        }
    }
}

enum class EqualizerPreset(val displayName: String, val gainsDb: List<Float>) {
    FLAT("Plano", listOf(0f, 0f, 0f, 0f, 0f)),
    BASS_BOOST("Graves", listOf(5f, 3f, 0f, -1f, -1f)),
    VOCAL("Voz", listOf(-2f, 0f, 4f, 3f, 0f)),
    TREBLE("Agudos", listOf(-1f, -1f, 0f, 3f, 5f)),
    CUSTOM("Personalizado", listOf(0f, 0f, 0f, 0f, 0f))
}

data class UserEqualizerSettings(
    val enabled: Boolean = false,
    val preset: EqualizerPreset = EqualizerPreset.FLAT,
    val gainsDb: List<Float> = EqualizerPreset.FLAT.gainsDb
) {
    val headroomDb: Float
        get() = gainsDb.maxOrNull()?.coerceAtLeast(0f) ?: 0f
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {

    companion object {
        val CROSSFADE_DURATION_KEY = intPreferencesKey("crossfade_duration_seconds")
        val CROSSFADE_MODE_KEY = stringPreferencesKey("crossfade_mode")
        val AUTOMIX_ENABLED_KEY = booleanPreferencesKey("automix_enabled")
        val LOUDNESS_NORMALIZATION_KEY = booleanPreferencesKey("loudness_normalization_enabled")
        val AUTOPLAY_RADIO_ENABLED_KEY = booleanPreferencesKey("autoplay_radio_enabled")
        val STREAMING_QUALITY_KEY = stringPreferencesKey("streaming_audio_quality")
        val DOWNLOAD_QUALITY_KEY = stringPreferencesKey("download_audio_quality")
        val EQUALIZER_ENABLED_KEY = booleanPreferencesKey("equalizer_enabled")
        val EQUALIZER_PRESET_KEY = stringPreferencesKey("equalizer_preset")
        private val EQUALIZER_BAND_KEYS = listOf(
            floatPreferencesKey("equalizer_60_hz_db"),
            floatPreferencesKey("equalizer_230_hz_db"),
            floatPreferencesKey("equalizer_910_hz_db"),
            floatPreferencesKey("equalizer_3600_hz_db"),
            floatPreferencesKey("equalizer_14000_hz_db")
        )
    }

    val streamingQualityFlow: Flow<AudioQuality> = context.dataStore.data.map { preferences ->
        AudioQuality.fromStored(preferences[STREAMING_QUALITY_KEY], AudioQuality.AUTO)
    }

    val downloadQualityFlow: Flow<AudioQuality> = context.dataStore.data.map { preferences ->
        AudioQuality.fromStored(preferences[DOWNLOAD_QUALITY_KEY], AudioQuality.HIGH)
    }

    suspend fun setStreamingQuality(quality: AudioQuality) {
        context.dataStore.edit { it[STREAMING_QUALITY_KEY] = quality.name }
    }

    suspend fun setDownloadQuality(quality: AudioQuality) {
        context.dataStore.edit { it[DOWNLOAD_QUALITY_KEY] = quality.name }
    }

    val equalizerSettingsFlow: Flow<UserEqualizerSettings> =
        context.dataStore.data.map { preferences ->
            val preset = runCatching {
                EqualizerPreset.valueOf(
                    preferences[EQUALIZER_PRESET_KEY] ?: EqualizerPreset.FLAT.name
                )
            }.getOrDefault(EqualizerPreset.FLAT)
            val fallbackGains = preset.gainsDb
            UserEqualizerSettings(
                enabled = preferences[EQUALIZER_ENABLED_KEY] ?: false,
                preset = preset,
                gainsDb = EQUALIZER_BAND_KEYS.mapIndexed { index, key ->
                    (preferences[key] ?: fallbackGains[index]).coerceIn(-12f, 12f)
                }
            )
        }

    suspend fun setEqualizerEnabled(enabled: Boolean) {
        context.dataStore.edit {
            it[EQUALIZER_ENABLED_KEY] = enabled
            if (
                enabled &&
                it[CROSSFADE_MODE_KEY] == CrossfadeMode.INTELLIGENT_EQ.name
            ) {
                it[CROSSFADE_MODE_KEY] = CrossfadeMode.SOFT_MIX.name
            }
        }
    }

    suspend fun setEqualizerPreset(preset: EqualizerPreset) {
        val gains = preset.gainsDb
        context.dataStore.edit { preferences ->
            preferences[EQUALIZER_PRESET_KEY] = preset.name
            EQUALIZER_BAND_KEYS.forEachIndexed { index, key ->
                preferences[key] = gains[index].coerceIn(-12f, 12f)
            }
        }
    }

    suspend fun setEqualizerBandGains(gainsDb: List<Float>) {
        require(gainsDb.size == EQUALIZER_BAND_KEYS.size) {
            "El ecualizador necesita exactamente ${EQUALIZER_BAND_KEYS.size} bandas"
        }
        context.dataStore.edit { preferences ->
            preferences[EQUALIZER_PRESET_KEY] = EqualizerPreset.CUSTOM.name
            EQUALIZER_BAND_KEYS.forEachIndexed { index, key ->
                preferences[key] = gainsDb[index].coerceIn(-12f, 12f)
            }
        }
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

    val autoplayRadioEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTOPLAY_RADIO_ENABLED_KEY] ?: true // Activado por defecto
    }

    suspend fun setAutoplayRadioEnabled(isEnabled: Boolean) {
        context.dataStore.edit { it[AUTOPLAY_RADIO_ENABLED_KEY] = isEnabled }
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
        context.dataStore.edit {
            it[CROSSFADE_MODE_KEY] = mode.name
            if (mode == CrossfadeMode.INTELLIGENT_EQ) {
                it[EQUALIZER_ENABLED_KEY] = false
            }
        }
    }
}

