package com.example.resonant.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.resonant.R
import com.example.resonant.ui.components.ResonantMotion
import com.example.resonant.ui.detail.SongInsightsFormatter
import kotlin.math.max

/**
 * Curva de energía de una canción como espectro espejado: una barra por tramo, con degradado
 * de los colores de la portada y los puntos del tema (entrada, drop, salida) marcados encima.
 * Se revela de izquierda a derecha al recibir datos.
 */
class EnergySpectrumView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private var values: List<Float> = emptyList()
    private var markers: List<SongInsightsFormatter.Marker> = emptyList()
    private var colorStart = ContextCompat.getColor(context, R.color.playmix_accent)
    private var colorEnd = ContextCompat.getColor(context, R.color.playmix_accent_end)
    private var reveal = 1f

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ghost = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.surfaceCardOverlay)
    }
    private val markerLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    }
    private val markerPill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val markerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 8.5f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.1f
        color = ContextCompat.getColor(context, R.color.primaryColorTheme)
    }
    private val rect = RectF()

    fun setColors(start: Int, end: Int) {
        colorStart = start
        colorEnd = end
        invalidate()
    }

    fun setData(energy: List<Float>, songMarkers: List<SongInsightsFormatter.Marker>, animate: Boolean) {
        val changed = energy != values
        values = energy
        markers = songMarkers
        if (changed && animate && ValueAnimator.areAnimatorsEnabled()) {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1100L
                interpolator = ResonantMotion.expoOut
                addUpdateListener { reveal = it.animatedValue as Float; invalidate() }
            }.start()
        } else {
            reveal = 1f
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return
        val labelSpace = if (markers.isEmpty()) 0f else 18f * density
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop + labelSpace
        val bottom = (height - paddingBottom).toFloat()
        val mid = (top + bottom) / 2f
        val half = (bottom - top) / 2f
        val n = values.size
        val slot = (right - left) / n
        val barW = max(1.5f * density, slot * 0.62f)
        val radius = barW / 2f

        bar.shader = LinearGradient(left, 0f, right, 0f, colorStart, colorEnd, Shader.TileMode.CLAMP)
        val revealX = left + (right - left) * reveal
        for (i in 0 until n) {
            val cx = left + slot * (i + 0.5f)
            val h = max(1.5f * density, half * (0.08f + 0.92f * values[i]))
            rect.set(cx - barW / 2, mid - h, cx + barW / 2, mid + h)
            canvas.drawRoundRect(rect, radius, radius, if (cx <= revealX) bar else ghost)
        }

        markers.forEach { marker ->
            val x = left + (right - left) * marker.fraction
            val color = ColorUtils.blendARGB(colorStart, colorEnd, marker.fraction)
            markerLine.color = ColorUtils.setAlphaComponent(color, 0xCC)
            canvas.drawLine(x, top, x, bottom, markerLine)
            val textW = markerText.measureText(marker.label)
            val pillW = textW + 10f * density
            val pillH = 14f * density
            val pillLeft = (x - pillW / 2).coerceIn(left, right - pillW)
            rect.set(pillLeft, paddingTop.toFloat(), pillLeft + pillW, paddingTop + pillH)
            markerPill.color = color
            canvas.drawRoundRect(rect, pillH / 2, pillH / 2, markerPill)
            canvas.drawText(marker.label, pillLeft + 5f * density, paddingTop + pillH - 4f * density, markerText)
        }
    }
}
