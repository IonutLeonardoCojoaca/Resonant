package com.example.resonant.playback

import com.example.resonant.data.network.PlaymixTransitionDTO
import kotlin.math.abs

/**
 * Modo de una transición de PlayMix: blend automático o transición avanzada.
 *
 * - Blend automático (`mixMode = "smart"`): lo decide el motor inteligente (beatmatch si los
 *   tempos casan, si no un blend), igual que el modo inteligente de las colas normales.
 * - Transición avanzada: cualquier otro `mixMode` (crossfade, overlap, freq_split, club_drop,
 *   hard_edit), con los puntos, curvas y EQ que el usuario fijó en el editor.
 *
 * Una pareja sin transición se trata como blend automático.
 */
object PlaymixMixMode {

    const val SMART = "smart"

    /** Modo con el que se abre una transición avanzada que venía de blend automático. */
    const val DEFAULT_ADVANCED = "crossfade"

    fun isSmart(transition: PlaymixTransitionDTO?): Boolean =
        transition == null || transition.mixMode == SMART

    /** La transición avanzada a reproducir, o null si toca blend automático. */
    fun advancedOrNull(transition: PlaymixTransitionDTO?): PlaymixTransitionDTO? =
        transition?.takeUnless { isSmart(it) }

    enum class SmartStyle { BEATMATCH, BLEND }

    /**
     * Lo que hará previsiblemente el blend automático con estos tempos, para explicarlo en la UI.
     * Usa la misma tolerancia que [TransitionPlanner.beatmatchRatio]; el plan real además necesita
     * la rejilla de beats de ambas canciones, así que esto es una previsión, no una garantía.
     */
    fun predictSmartStyle(bpmA: Double, bpmB: Double): SmartStyle =
        if (bpmA > 0 && bpmB > 0 && abs(bpmA / bpmB - 1.0) <= TransitionPlanner.BEATMATCH_TOLERANCE) {
            SmartStyle.BEATMATCH
        } else {
            SmartStyle.BLEND
        }
}
