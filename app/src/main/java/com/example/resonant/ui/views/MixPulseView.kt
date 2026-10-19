package com.example.resonant.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * Ecualizador animado de la cápsula de estado del editor de PlayMix: barras redondeadas con el degradado de la marca
 * que suben y bajan desfasadas mientras se guarda o se renderiza la mezcla. Se anima solo mientras es visible.
 */
class MixPulseView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var phase = 0f
    private var animator: ValueAnimator? = null

    /** Más rápido y amplio al renderizar; más tranquilo al guardar. */
    var intensity: Float = 1f
        set(value) { field = value.coerceIn(0.2f, 1.5f); invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, COLOR_START, COLOR_END, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val barW = w / (BARS * 2 - 1)
        val radius = barW / 2
        for (i in 0 until BARS) {
            val wave = (sin(phase * 2 * PI + i * 1.1) + 1) / 2
            val level = (0.28f + 0.72f * wave.toFloat() * intensity).coerceIn(0.2f, 1f)
            val barH = h * level
            val x = i * barW * 2
            canvas.drawRoundRect(x, (h - barH) / 2, x + barW, (h + barH) / 2, radius, radius, paint)
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) start() else stop()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
    }

    private companion object {
        const val BARS = 4
        const val CYCLE_MS = 900L
        val COLOR_START = Color.parseColor("#FF7A59")
        val COLOR_END = Color.parseColor("#FF4FB8")
    }
}
