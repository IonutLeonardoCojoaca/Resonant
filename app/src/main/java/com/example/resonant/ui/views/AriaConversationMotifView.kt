package com.example.resonant.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.example.resonant.R
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Motivo de Aria (basado en el prototipo de Figma): el logotipo "Aria" a la izquierda y
 * una conversación de píldoras en zigzag a la derecha; la primera, con el degradado de
 * Aria. Las píldoras se "escriben" una tras otra al aparecer; en reposo, un brillo
 * recorre la píldora de color, las grises respiran y unos puntos de "escribiendo…"
 * laten bajo la última. Pensado para ir sobre fondo oscuro (tarjeta de marca).
 */
class AriaConversationMotifView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Píldora en coordenadas relativas al cuadro de la composición (0..1). */
    private class Pill(val left: Float, val right: Float, val centerY: Float, val accent: Boolean)

    private val pills = listOf(
        Pill(0.24f, 0.57f, 0.16f, accent = true),
        Pill(0.455f, 0.745f, 0.33f, accent = false),
        Pill(0.53f, 0.815f, 0.50f, accent = false),
        Pill(0.455f, 0.745f, 0.67f, accent = false),
        Pill(0.28f, 0.57f, 0.84f, accent = false)
    )

    private val gradientStart = Color.parseColor("#FF1F3D")
    private val gradientEnd = Color.parseColor("#F21FD8")
    private val pillGray = Color.parseColor("#2C2C2C")

    private val logo: Drawable? = ContextCompat.getDrawable(context, R.drawable.logo_aria_text)
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5A5A5A") }
    private val rect = RectF()
    private val clip = Path()

    private var box = 0f
    private var originX = 0f
    private var originY = 0f
    private var pillHeight = 0f
    private var time = 0f
    private var startedAt = -1f

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        var last = 0L
        addUpdateListener {
            val now = it.currentPlayTime
            val delta = if (last == 0L || now < last) 16L else (now - last).coerceAtMost(48L)
            last = now
            time += delta / 1000f
            invalidate()
        }
    }

    /** Vuelve a "escribir" la conversación desde cero (entrada de la pantalla). */
    fun replay() {
        startedAt = time
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val contentW = (w - paddingLeft - paddingRight).toFloat()
        val contentH = (h - paddingTop - paddingBottom).toFloat()
        box = min(contentW * 0.92f, contentH * 1.05f)
        originX = paddingLeft + (contentW - box) / 2f
        originY = paddingTop + (contentH - box) / 2f
        pillHeight = box * 0.068f
        accentPaint.shader = LinearGradient(
            originX + box * pills[0].left, 0f, originX + box * pills[0].right, 0f,
            gradientStart, gradientEnd, Shader.TileMode.CLAMP
        )
        shinePaint.shader = LinearGradient(
            -box * 0.08f, 0f, box * 0.08f, 0f,
            intArrayOf(0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP
        )
        if (startedAt < 0f) startedAt = time
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (box <= 0f) return
        val since = time - startedAt
        val float = sin(time * 0.9f) * box * 0.01f

        drawLogo(canvas, since, float)
        pills.forEachIndexed { index, pill -> drawPill(canvas, pill, index, since, float) }
        drawTyping(canvas, since, float)
    }

    private fun drawLogo(canvas: Canvas, since: Float, float: Float) {
        val d = logo ?: return
        val p = ease(since / 0.6f)
        if (p <= 0f) return
        val width = box * 0.24f
        val height = width * d.intrinsicHeight / d.intrinsicWidth.coerceAtLeast(1)
        val cx = originX + box * 0.15f + width / 2f
        val cy = originY + box * 0.5f + float * 0.5f
        val scale = 0.85f + 0.15f * p
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)
        d.alpha = (255 * p).toInt()
        d.setBounds((-width / 2).toInt(), (-height / 2).toInt(), (width / 2).toInt(), (height / 2).toInt())
        d.draw(canvas)
        canvas.restore()
    }

    private fun drawPill(canvas: Canvas, pill: Pill, index: Int, since: Float, float: Float) {
        // Cada píldora "llega" 0.14 s después de la anterior y crece con un leve rebote.
        val t = ((since - 0.25f - index * 0.14f) / 0.55f).coerceIn(0f, 1f)
        if (t <= 0f) return
        val grow = overshoot(t)
        val left = originX + box * pill.left
        val fullWidth = box * (pill.right - pill.left)
        val width = (fullWidth * grow).coerceAtLeast(pillHeight)
        val cy = originY + box * pill.centerY + float * (if (index % 2 == 0) 1f else -0.6f)
        val radius = pillHeight / 2f
        rect.set(left, cy - radius, left + width, cy + radius)

        if (pill.accent) {
            accentPaint.alpha = (255 * ease(t * 1.6f)).toInt()
            canvas.drawRoundRect(rect, radius, radius, accentPaint)
            // Brillo que recorre la píldora cada 2.6 s.
            val sweep = ((since % 2.6f) / 2.6f) * 1.6f - 0.3f
            if (t >= 1f && sweep in 0f..1f) {
                clip.reset()
                clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(clip)
                canvas.translate(left + fullWidth * sweep, 0f)
                canvas.drawRect(-box * 0.08f, rect.top, box * 0.08f, rect.bottom, shinePaint)
                canvas.restore()
            }
        } else {
            val breathe = 0.82f + 0.18f * ((sin(time * 1.4f + index * 1.3f) + 1f) / 2f)
            pillPaint.color = pillGray
            pillPaint.alpha = (255 * ease(t * 1.6f) * breathe).toInt()
            canvas.drawRoundRect(rect, radius, radius, pillPaint)
        }
    }

    /** Tres puntos de "escribiendo…" bajo la última píldora, cuando la conversación ya está. */
    private fun drawTyping(canvas: Canvas, since: Float, float: Float) {
        val appear = ease((since - 1.3f) / 0.4f)
        if (appear <= 0f) return
        val last = pills.last()
        val baseX = originX + box * last.right + box * 0.05f
        val cy = originY + box * last.centerY + float
        val r = pillHeight * 0.17f
        for (i in 0..2) {
            val bounce = ((sin(time * 6f - i * 0.9f) + 1f) / 2f)
            dotPaint.alpha = ((90 + 140 * bounce) * appear).toInt()
            canvas.drawCircle(baseX + i * r * 3.2f, cy - bounce * r * 1.2f, r, dotPaint)
        }
    }

    private fun ease(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return 1f - (1f - c) * (1f - c) * (1f - c)
    }

    private fun overshoot(t: Float): Float {
        if (t >= 1f) return 1f
        return (1f - exp(-6f * t) * cos(8f * t)).coerceAtLeast(0f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateTicker()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateTicker()
    }

    private fun updateTicker() {
        val shouldRun = isAttachedToWindow && isShown && ValueAnimator.areAnimatorsEnabled()
        if (shouldRun && !ticker.isStarted) ticker.start()
        if (!shouldRun && ticker.isStarted) ticker.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            // Sin animaciones del sistema: la conversación aparece completa y quieta.
            time += 10f
            invalidate()
        }
    }
}
