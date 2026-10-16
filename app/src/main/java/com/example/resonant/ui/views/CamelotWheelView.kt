package com.example.resonant.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.resonant.R
import com.example.resonant.ui.detail.SongInsightsFormatter

/**
 * Rueda de Camelot: 12 segmentos en dos anillos (exterior B = mayor, interior A = menor).
 * El tono de la canción se resalta con el acento y sus compatibles armónicos (±1 y relativo)
 * se iluminan más tenues; en el centro va el código.
 */
class CamelotWheelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private var code: String? = null
    private var neighbours: Set<String> = emptySet()
    private var accent = ContextCompat.getColor(context, R.color.playmix_accent)

    private val segment = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val idle = ContextCompat.getColor(context, R.color.surfaceCardOverlay)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = ContextCompat.getColor(context, R.color.white)
    }
    private val oval = RectF()

    fun setKey(camelotCode: String?, accentColor: Int) {
        code = camelotCode
        neighbours = SongInsightsFormatter.camelotNeighbours(camelotCode).toSet()
        accent = accentColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()
        if (size <= 0) return
        val cx = paddingLeft + (width - paddingLeft - paddingRight) / 2f
        val cy = paddingTop + (height - paddingTop - paddingBottom) / 2f
        val ring = size * 0.14f
        val gap = 1.6f * density
        segment.strokeWidth = ring - gap
        val current = SongInsightsFormatter.camelotPosition(code)

        // Exterior: B (mayor); interior: A (menor). El 12 arriba, como en la rueda clásica.
        listOf(false to size / 2f - ring / 2f, true to size / 2f - ring * 1.5f).forEach { (minor, r) ->
            oval.set(cx - r, cy - r, cx + r, cy + r)
            for (n in 1..12) {
                val start = -90f + (n - 12) * 30f - 15f + 1.5f
                val id = "$n${if (minor) "A" else "B"}"
                segment.color = when {
                    current != null && current.first == n && current.second == minor -> accent
                    id in neighbours -> ColorUtils.setAlphaComponent(accent, 0x66)
                    else -> idle
                }
                canvas.drawArc(oval, start, 27f, false, segment)
            }
        }

        val text = code ?: "—"
        label.textSize = size * 0.2f
        canvas.drawText(text, cx, cy - (label.descent() + label.ascent()) / 2f, label)
    }
}
