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
 *
 * Se dibuja en cada fila de la lista: las curvas son constantes por modo y el trazado reutiliza
 * los mismos Path, sin listas nuevas por frame.
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

        drawLevel(canvas, CURVES_A[mixMode] ?: DEFAULT_A, colorA, left, right, top, bottom)
        drawLevel(canvas, CURVES_B[mixMode] ?: DEFAULT_B, colorB, left, right, top, bottom)
    }

    /** [curve] = pares (t, nivel) normalizados, aplanados: [t0, l0, t1, l1, ...]. */
    private fun drawLevel(
        canvas: Canvas, curve: FloatArray, color: Int,
        left: Float, right: Float, top: Float, bottom: Float
    ) {
        val w = right - left
        val h = bottom - top
        val count = curve.size / 2
        fun x(i: Int) = left + curve[i * 2] * w
        fun y(i: Int) = top + h * (1f - curve[i * 2 + 1])

        path.reset()
        path.moveTo(x(0), y(0))
        if (count == 2) {
            path.cubicTo(x(0) + w * 0.4f, y(0), x(1) - w * 0.4f, y(1), x(1), y(1))
        } else {
            for (i in 1 until count) {
                val mx = (x(i - 1) + x(i)) / 2f
                path.cubicTo(mx, y(i - 1), mx, y(i), x(i), y(i))
            }
        }
        area.reset()
        area.addPath(path)
        area.lineTo(x(count - 1), bottom)
        area.lineTo(x(0), bottom)
        area.close()
        fill.color = ColorUtils.setAlphaComponent(color, 0x33)
        canvas.drawPath(area, fill)
        stroke.color = color
        canvas.drawPath(path, stroke)
    }

    private companion object {
        /** (t, nivel) normalizados; las formas son las mismas que en el editor (MixModeGraphView). */
        val DEFAULT_A = floatArrayOf(0f, 1f, 1f, 0f)
        val DEFAULT_B = floatArrayOf(0f, 0f, 1f, 1f)
        val CURVES_A = mapOf(
            PlaymixMixMode.SMART to floatArrayOf(0f, 1f, 0.5f, 0.92f, 1f, 0f),
            "overlap" to floatArrayOf(0f, 0.92f, 1f, 0.92f),
            "freq_split" to floatArrayOf(0f, 1f, 0.3f, 1f, 0.7f, 0.4f, 1f, 0f),
            "club_drop" to floatArrayOf(0f, 1f, 0.4f, 0.9f, 0.6f, 0.3f, 1f, 0.1f),
            "hard_edit" to floatArrayOf(0f, 1f, 0.49f, 1f, 0.51f, 0f, 1f, 0f)
        )
        val CURVES_B = mapOf(
            PlaymixMixMode.SMART to floatArrayOf(0f, 0f, 0.5f, 0.92f, 1f, 1f),
            "overlap" to floatArrayOf(0f, 0.7f, 1f, 0.7f),
            "freq_split" to floatArrayOf(0f, 0f, 0.3f, 0.2f, 0.7f, 0.8f, 1f, 1f),
            "club_drop" to floatArrayOf(0f, 0.1f, 0.3f, 0.2f, 0.6f, 0.7f, 1f, 1f),
            "hard_edit" to floatArrayOf(0f, 0f, 0.49f, 0f, 0.51f, 1f, 1f, 1f)
        )
    }
}
