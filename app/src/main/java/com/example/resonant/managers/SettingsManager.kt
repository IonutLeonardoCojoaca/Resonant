package com.example.resonant.managers

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.math.max

enum class CrossfadeMode {
    SOFT_MIX,
    DIRECT_CUT,
    INTELLIGENT_EQ
}

enum class ThemeMode {
    DARK,
    LIGHT;

    companion object {
        fun fromStored(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value } ?: DARK
    }
}

/** Maps [ThemeMode] to the AppCompatDelegate night-mode constant that applies it. */
fun ThemeMode.toNightMode(): Int = when (this) {
    ThemeMode.DARK -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
    ThemeMode.LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
}

enum class AudioQuality(
    val apiValue: String,
    val displayName: String,
    val maxStreamingBitrate: Int
) {
    // Resonant ya no expone selector de calidad al usuario: streaming y
    // descargas usan siempre el modo automático, con un tope de 320 kbps.
    AUTO("auto", "Automática · hasta 320 kbps", 320_000);

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
        val ARIA_FLOATING_ASSISTANT_ENABLED_KEY = booleanPreferencesKey("aria_floating_assistant_enabled")
        val ARIA_FOREGROUND_WAKE_WORD_ENABLED_KEY = booleanPreferencesKey("aria_foreground_wake_word_enabled")
        val EQUALIZER_ENABLED_KEY = booleanPreferencesKey("equalizer_enabled")
        val EQUALIZER_PRESET_KEY = stringPreferencesKey("equalizer_preset")
        private val EQUALIZER_BAND_KEYS = listOf(
            floatPreferencesKey("equalizer_60_hz_db"),
            floatPreferencesKey("equalizer_230_hz_db"),
            floatPreferencesKey("equalizer_910_hz_db"),
            floatPreferencesKey("equalizer_3600_hz_db"),
            floatPreferencesKey("equalizer_14000_hz_db")
        )
        private const val THEME_PREFS_NAME = "theme_prefs"
        private const val THEME_MODE_PREF_KEY = "theme_mode"
    }

    // Calidad fija: ya no hay selector de usuario, streaming y descargas
    // siempre solicitan AudioQuality.AUTO (automático, hasta 320 kbps).
    val streamingQualityFlow: Flow<AudioQuality> = flowOf(AudioQuality.AUTO)

    val downloadQualityFlow: Flow<AudioQuality> = flowOf(AudioQuality.AUTO)

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

    val ariaFloatingAssistantEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[ARIA_FLOATING_ASSISTANT_ENABLED_KEY] ?: true
    }

    suspend fun setAriaFloatingAssistantEnabled(isEnabled: Boolean) {
        context.dataStore.edit { it[ARIA_FLOATING_ASSISTANT_ENABLED_KEY] = isEnabled }
    }

    val ariaForegroundWakeWordEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[ARIA_FOREGROUND_WAKE_WORD_ENABLED_KEY] ?: false
    }

    suspend fun setAriaForegroundWakeWordEnabled(isEnabled: Boolean) {
        context.dataStore.edit { it[ARIA_FOREGROUND_WAKE_WORD_ENABLED_KEY] = isEnabled }
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

    // --- TEMA CLARO/OSCURO ---
    // Se guarda en SharedPreferences (no en DataStore) porque debe leerse de
    // forma síncrona en Application.onCreate(), antes de que se cree
    // cualquier Activity, para aplicar AppCompatDelegate sin parpadeos.
    private val themePrefs = context.getSharedPreferences(THEME_PREFS_NAME, Context.MODE_PRIVATE)

    fun getThemeModeSync(): ThemeMode = ThemeMode.fromStored(themePrefs.getString(THEME_MODE_PREF_KEY, null))

    fun setThemeMode(mode: ThemeMode) {
        themePrefs.edit().putString(THEME_MODE_PREF_KEY, mode.name).apply()
    }

    val themeModeFlow: Flow<ThemeMode> = callbackFlow {
        trySend(getThemeModeSync())
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == THEME_MODE_PREF_KEY) {
                trySend(ThemeMode.fromStored(prefs.getString(key, null)))
            }
        }
        themePrefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose {
            themePrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
}
