package com.example.resonant.feature.collabfinder.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

class CollabScoreGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 24f
        strokeCap = Paint.Cap.ROUND
    }
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 64f
    }
    
    private var score = 0f
    private var targetScore = 0f
    private var animator: ValueAnimator? = null
    private val rectF = RectF()

    fun setScore(newScore: Int) {
        targetScore = newScore.toFloat().coerceIn(0f, 100f)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(score, targetScore).apply {
            duration = 1000
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                score = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val padding = paint.strokeWidth / 2f
        rectF.set(padding, padding, w - padding, h - padding)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val cx = width / 2f
        val cy = height / 2f

        // Background arc
        paint.color = Color.parseColor("#333333")
        canvas.drawArc(rectF, 135f, 270f, false, paint)

        // Score arc
        paint.color = getColorForScore(score)
        val sweepAngle = (score / 100f) * 270f
        canvas.drawArc(rectF, 135f, sweepAngle, false, paint)

        // Text
        val yPos = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(score.toInt().toString(), cx, yPos, textPaint)
    }

    private fun getColorForScore(score: Float): Int {
        return when {
            score >= 80 -> Color.parseColor("#4CAF50") // Green
            score >= 50 -> Color.parseColor("#FFC107") // Yellow
            else -> Color.parseColor("#F44336") // Red
        }
    }
}
