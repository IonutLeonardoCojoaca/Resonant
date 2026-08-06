package com.example.resonant.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Lightweight, code-native login artwork inspired by sound waves and a record.
 *
 * It replaces the old full-screen video, allocates its drawing objects once and
 * stops animating whenever the window is not visible.
 */
class ResonantFlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Particle(
        val xRatio: Float,
        val yRatio: Float,
        val radius: Float,
        val speed: Float,
        val alpha: Int,
        val offset: Float
    )

    private val density = resources.displayMetrics.density
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val redGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val secondaryGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val equalizerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.WHITE
    }
    private val accentRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
        color = ACCENT
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 11f * density), 0f)
    }

    private val ribbonPaths = Array(RIBBON_COUNT) { Path() }
    private val orbitBounds = RectF()
    private var particles: List<Particle> = emptyList()
    private var phase = 0.12f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = ANIMATION_DURATION_MS
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        addUpdateListener {
            phase = it.animatedValue as Float
            postInvalidateOnAnimation()
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width == 0 || height == 0) return

        backgroundPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            height.toFloat(),
            intArrayOf(
                Color.rgb(4, 4, 5),
                Color.rgb(10, 6, 8),
                Color.rgb(4, 4, 6)
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )

        val glowRadius = max(width, height) * 0.66f
        redGlowPaint.shader = RadialGradient(
            width * 0.82f,
            height * 0.18f,
            glowRadius,
            intArrayOf(
                Color.argb(88, 226, 22, 22),
                Color.argb(28, 146, 0, 16),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.38f, 1f),
            Shader.TileMode.CLAMP
        )
        secondaryGlowPaint.shader = RadialGradient(
            width * 0.08f,
            height * 0.62f,
            glowRadius * 0.75f,
            intArrayOf(
                Color.argb(38, 115, 12, 62),
                Color.argb(14, 73, 8, 35),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.44f, 1f),
            Shader.TileMode.CLAMP
        )
        ribbonPaint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            0f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(180, 134, 5, 18),
                Color.argb(235, 226, 22, 22),
                Color.argb(135, 255, 86, 92),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.18f, 0.5f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )

        val random = Random(PARTICLE_SEED)
        particles = List(PARTICLE_COUNT) {
            Particle(
                xRatio = random.nextFloat(),
                yRatio = random.nextFloat(),
                radius = (0.45f + random.nextFloat() * 1.1f) * density,
                speed = 0.018f + random.nextFloat() * 0.035f,
                alpha = 26 + random.nextInt(74),
                offset = random.nextFloat()
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val widthF = width.toFloat()
        val heightF = height.toFloat()
        canvas.drawRect(0f, 0f, widthF, heightF, backgroundPaint)
        canvas.drawCircle(widthF * 0.82f, heightF * 0.18f, max(widthF, heightF) * 0.66f, redGlowPaint)
        canvas.drawCircle(widthF * 0.08f, heightF * 0.62f, max(widthF, heightF) * 0.5f, secondaryGlowPaint)

        drawParticles(canvas, widthF, heightF)
        drawOrbit(canvas, widthF, heightF)
        drawRibbons(canvas, widthF, heightF)
        drawEqualizer(canvas, widthF, heightF)
    }

    private fun drawParticles(canvas: Canvas, width: Float, height: Float) {
        particles.forEach { particle ->
            val animatedY = (particle.yRatio + phase * particle.speed * 14f) % 1f
            val shimmer = 0.48f + 0.52f * sin(
                ((phase + particle.offset) * TWO_PI).toDouble()
            ).toFloat()
            particlePaint.alpha = (particle.alpha * shimmer).toInt().coerceIn(8, 100)
            canvas.drawCircle(
                particle.xRatio * width,
                animatedY * height,
                particle.radius,
                particlePaint
            )
        }
    }

    private fun drawOrbit(canvas: Canvas, width: Float, height: Float) {
        val centerX = width * 0.5f
        val centerY = height * 0.285f
        val radius = min(width * 0.34f, height * 0.15f)

        ringPaint.alpha = 25
        canvas.drawCircle(centerX, centerY, radius, ringPaint)
        ringPaint.alpha = 16
        canvas.drawCircle(centerX, centerY, radius * 0.72f, ringPaint)

        orbitBounds.set(
            centerX - radius * 1.12f,
            centerY - radius * 1.12f,
            centerX + radius * 1.12f,
            centerY + radius * 1.12f
        )
        canvas.save()
        canvas.rotate(phase * 100f, centerX, centerY)
        accentRingPaint.alpha = 92
        canvas.drawArc(orbitBounds, 204f, 112f, false, accentRingPaint)
        accentRingPaint.alpha = 42
        canvas.drawArc(orbitBounds, 24f, 74f, false, accentRingPaint)
        canvas.restore()

        val pulse = radius * (
            0.1f + 0.018f * sin((phase * TWO_PI).toDouble()).toFloat()
        )
        val pulsePaint = ringPaint
        pulsePaint.color = ACCENT
        pulsePaint.alpha = 45
        pulsePaint.strokeWidth = 1.2f * density
        canvas.drawCircle(centerX, centerY, pulse, pulsePaint)
        pulsePaint.color = Color.WHITE
        pulsePaint.strokeWidth = 1f * density
    }

    private fun drawRibbons(canvas: Canvas, width: Float, height: Float) {
        val step = max(8f * density, width / 74f)

        repeat(RIBBON_COUNT) { index ->
            val path = ribbonPaths[index]
            path.reset()
            val baseY = height * (0.40f + index * 0.032f)
            val amplitude = height * (0.013f + index * 0.0045f)
            var x = -step
            var first = true
            while (x <= width + step) {
                val normalizedX = (x / width).coerceIn(0f, 1f)
                val envelope = 0.22f + 0.78f *
                    sin((normalizedX * PI).toFloat()).coerceAtLeast(0f)
                val primaryWave = sin(
                    normalizedX * TWO_PI * 1.35f +
                        phase * TWO_PI +
                        index * 0.72f.toDouble()
                ).toFloat()
                val secondaryWave = cos(
                    normalizedX * TWO_PI * 0.62f -
                        phase * TWO_PI * 0.42f +
                        index.toDouble()
                ).toFloat()
                val y = baseY +
                    primaryWave * amplitude * envelope +
                    secondaryWave * amplitude * 0.24f
                if (first) {
                    path.moveTo(x, y)
                    first = false
                } else {
                    path.lineTo(x, y)
                }
                x += step
            }
            ribbonPaint.alpha = (118 - index * 15).coerceAtLeast(42)
            ribbonPaint.strokeWidth = (1.05f + index * 0.32f) * density
            canvas.drawPath(path, ribbonPaint)
        }
    }

    private fun drawEqualizer(canvas: Canvas, width: Float, height: Float) {
        val barCount = 31
        val startX = width * 0.12f
        val endX = width * 0.88f
        val spacing = (endX - startX) / (barCount - 1)
        val centerY = height * 0.59f

        equalizerPaint.strokeWidth = 1.15f * density
        equalizerPaint.alpha = 28
        repeat(barCount) { index ->
            val normalized = index.toFloat() / (barCount - 1)
            val envelope = sin(normalized * PI).toFloat().coerceAtLeast(0.12f)
            val movement = 0.5f + 0.5f * sin(
                (
                    normalized * TWO_PI * 2.1f +
                        phase * TWO_PI * 1.4f
                    ).toDouble()
            ).toFloat()
            val barHeight = height * (0.006f + movement * 0.028f * envelope)
            val x = startX + index * spacing
            canvas.drawLine(x, centerY - barHeight, x, centerY + barHeight, equalizerPaint)
        }
    }

    private fun startIfNeeded() {
        if (!isShown || windowVisibility != VISIBLE || animator.isRunning) return
        if (ValueAnimator.areAnimatorsEnabled()) {
            animator.start()
        } else {
            phase = 0.12f
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startIfNeeded()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            startIfNeeded()
        } else {
            animator.cancel()
        }
    }

    companion object {
        private const val RIBBON_COUNT = 5
        private const val PARTICLE_COUNT = 42
        private const val PARTICLE_SEED = 731_911
        private const val ANIMATION_DURATION_MS = 16_000L
        private const val TWO_PI = (PI * 2.0).toFloat()
        private val ACCENT = Color.rgb(226, 22, 22)
    }
}
