package com.example.resonant.playback

import com.example.resonant.data.network.PlaymixTransitionDTO

/**
 * Cómo suena una pareja con mezcla de Playmix, en el orden de prioridad de siempre: escucha del editor →
 * documento de Studio → clip renderizado o campos clásicos → automático. La calcula
 * `MusicPlaybackService.resolvePlaymixPlan` tanto para una cola PLAYMIX como para un bloque de Playmix dentro
 * de la cola (collection-queue-offline D1).
 */
sealed interface PlaymixResolution {
    /** Escucha del editor o documento de Studio: se mezcla con el plan del motor del móvil. */
    data class Planned(val plan: TransitionPlanner.Plan) : PlaymixResolution

    /** Clip renderizado o campos clásicos: motor de Playmix (`startPlaymixTransition`). */
    data class Designed(val transition: PlaymixTransitionDTO) : PlaymixResolution

    /** Blend automático o pareja sin transición: motor inteligente ([plan] `null` = sin mezcla). */
    data class Smart(val plan: TransitionPlanner.Plan?) : PlaymixResolution
}
