package com.example.resonant.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.resonant.R
import com.example.resonant.ui.components.ResonantMotion

/**
 * Medidor horizontal segmentado (estilo vúmetro): [fraction] 0..1 enciende segmentos con un
 * degradado del color dado; el resto queda apagado. Se anima al cambiar.
 */
class LevelMeterView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private var target = 0f
    private var shown = 0f
    private var color = ContextCompat.getColor(context, R.color.playmix_accent)
    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.surfaceCardOverlay)
    }
    private val rect = RectF()

    fun setLevel(fraction: Float, levelColor: Int, animate: Boolean) {
        color = levelColor
        target = fraction.coerceIn(0f, 1f)
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            ValueAnimator.ofFloat(0f, target).apply {
                duration = 900L
                interpolator = ResonantMotion.expoOut
                addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            }.start()
        } else {
            shown = target
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        val segments = 20
        val gap = 2f * density
        val w = (right - left - gap * (segments - 1)) / segments
        on.shader = LinearGradient(
            left, 0f, right, 0f,
            ColorUtils.setAlphaComponent(color, 0x99), color, Shader.TileMode.CLAMP
        )
        val lit = (shown * segments).toInt()
        for (i in 0 until segments) {
            val x = left + i * (w + gap)
            rect.set(x, top, x + w, bottom)
            canvas.drawRoundRect(rect, 2f * density, 2f * density, if (i < lit) on else off)
        }
    }
}
