package com.example.resonant.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.resonant.R
import com.example.resonant.playback.PlaymixMixMode

/**
 * Mini diagrama de una transición de PlayMix para los conectores entre canciones: el volumen
 * de A (sale) y de B (entra) a lo largo de la mezcla. En blend automático añade la rejilla de
 * beats, porque el motor cuadra la mezcla a compás.
 */
class PlaymixMixGlyphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var mixMode: String = PlaymixMixMode.SMART
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var colorA: Int = Color.parseColor("#FF7A59")
        set(value) { field = value; invalidate() }

    var colorB: Int = Color.parseColor("#FF4FB8")
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.textTheme).let { ColorUtils.setAlphaComponent(it, 0x66) }
    }
    private val path = Path()
    private val area = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = 3f * density
        val left = paddingLeft + pad
        val right = width - paddingRight - pad
        val top = paddingTop + pad
        val bottom = height - paddingBottom - pad
        if (right <= left || bottom <= top) return

        if (mixMode == PlaymixMixMode.SMART) {
            // Rejilla de beats: cada 4.º más alta (compás).
            val beats = 16
            for (i in 0..beats) {
                val x = left + (right - left) * i / beats
                val h = if (i % 4 == 0) 5f * density else 2.5f * density
                canvas.drawLine(x, bottom, x, bottom - h, tick)
            }
        }

        drawLevel(canvas, curveA(), colorA, left, right, top, bottom)
        drawLevel(canvas, curveB(), colorB, left, right, top, bottom)
    }

    private fun drawLevel(
        canvas: Canvas, points: List<Pair<Float, Float>>, color: Int,
        left: Float, right: Float, top: Float, bottom: Float
    ) {
        val w = right - left
        val h = bottom - top
        val coords = points.map { (t, level) -> (left + t * w) to (top + h * (1f - level)) }
        path.reset()
        path.moveTo(coords[0].first, coords[0].second)
        if (coords.size == 2) {
            path.cubicTo(
                coords[0].first + w * 0.4f, coords[0].second,
                coords[1].first - w * 0.4f, coords[1].second,
                coords[1].first, coords[1].second
            )
        } else {
            for (i in 1 until coords.size) {
                val (px, py) = coords[i - 1]
                val (x, y) = coords[i]
                val mx = (px + x) / 2f
                path.cubicTo(mx, py, mx, y, x, y)
            }
        }
        area.reset()
        area.addPath(path)
        area.lineTo(coords.last().first, bottom)
        area.lineTo(coords.first().first, bottom)
        area.close()
        fill.color = ColorUtils.setAlphaComponent(color, 0x33)
        canvas.drawPath(area, fill)
        stroke.color = color
        canvas.drawPath(path, stroke)
    }

    /** (t, nivel) normalizados; las formas son las mismas que en el editor (MixModeGraphView). */
    private fun curveA(): List<Pair<Float, Float>> = when (mixMode) {
        PlaymixMixMode.SMART -> listOf(0f to 1f, 0.5f to 0.92f, 1f to 0f)
        "overlap" -> listOf(0f to 0.92f, 1f to 0.92f)
        "freq_split" -> listOf(0f to 1f, 0.3f to 1f, 0.7f to 0.4f, 1f to 0f)
        "club_drop" -> listOf(0f to 1f, 0.4f to 0.9f, 0.6f to 0.3f, 1f to 0.1f)
        "hard_edit" -> listOf(0f to 1f, 0.49f to 1f, 0.51f to 0f, 1f to 0f)
        else -> listOf(0f to 1f, 1f to 0f)
    }

    private fun curveB(): List<Pair<Float, Float>> = when (mixMode) {
        PlaymixMixMode.SMART -> listOf(0f to 0f, 0.5f to 0.92f, 1f to 1f)
        "overlap" -> listOf(0f to 0.7f, 1f to 0.7f)
        "freq_split" -> listOf(0f to 0f, 0.3f to 0.2f, 0.7f to 0.8f, 1f to 1f)
        "club_drop" -> listOf(0f to 0.1f, 0.3f to 0.2f, 0.6f to 0.7f, 1f to 1f)
        "hard_edit" -> listOf(0f to 0f, 0.49f to 0f, 0.51f to 1f, 1f to 1f)
        else -> listOf(0f to 0f, 1f to 1f)
    }
}
