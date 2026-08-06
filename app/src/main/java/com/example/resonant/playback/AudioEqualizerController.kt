package com.example.resonant.playback

import android.media.audiofx.Equalizer
import android.util.Log
import com.example.resonant.managers.UserEqualizerSettings
import kotlin.math.ln
import kotlin.math.pow

/**
 * Único ecualizador persistente de usuario asociado al reproductor principal.
 *
 * TransitionManager usa efectos temporales para las mezclas. El servicio llama
 * a [suspendForTransition] antes de una mezcla y vuelve a enlazar este
 * controlador al hacer el handoff, evitando dos Equalizer sobre la misma sesión.
 */
class AudioEqualizerController {
    private var effect: Equalizer? = null
    private var audioSessionId: Int = 0
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
            releaseEffect()
            return
        }
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

    fun resumeAfterTransition(sessionId: Int) {
        suspendedForTransition = false
        audioSessionId = sessionId
        if (sessionId != 0 && settings.enabled) {
            ensureEffectAndApply()
        }
    }

    fun release() {
        suspendedForTransition = false
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
        } catch (error: Exception) {
            Log.w(TAG, "No se pudo aplicar la curva del ecualizador", error)
            releaseEffect(clearSession = false)
        }
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
