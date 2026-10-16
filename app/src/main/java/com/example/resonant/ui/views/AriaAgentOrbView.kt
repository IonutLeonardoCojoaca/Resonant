package com.example.resonant.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.resonant.R
import kotlin.math.min
import kotlin.math.sin

/**
 * Orbe de presencia de Aria: núcleo con el degradado de marca girando y la
 * estrella de Aria en blanco. En reposo gira despacio; con [setThinking] acelera,
 * respira y emite un halo, para que se note que el agente está trabajando.
 */
class AriaAgentOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val brand = intArrayOf(
        Color.parseColor("#FF1F3D"),
        Color.parseColor("#F21FD8"),
        Color.parseColor("#7117FF"),
        Color.parseColor("#FF2EA8"),
        Color.parseColor("#FF1F3D")
    )

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val star: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_star_ai)?.mutate()?.apply {
        setTint(Color.WHITE)
    }

    private var sweep: SweepGradient? = null
    private var angle = 0f
    private var time = 0f
    private var thinking = false
    private var energy = 0f

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        var last = 0L
        addUpdateListener {
            val now = it.currentPlayTime
            val delta = if (last == 0L || now < last) 16L else (now - last).coerceAtMost(48L)
            last = now
            val dt = delta / 1000f
            energy += ((if (thinking) 1f else 0f) - energy) * min(1f, dt * 4f)
            angle = (angle + dt * (24f + 220f * energy)) % 360f
            time += dt
            invalidate()
        }
    }

    fun setThinking(value: Boolean) {
        if (thinking == value) return
        thinking = value
        if (!ticker.isRunning) {
            energy = if (value) 1f else 0f
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        sweep = SweepGradient(cx, cy, brand, null)
        corePaint.shader = sweep
        val r = min(w, h) / 2f
        haloPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.parseColor("#FF2EA8"), 0x88),
                ColorUtils.setAlphaComponent(Color.parseColor("#FF2EA8"), 0)
            ),
            floatArrayOf(0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        sheenPaint.shader = RadialGradient(
            cx - r * 0.25f, cy - r * 0.3f, r * 0.7f,
            intArrayOf(0x55FFFFFF, 0x00FFFFFF),
            null,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        if (r <= 0f) return

        // Halo: solo cuando trabaja, respirando.
        if (energy > 0.01f) {
            haloPaint.alpha = (energy * (0.55f + 0.45f * sin(time * 5f)) * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, r, haloPaint)
        }

        val coreR = r * (0.74f + 0.04f * energy * sin(time * 6f))
        matrix.setRotate(angle, cx, cy)
        sweep?.setLocalMatrix(matrix)
        canvas.drawCircle(cx, cy, coreR, corePaint)
        canvas.drawCircle(cx, cy, coreR, sheenPaint)

        star?.let {
            val s = (coreR * 0.95f).toInt()
            it.setBounds((cx - s / 2f).toInt(), (cy - s / 2f).toInt(), (cx + s / 2f).toInt(), (cy + s / 2f).toInt())
            canvas.save()
            canvas.rotate(-angle * 0.25f, cx, cy)
            it.draw(canvas)
            canvas.restore()
        }
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
    }
}
