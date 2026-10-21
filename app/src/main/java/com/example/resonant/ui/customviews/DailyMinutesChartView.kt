package com.example.resonant.ui.customviews

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.example.resonant.R

/**
 * Gráfico de barras de minutos escuchados (Canvas, sin librerías). Etiquetas solo si caben.
 * Barras en el acento de la pantalla: la del máximo a color pleno y el resto al ~45 %.
 */
class DailyMinutesChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.dividerOnSurface)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.textTheme)
        textSize = 10f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.unageo_medium)
    }
    private val rect = RectF()

    /** Acento de las barras (por defecto el texto principal, neutro en ambos temas). */
    @ColorInt
    var accentColor: Int = ContextCompat.getColor(context, R.color.white)
        set(value) {
            field = value
            applyAccent()
            invalidate()
        }

    init {
        applyAccent()
    }

    private fun applyAccent() {
        peakPaint.color = accentColor
        barPaint.color = ColorUtils.setAlphaComponent(accentColor, (255 * 0.45f).toInt())
    }

    var bars: List<TimelineBar> = emptyList()
        set(value) {
            field = value
            contentDescription = value.joinToString { "${it.label}: ${it.minutes} min" }
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (160 * density).toInt()
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bars.isEmpty()) return

        val labelHeight = labelPaint.textSize + 6 * density
        val chartBottom = height - paddingBottom - labelHeight
        val chartTop = paddingTop.toFloat()
        val slot = (width - paddingLeft - paddingRight).toFloat() / bars.size
        val gap = (slot * 0.25f).coerceAtMost(6 * density)
        val radius = 3 * density
        val max = bars.maxOf { it.minutes }.coerceAtLeast(1)
        val minBar = 2 * density
        val showEveryLabel = slot >= labelPaint.textSize * 1.6f
        val labelStep = if (showEveryLabel) 1 else ((labelPaint.textSize * 2.5f) / slot).toInt().coerceAtLeast(1)

        bars.forEachIndexed { index, bar ->
            val left = paddingLeft + index * slot + gap / 2
            val right = left + slot - gap
            val barHeight = ((chartBottom - chartTop) * bar.minutes / max).coerceAtLeast(minBar)
            rect.set(left, chartBottom - barHeight, right, chartBottom)
            val paint = when {
                bar.minutes <= 0 -> emptyPaint
                bar.minutes == max -> peakPaint
                else -> barPaint
            }
            canvas.drawRoundRect(rect, radius, radius, paint)
            if (index % labelStep == 0) {
                canvas.drawText(bar.label, (left + right) / 2, height - paddingBottom - 2 * density, labelPaint)
            }
        }
    }
}
