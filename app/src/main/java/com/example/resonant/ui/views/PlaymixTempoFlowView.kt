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

    // Geometría y degradados preparados fuera de onDraw (la curva se anima 900 ms a 60 fps al abrir).
    private var pointX = FloatArray(0)
    private var pointY = FloatArray(0)
    private var pointCount = 0
    private var shadersDirty = true

    fun setColors(start: Int, end: Int) {
        if (colorStart == start && colorEnd == end) return
        colorStart = start
        colorEnd = end
        shadersDirty = true
        invalidate()
    }

    /** BPM por canción en el orden del set; null = sin análisis. */
    fun setTempos(values: List<Double?>, animate: Boolean) {
        val changed = values != bpms
        bpms = values
        if (changed) prepareGeometry()
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shadersDirty = true
        prepareGeometry()
    }

    private val pad get() = 8f * density
    private val left get() = paddingLeft + pad
    private val right get() = width - paddingRight - pad
    private val top get() = paddingTop + pad
    private val bottom get() = height - paddingBottom - pad

    /** Posición final (sin animar) de cada punto con BPM conocido; se recalcula al cambiar datos o tamaño. */
    private fun prepareGeometry() {
        val known = bpms.withIndex().filter { (it.value ?: 0.0) > 0.0 }
        pointCount = if (known.size < 2 || bpms.size < 2 || width == 0) 0 else known.size
        if (pointCount == 0) return
        if (pointX.size < pointCount) {
            pointX = FloatArray(pointCount)
            pointY = FloatArray(pointCount)
        }
        val min = known.minOf { it.value!! }
        val max = known.maxOf { it.value!! }
        // Un margen mínimo de ±6 BPM para que un set plano no parezca una montaña rusa.
        val span = (max - min).coerceAtLeast(12.0)
        val mid = (max + min) / 2
        val lo = mid - span / 2
        val stepX = (right - left) / (bpms.size - 1)
        known.forEachIndexed { n, (i, bpm) ->
            val level = ((bpm!! - lo) / span).toFloat()
            pointX[n] = left + i * stepX
            pointY[n] = bottom - (bottom - top) * (0.1f + 0.8f * level)
        }
    }

    private fun prepareShaders() {
        area.shader = LinearGradient(
            0f, top, 0f, bottom,
            ColorUtils.setAlphaComponent(colorEnd, 0x55), ColorUtils.setAlphaComponent(colorStart, 0x00),
            Shader.TileMode.CLAMP
        )
        line.shader = LinearGradient(left, 0f, right, 0f, colorStart, colorEnd, Shader.TileMode.CLAMP)
        shadersDirty = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pointCount == 0) return
        if (shadersDirty) prepareShaders()
        val left = left
        val right = right
        val top = top
        val bottom = bottom
        canvas.drawLine(left, top, right, top, grid)
        canvas.drawLine(left, (top + bottom) / 2f, right, (top + bottom) / 2f, grid)
        canvas.drawLine(left, bottom, right, bottom, grid)

        fun y(i: Int) = bottom + (pointY[i] - bottom) * reveal

        linePath.reset()
        linePath.moveTo(pointX[0], y(0))
        for (i in 1 until pointCount) {
            val mx = (pointX[i - 1] + pointX[i]) / 2f
            linePath.cubicTo(mx, y(i - 1), mx, y(i), pointX[i], y(i))
        }
        areaPath.reset()
        areaPath.addPath(linePath)
        areaPath.lineTo(pointX[pointCount - 1], bottom)
        areaPath.lineTo(pointX[0], bottom)
        areaPath.close()

        canvas.drawPath(areaPath, area)
        canvas.drawPath(linePath, line)

        val radius = 4.5f * density
        for (i in 0 until pointCount) {
            val x = pointX[i]
            dot.color = ColorUtils.blendARGB(colorStart, colorEnd, ((x - left) / (right - left)).coerceIn(0f, 1f))
            canvas.drawCircle(x, y(i), radius, dot)
            canvas.drawCircle(x, y(i), radius, dotRing)
        }
    }
}
