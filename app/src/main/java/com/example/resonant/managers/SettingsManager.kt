package com.example.resonant.managers

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.resonant.live.LiveDjMode
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.math.max

/** Modo interno del motor de transiciones (lo que lee `MusicPlaybackService`). */
enum class CrossfadeMode {
    SOFT_MIX,
    DIRECT_CUT,
    INTELLIGENT_EQ
}

/**
 * Cómo pasa una canción a la siguiente fuera de Playmix: lo que el usuario elige en Ajustes
 * (unify-transition-engine). Se guarda en las claves de siempre (`crossfade_mode` y la duración),
 * así que no hay migración: los valores antiguos se leen con [fromStored].
 */
enum class TransitionStyle(val crossfadeMode: CrossfadeMode) {
    /** Empalme limpio: sin silencios entre canciones y la siguiente entra a compás. */
    CLEAN_CUT(CrossfadeMode.DIRECT_CUT),
    /** Fundido de la duración elegida. */
    FADE(CrossfadeMode.SOFT_MIX),
    /** Mezcla según el ritmo y la estructura de cada pareja (recetas del servidor y beatmatch). */
    AUTO(CrossfadeMode.INTELLIGENT_EQ);

    companion object {
        const val DEFAULT_FADE_SECONDS = 6
        const val MIN_FADE_SECONDS = 1
        const val MAX_FADE_SECONDS = 12

        /**
         * Sin nada guardado es [AUTO]. Valores antiguos: inteligente → [AUTO]; corte o 0 s
         * ("desactivado") → [CLEAN_CUT]; fundido suave con duración → [FADE].
         */
        fun fromStored(mode: String?, durationSeconds: Int?): TransitionStyle = when {
            mode == null && durationSeconds == null -> AUTO
            mode == CrossfadeMode.INTELLIGENT_EQ.name -> AUTO
            mode == CrossfadeMode.DIRECT_CUT.name -> CLEAN_CUT
            durationSeconds == 0 -> CLEAN_CUT
            else -> FADE
        }

        /** Duración del fundido que se muestra y se usa: la guardada, acotada; por defecto 6 s. */
        fun fadeSeconds(durationSeconds: Int?): Int =
            durationSeconds?.takeIf { it > 0 }?.coerceIn(MIN_FADE_SECONDS, MAX_FADE_SECONDS) ?: DEFAULT_FADE_SECONDS
    }
}

enum class ThemeMode {
    DARK,
    LIGHT,
    /** Sigue el tema claro/oscuro del sistema. */
    SYSTEM;

    companion object {
        fun fromStored(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value } ?: DARK
    }
}

/** Maps [ThemeMode] to the AppCompatDelegate night-mode constant that applies it. */
fun ThemeMode.toNightMode(): Int = when (this) {
    ThemeMode.DARK -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
    ThemeMode.LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
    ThemeMode.SYSTEM -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
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
        val FLOW_DISCOVERY_LEVEL_KEY = intPreferencesKey("flow_discovery_level")
        val FLOW_PREFERENCES_PENDING_SYNC_KEY = booleanPreferencesKey("flow_preferences_pending_sync")
        val ARIA_FLOATING_ASSISTANT_ENABLED_KEY = booleanPreferencesKey("aria_floating_assistant_enabled")
        val ARIA_FOREGROUND_WAKE_WORD_ENABLED_KEY = booleanPreferencesKey("aria_foreground_wake_word_enabled")
        val STREAK_REMINDER_ENABLED_KEY = booleanPreferencesKey("streak_reminder_enabled")
        val LIVE_DJ_MODE_KEY = stringPreferencesKey("live_dj_mode")
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

    // El ecualizador del usuario y la transición automática ya conviven: el motor solo suspende el EQ
    // del usuario durante las mezclas que usan EQ propio (unify-transition-engine D5).
    suspend fun setEqualizerEnabled(enabled: Boolean) {
        context.dataStore.edit { it[EQUALIZER_ENABLED_KEY] = enabled }
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

    /** Nivel de descubrimiento de Flow: 0 familiar, 1 equilibrado, 2 descubrir. */
    val flowDiscoveryLevelFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[FLOW_DISCOVERY_LEVEL_KEY] ?: 1
    }

    /** Hay cambios de Flow pendientes de subir a `PUT api/me/flow-preferences`. */
    val flowPreferencesPendingSyncFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[FLOW_PREFERENCES_PENDING_SYNC_KEY] ?: false
    }

    suspend fun setFlowPreferences(enabled: Boolean, discoveryLevel: Int, pendingSync: Boolean) {
        context.dataStore.edit {
            it[AUTOPLAY_RADIO_ENABLED_KEY] = enabled
            it[FLOW_DISCOVERY_LEVEL_KEY] = discoveryLevel
            it[FLOW_PREFERENCES_PENDING_SYNC_KEY] = pendingSync
        }
    }

    /** Aria DJ en Live: voz (por defecto), solo subtítulos o desactivado. Un valor desconocido se lee como voz. */
    val liveDjModeFlow: Flow<LiveDjMode> = context.dataStore.data.map { preferences ->
        LiveDjMode.fromStored(preferences[LIVE_DJ_MODE_KEY])
    }

    suspend fun setLiveDjMode(mode: LiveDjMode) {
        context.dataStore.edit { it[LIVE_DJ_MODE_KEY] = mode.name }
    }

    suspend fun setFlowPreferencesPendingSync(pending: Boolean) {
        context.dataStore.edit { it[FLOW_PREFERENCES_PENDING_SYNC_KEY] = pending }
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

    val streakReminderEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[STREAK_REMINDER_ENABLED_KEY] ?: true // Activado por defecto
    }

    suspend fun setStreakReminderEnabled(isEnabled: Boolean) {
        context.dataStore.edit { it[STREAK_REMINDER_ENABLED_KEY] = isEnabled }
    }


    // ── Transición entre canciones ─────────────────────────────────────────────────────────

    val transitionStyleFlow: Flow<TransitionStyle> = context.dataStore.data.map { preferences ->
        TransitionStyle.fromStored(preferences[CROSSFADE_MODE_KEY], preferences[CROSSFADE_DURATION_KEY])
    }

    /** Duración del fundido en segundos (solo cuenta con [TransitionStyle.FADE]). */
    val fadeSecondsFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        TransitionStyle.fadeSeconds(preferences[CROSSFADE_DURATION_KEY])
    }

    /** Modo del motor, derivado del estilo (lo lee el servicio de reproducción). */
    val crossfadeModeFlow: Flow<CrossfadeMode> = transitionStyleFlow.map { it.crossfadeMode }

    /** Duración en segundos para el motor: la del fundido; el corte y el modo automático la ignoran. */
    val crossfadeDurationFlow: Flow<Int> = fadeSecondsFlow

    suspend fun setTransitionStyle(style: TransitionStyle) {
        context.dataStore.edit {
            it[CROSSFADE_MODE_KEY] = style.crossfadeMode.name
            if (style == TransitionStyle.FADE) it[CROSSFADE_DURATION_KEY] = TransitionStyle.fadeSeconds(it[CROSSFADE_DURATION_KEY])
        }
    }

    suspend fun setFadeSeconds(seconds: Int) {
        context.dataStore.edit {
            it[CROSSFADE_DURATION_KEY] = seconds.coerceIn(TransitionStyle.MIN_FADE_SECONDS, TransitionStyle.MAX_FADE_SECONDS)
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
