package com.example.resonant.feature.collabfinder.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.resonant.feature.collabfinder.domain.model.CollabTier

class CollabTierBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        textSize = 32f
        isFakeBoldText = true
    }
    
    private var tier: CollabTier = CollabTier.BRONZE
    private val rectF = RectF()

    fun setTier(newTier: CollabTier) {
        tier = newTier
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rectF.set(0f, 0f, w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val color = when (tier) {
            CollabTier.GOLD -> Color.parseColor("#FFD700")
            CollabTier.SILVER -> Color.parseColor("#C0C0C0")
            CollabTier.BRONZE -> Color.parseColor("#CD7F32")
        }
        
        val text = when (tier) {
            CollabTier.GOLD -> "GOLD"
            CollabTier.SILVER -> "SILVER"
            CollabTier.BRONZE -> "BRONZE"
        }

        paint.color = color
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(rectF, height / 2f, height / 2f, paint)

        val cx = width / 2f
        val cy = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, cx, cy, textPaint)
    }
}
