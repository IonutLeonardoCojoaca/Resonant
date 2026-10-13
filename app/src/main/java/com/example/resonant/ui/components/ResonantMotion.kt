package com.example.resonant.ui.components

import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp

/** Curvas de movimiento compartidas por las tarjetas de Explorar y los heroes de los charts. */
object ResonantMotion {

    /** Expo-out: arranca rápido y se posa muy suave. */
    val expoOut: Interpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)

    /**
     * Rebote "de caída" amortiguado: llega a reposo, rebota un poco (~13% del
     * recorrido), una segunda vez casi imperceptible, y termina exactamente en 1
     * con velocidad casi nula, así que se para sin brusquedad.
     *
     * f(t) = 1 - |e^(-5t) · cos(2.5πt)|  →  f(0) = 0, f(1) = 1.
     */
    val softBounce: Interpolator = Interpolator { input ->
        val t = input.toDouble()
        (1.0 - abs(exp(-5.0 * t) * cos(2.5 * PI * t))).toFloat()
    }
}
