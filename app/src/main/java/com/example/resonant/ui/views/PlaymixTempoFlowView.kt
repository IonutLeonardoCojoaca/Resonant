package com.example.resonant.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.resonant.R
import com.example.resonant.ui.components.ResonantMotion

/**
 * Curva de tempo de un PlayMix: el BPM de cada canción en orden, como la gráfica de energía
 * de un set de DJ. Las canciones sin análisis no tienen punto y la curva las salta.
 */
class PlaymixTempoFlowView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private var bpms: List<Double?> = emptyList()
    private var colorStart = Color.parseColor("#FF7A59")
    private var colorEnd = Color.parseColor("#FF4FB8")
    private var reveal = 1f

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val area = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = ContextCompat.getColor(context, R.color.primaryColorTheme)
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = ContextCompat.getColor(context, R.color.dividerOverlay)
    }
    private val linePath = Path()
    private val areaPath = Path()

    fun setColors(start: Int, end: Int) {
        colorStart = start
        colorEnd = end
        invalidate()
    }

    /** BPM por canción en el orden del set; null = sin análisis. */
    fun setTempos(values: List<Double?>, animate: Boolean) {
        val changed = values != bpms
        bpms = values
        if (changed && animate && ValueAnimator.areAnimatorsEnabled()) {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900L
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
        val known = bpms.withIndex().filter { (it.value ?: 0.0) > 0.0 }
        if (known.size < 2 || bpms.size < 2) return

        val pad = 8f * density
        val left = paddingLeft + pad
        val right = width - paddingRight - pad
        val top = paddingTop + pad
        val bottom = height - paddingBottom - pad
        for (frac in listOf(0f, 0.5f, 1f)) {
            val y = top + (bottom - top) * frac
            canvas.drawLine(left, y, right, y, grid)
        }

        val min = known.minOf { it.value!! }
        val max = known.maxOf { it.value!! }
        // Un margen mínimo de ±6 BPM para que un set plano no parezca una montaña rusa.
        val span = (max - min).coerceAtLeast(12.0)
        val mid = (max + min) / 2
        val lo = mid - span / 2
        val stepX = (right - left) / (bpms.size - 1)
        val points = known.map { (i, bpm) ->
            val x = left + i * stepX
            val level = ((bpm!! - lo) / span).toFloat()
            val y = bottom - (bottom - top) * (0.1f + 0.8f * level)
            x to bottom + (y - bottom) * reveal
        }

        linePath.reset()
        linePath.moveTo(points[0].first, points[0].second)
        for (i in 1 until points.size) {
            val (px, py) = points[i - 1]
            val (x, y) = points[i]
            val mx = (px + x) / 2f
            linePath.cubicTo(mx, py, mx, y, x, y)
        }
        areaPath.reset()
        areaPath.addPath(linePath)
        areaPath.lineTo(points.last().first, bottom)
        areaPath.lineTo(points.first().first, bottom)
        areaPath.close()

        area.shader = LinearGradient(
            0f, top, 0f, bottom,
            ColorUtils.setAlphaComponent(colorEnd, 0x55), ColorUtils.setAlphaComponent(colorStart, 0x00),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(areaPath, area)
        line.shader = LinearGradient(left, 0f, right, 0f, colorStart, colorEnd, Shader.TileMode.CLAMP)
        canvas.drawPath(linePath, line)

        points.forEach { (x, y) ->
            dot.color = ColorUtils.blendARGB(colorStart, colorEnd, ((x - left) / (right - left)).coerceIn(0f, 1f))
            canvas.drawCircle(x, y, 4.5f * density, dot)
            canvas.drawCircle(x, y, 4.5f * density, dotRing)
        }
    }
}
