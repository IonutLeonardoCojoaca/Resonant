package com.example.resonant.playback

/** Cuándo se suspende el ecualizador del usuario al empezar una transición. */
object UserEqPolicy {

    /**
     * Con el motor de salida único nunca: el EQ está en la sesión compartida y la mezcla hace su propio EQ en el
     * DSP. Sin motor, solo si la mezcla usa ecualizadores del sistema (Playmix antigua, corte de álbum sin plan o
     * un plan con carriles de EQ); un fundido sin EQ propio lo mantiene en A y lo copia a B.
     */
    fun suspendForTransition(engineActive: Boolean, legacyPlaymix: Boolean, plan: TransitionPlanner.Plan?): Boolean =
        !engineActive && (legacyPlaymix || plan == null || plan.hasEq)
}
