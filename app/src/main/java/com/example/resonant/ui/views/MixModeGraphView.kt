package com.example.resonant.ui.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Draws a small visual diagram illustrating the selected mix mode.
 * Song A fades out (red), Song B fades in (blue/purple).
 */
class MixModeGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var mixMode: String = "crossfade"
        set(value) { field = value; invalidate() }

    private val paintA = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }
    private val paintB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }
    private val paintFillA = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintFillB = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#22FFFFFF")
    }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val paintDivider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#33FFFFFF")
        pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }

    private val colorA = Color.parseColor("#E21616")
    private val colorB = Color.parseColor("#BB86FC")
    private val pathA = Path()
    private val pathB = Path()
    private val fillPath = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 16f
        val top = pad + 12f  // leave room for labels
        val bot = h - pad
        val left = pad
        val right = w - pad
        val cw = right - left  // chart width
        val ch = bot - top      // chart height

        // Grid lines (horizontal at 25%, 50%, 75%)
        for (frac in listOf(0.25f, 0.5f, 0.75f)) {
            val y = top + ch * (1f - frac)
            canvas.drawLine(left, y, right, y, paintGrid)
        }

        // Center divider
        val cx = left + cw / 2f
        canvas.drawLine(cx, top, cx, bot, paintDivider)

        // Labels
        paintLabel.textSize = 22f * resources.displayMetrics.density / 2.5f
        paintLabel.color = Color.parseColor("#88FFFFFF")
        canvas.drawText("A", left, top - 3f, paintLabel)
        val bWidth = paintLabel.measureText("B")
        canvas.drawText("B", right - bWidth, top - 3f, paintLabel)

        // Get curves for current mode
        val curvesA = getCurveA()
        val curvesB = getCurveB()

        // Draw fill + stroke for Song A
        drawCurve(canvas, curvesA, left, right, top, bot, ch, colorA, paintA, paintFillA, true)
        // Draw fill + stroke for Song B
        drawCurve(canvas, curvesB, left, right, top, bot, ch, colorB, paintB, paintFillB, false)
    }

    private fun drawCurve(
        canvas: Canvas, points: List<Pair<Float, Float>>,
        left: Float, right: Float, top: Float, bot: Float, ch: Float,
        color: Int, strokePaint: Paint, fillPaint: Paint, isA: Boolean
    ) {
        if (points.isEmpty()) return
        val cw = right - left
        pathA.reset()
        fillPath.reset()

        strokePaint.color = color
        fillPaint.color = Color.argb(30, Color.red(color), Color.green(color), Color.blue(color))

        val n = points.size
        // Map points to pixel coords using explicit t positions
        val coords = points.map { (t, level) ->
            val x = left + t * cw
            val y = top + ch * (1f - level)
            x to y
        }

        // Stroke path
        pathA.moveTo(coords[0].first, coords[0].second)
        if (coords.size == 2) {
            // Smooth cubic bezier for 2-point curves
            val cp1x = coords[0].first + cw * 0.35f
            val cp1y = coords[0].second
            val cp2x = coords[1].first - cw * 0.35f
            val cp2y = coords[1].second
            pathA.cubicTo(cp1x, cp1y, cp2x, cp2y, coords[1].first, coords[1].second)
        } else {
            for (i in 1 until coords.size) {
                pathA.lineTo(coords[i].first, coords[i].second)
            }
        }
        canvas.drawPath(pathA, strokePaint)

        // Fill under/over curve
        fillPath.addPath(pathA)
        fillPath.lineTo(coords.last().first, bot)
        fillPath.lineTo(coords.first().first, bot)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)
    }

    /** Returns list of (t, level) where t is normalized 0..1 position */
    private fun getCurveA(): List<Pair<Float, Float>> = when (mixMode) {
        "crossfade"  -> listOf(0f to 1f, 1f to 0f)
        "overlap"    -> listOf(0f to 1f, 1f to 1f)
        "freq_split" -> listOf(0f to 1f, 0.3f to 1f, 0.7f to 0.4f, 1f to 0f)
        "club_drop"  -> listOf(0f to 1f, 0.4f to 0.9f, 0.6f to 0.3f, 0.8f to 0.1f, 1f to 0.1f)
        "hard_edit"  -> listOf(0f to 1f, 0.499f to 1f, 0.5f to 0f, 1f to 0f)
        else         -> listOf(0f to 1f, 1f to 0f)
    }

    private fun getCurveB(): List<Pair<Float, Float>> = when (mixMode) {
        "crossfade"  -> listOf(0f to 0f, 1f to 1f)
        "overlap"    -> listOf(0f to 1f, 1f to 1f)
        "freq_split" -> listOf(0f to 0f, 0.3f to 0.2f, 0.7f to 0.8f, 1f to 1f)
        "club_drop"  -> listOf(0f to 0.1f, 0.2f to 0.2f, 0.5f to 0.5f, 0.7f to 0.9f, 1f to 1f)
        "hard_edit"  -> listOf(0f to 0f, 0.499f to 0f, 0.5f to 1f, 1f to 1f)
        else         -> listOf(0f to 0f, 1f to 1f)
    }
}
