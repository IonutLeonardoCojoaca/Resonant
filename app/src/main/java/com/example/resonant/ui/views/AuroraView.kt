package com.example.resonant.ui.views

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Fondo "aurora" de Inicio y del menú lateral: tres manchas de luz grandes y difusas que derivan despacio. Los colores
 * se pueden cambiar en caliente con [setPalette] (p. ej. los de la portada de lo último que sonó) y se funden con un
 * fundido suave. En tema claro la luz es más tenue para no ensuciar el fondo blanco. Solo se anima mientras está
 * visible y con [setAnimating] activo.
 */
class AuroraView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var colors = intArrayOf(DEFAULT_A, DEFAULT_B, DEFAULT_C)
    private var phase = 0f
    private var driftAnimator: ValueAnimator? = null
    private var paletteAnimator: ValueAnimator? = null
    private var animatingRequested = true
    private val isNight: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Intensidad 0..1 de toda la aurora (el parallax la apaga al hacer scroll). */
    var intensity: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Cambia los colores con un fundido de [durationMs]. */
    fun setPalette(a: Int, b: Int, c: Int, durationMs: Long = 900L) {
        val from = colors.copyOf()
        val to = intArrayOf(a, b, c)
        if (from.contentEquals(to)) return
        paletteAnimator?.cancel()
        val evaluator = ArgbEvaluator()
        paletteAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                colors = IntArray(3) { evaluator.evaluate(f, from[it], to[it]) as Int }
                invalidate()
            }
            start()
        }
    }

    /** Pausa o reanuda la deriva (p. ej. con la pantalla en segundo plano). */
    fun setAnimating(animating: Boolean) {
        animatingRequested = animating
        if (animating && isShown) startDrift() else stopDrift()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (intensity <= 0f) return
        val w = width.toFloat()
        val h = height.toFloat()
        val base = if (isNight) 0.62f else 0.34f
        val t = phase * 2 * PI
        // Cada mancha sigue su propia órbita lenta (frecuencias distintas: nunca se repite igual).
        val blobs = listOf(
            Triple(0.18f + 0.10f * sin(t).toFloat(), 0.20f + 0.08f * cos(t * 2).toFloat(), 0.75f),
            Triple(0.82f + 0.08f * cos(t).toFloat(), 0.12f + 0.10f * sin(t * 3).toFloat(), 0.68f),
            Triple(0.55f + 0.14f * sin(t * 2 + 1).toFloat(), 0.55f + 0.08f * cos(t + 2).toFloat(), 0.62f)
        )
        blobs.forEachIndexed { index, (fx, fy, fr) ->
            val radius = max(w, h) * fr
            val color = colors[index]
            val alpha = (255 * base * intensity).toInt().coerceIn(0, 255)
            paint.shader = RadialGradient(
                fx * w, fy * h, radius,
                intArrayOf(ColorUtils.setAlphaComponent(color, alpha), ColorUtils.setAlphaComponent(color, alpha / 3), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(fx * w, fy * h, radius, paint)
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && animatingRequested) startDrift() else stopDrift()
    }

    override fun onDetachedFromWindow() {
        stopDrift()
        paletteAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun startDrift() {
        if (driftAnimator != null) return
        driftAnimator = ValueAnimator.ofFloat(phase, phase + 1f).apply {
            duration = DRIFT_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = (it.animatedValue as Float) % 1f; invalidate() }
            start()
        }
    }

    private fun stopDrift() {
        driftAnimator?.cancel()
        driftAnimator = null
    }

    companion object {
        private const val DRIFT_MS = 26_000L
        /** Paleta de marca de Resonant: rojo, magenta y violeta. */
        val DEFAULT_A = Color.parseColor("#E21616")
        val DEFAULT_B = Color.parseColor("#FF2E7E")
        val DEFAULT_C = Color.parseColor("#7C4DFF")
    }
}
