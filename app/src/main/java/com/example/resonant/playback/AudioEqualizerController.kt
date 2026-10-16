package com.example.resonant.playback

import android.media.audiofx.Equalizer
import android.util.Log
import com.example.resonant.managers.UserEqualizerSettings
import kotlin.math.ln
import kotlin.math.pow

/**
 * Único ecualizador persistente de usuario asociado al reproductor principal.
 *
 * TransitionManager usa efectos temporales para las mezclas con EQ propio. Antes de
 * esas mezclas el servicio llama a [suspendForTransition] y vuelve a enlazar este
 * controlador al hacer el handoff, evitando dos Equalizer sobre la misma sesión.
 *
 * En un fundido sin EQ propio la curva del usuario se mantiene en A y se copia al
 * player entrante en cuanto suena ([mirrorOnTransitionSession]); en el handoff esa
 * copia pasa a ser el efecto principal, sin saltos de timbre ni de volumen.
 */
class AudioEqualizerController {
    private var effect: Equalizer? = null
    private var audioSessionId: Int = 0
    private var transitionEffect: Equalizer? = null
    private var transitionSessionId: Int = 0
    private var suspendedForTransition = false
    private var settings = UserEqualizerSettings()

    val headroomMultiplier: Float
        get() {
            if (!settings.enabled) return 1f
            return 10.0.pow((-settings.headroomDb / 20.0).toDouble())
                .toFloat()
                .coerceIn(0.2f, 1f)
        }

    fun updateSettings(value: UserEqualizerSettings) {
        settings = value
        if (!value.enabled) {
            releaseTransitionEffect()
            releaseEffect()
            return
        }
        transitionEffect?.let { applySettingsTo(it) }
        if (!suspendedForTransition && audioSessionId != 0) {
            ensureEffectAndApply()
        }
    }

    fun bindToSession(sessionId: Int) {
        if (sessionId == audioSessionId && effect != null) {
            if (!suspendedForTransition) applySettings()
            return
        }
        releaseEffect(clearSession = false)
        audioSessionId = sessionId
        if (sessionId != 0 && settings.enabled && !suspendedForTransition) {
            ensureEffectAndApply()
        }
    }

    fun suspendForTransition() {
        suspendedForTransition = true
        releaseEffect(clearSession = false)
    }

    /** Copia la curva del usuario al player entrante de un fundido sin EQ propio. */
    fun mirrorOnTransitionSession(sessionId: Int) {
        releaseTransitionEffect()
        if (sessionId == 0 || sessionId == audioSessionId || suspendedForTransition || !settings.enabled || effect == null) return
        transitionEffect = try {
            Equalizer(0, sessionId)
        } catch (error: Exception) {
            Log.w(TAG, "El dispositivo no permite crear Equalizer para el player entrante", error)
            null
        }
        transitionSessionId = if (transitionEffect != null) sessionId else 0
        if (transitionEffect?.let { applySettingsTo(it) } == false) releaseTransitionEffect()
    }

    fun resumeAfterTransition(sessionId: Int) {
        suspendedForTransition = false
        val mirrored = transitionEffect
        if (mirrored != null && transitionSessionId == sessionId) {
            // Handoff de un fundido sin EQ propio: la copia ya suena en B; pasa a ser la principal.
            transitionEffect = null
            transitionSessionId = 0
            releaseEffect(clearSession = false)
            effect = mirrored
            audioSessionId = sessionId
            applySettings()
            return
        }
        releaseTransitionEffect()
        audioSessionId = sessionId
        if (sessionId != 0 && settings.enabled) {
            ensureEffectAndApply()
        }
    }

    fun release() {
        suspendedForTransition = false
        releaseTransitionEffect()
        releaseEffect()
    }

    private fun ensureEffectAndApply() {
        if (effect == null) {
            effect = try {
                Equalizer(0, audioSessionId)
            } catch (error: Exception) {
                Log.w(TAG, "El dispositivo no permite crear Equalizer para esta sesión", error)
                null
            }
        }
        applySettings()
    }

    private fun applySettings() {
        val equalizer = effect ?: return
        if (!applySettingsTo(equalizer)) releaseEffect(clearSession = false)
    }

    /** Aplica la curva del usuario a [equalizer]. Devuelve false si el dispositivo la rechaza. */
    private fun applySettingsTo(equalizer: Equalizer): Boolean {
        try {
            val range = equalizer.bandLevelRange
            val minLevel = range[0].toInt()
            val maxLevel = range[1].toInt()
            for (index in 0 until equalizer.numberOfBands.toInt()) {
                val centerHz = equalizer.getCenterFreq(index.toShort()) / 1_000f
                val gainDb = interpolateGain(centerHz, settings.gainsDb)
                val levelMb = (gainDb * 100f)
                    .toInt()
                    .coerceIn(minLevel, maxLevel)
                    .toShort()
                equalizer.setBandLevel(index.toShort(), levelMb)
            }
            equalizer.enabled = settings.enabled
            return true
        } catch (error: Exception) {
            Log.w(TAG, "No se pudo aplicar la curva del ecualizador", error)
            return false
        }
    }

    private fun releaseTransitionEffect() {
        runCatching {
            transitionEffect?.enabled = false
            transitionEffect?.release()
        }
        transitionEffect = null
        transitionSessionId = 0
    }

    private fun interpolateGain(frequencyHz: Float, gainsDb: List<Float>): Float {
        if (gainsDb.isEmpty()) return 0f
        if (frequencyHz <= TARGET_FREQUENCIES.first()) return gainsDb.first()
        if (frequencyHz >= TARGET_FREQUENCIES.last()) return gainsDb.last()

        val upper = TARGET_FREQUENCIES.indexOfFirst { it >= frequencyHz }
        val lower = (upper - 1).coerceAtLeast(0)
        val logFrequency = ln(frequencyHz)
        val logLower = ln(TARGET_FREQUENCIES[lower])
        val logUpper = ln(TARGET_FREQUENCIES[upper])
        val fraction = ((logFrequency - logLower) / (logUpper - logLower))
            .coerceIn(0f, 1f)
        return gainsDb[lower] + (gainsDb[upper] - gainsDb[lower]) * fraction
    }

    private fun releaseEffect(clearSession: Boolean = true) {
        runCatching {
            effect?.enabled = false
            effect?.release()
        }.onFailure {
            Log.d(TAG, "Equalizer ya liberado", it)
        }
        effect = null
        if (clearSession) audioSessionId = 0
    }

    companion object {
        private const val TAG = "UserEqualizer"
        private val TARGET_FREQUENCIES = floatArrayOf(60f, 230f, 910f, 3_600f, 14_000f)
    }
}
