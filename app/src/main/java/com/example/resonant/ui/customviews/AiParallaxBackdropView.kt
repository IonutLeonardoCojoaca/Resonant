package com.example.resonant.ui.customviews

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Fondo de la pantalla de generación de Crear con IA: manchas de luz (degradados radiales) en tres profundidades
 * que derivan a velocidades distintas y se desplazan con la inclinación del móvil ([setTilt]) — las más cercanas
 * se mueven más —, con un grano fino encima. [setEnergy] acelera la deriva mientras se genera; [setPalette] funde
 * los colores (p. ej. hacia los de la portada al terminar).
 */
class AiParallaxBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Blob(
        val depth: Float,
        val baseX: Float,
        val baseY: Float,
        val radius: Float,
        val speed: Float,
        val phase: Float,
        var colorIndex: Int
    )

    private val blobs = listOf(
        Blob(depth = 0.35f, baseX = 0.2f, baseY = 0.25f, radius = 0.75f, speed = 0.6f, phase = 0f, colorIndex = 0),
        Blob(depth = 0.55f, baseX = 0.85f, baseY = 0.3f, radius = 0.6f, speed = 0.8f, phase = 1.7f, colorIndex = 1),
        Blob(depth = 0.75f, baseX = 0.3f, baseY = 0.8f, radius = 0.55f, speed = 1.0f, phase = 3.1f, colorIndex = 2),
        Blob(depth = 1.0f, baseX = 0.8f, baseY = 0.85f, radius = 0.4f, speed = 1.3f, phase = 4.4f, colorIndex = 3)
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grainPaint = Paint().apply { alpha = 22 }
    private val evaluator = ArgbEvaluator()

    private var colors = intArrayOf(0xFF2B1E5C.toInt(), 0xFF6A3FA0.toInt(), 0xFFFF7A59.toInt(), 0xFF2FD3C9.toInt())
    private var fromColors = colors.copyOf()
    private var toColors = colors.copyOf()
    private var paletteMix = 1f

    private var time = 0f
    private var energy = 0.4f
    private var tiltX = 0f
    private var tiltY = 0f
    private var lastFrameNanos = 0L

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { advance() }
    }

    init {
        setBackgroundColor(BACKGROUND)
        grainPaint.shader = BitmapShader(grainBitmap(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    /** Inclinación normalizada (-1..1) en cada eje; las capas cercanas se desplazan más. */
    fun setTilt(x: Float, y: Float) {
        tiltX = x.coerceIn(-1f, 1f)
        tiltY = y.coerceIn(-1f, 1f)
    }

    /** 0 = reposo, 1 = generando a tope. */
    fun setEnergy(value: Float) {
        energy = value.coerceIn(0f, 1f)
    }

    /** Funde hacia una paleta nueva (al menos 1 color; se repiten si hay menos de 4). */
    fun setPalette(newColors: List<Int>, animate: Boolean = true) {
        if (newColors.isEmpty()) return
        fromColors = currentColors()
        toColors = IntArray(blobs.size) { newColors[it % newColors.size] }
        paletteMix = if (animate) 0f else 1f
        if (!animate) colors = toColors.copyOf()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameNanos = 0L
        ticker.start()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        super.onDetachedFromWindow()
    }

    private fun advance() {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / 1_000_000_000f).coerceAtMost(0.1f)
        lastFrameNanos = now
        time += dt * (0.15f + 0.85f * energy)
        if (paletteMix < 1f) {
            paletteMix = (paletteMix + dt / PALETTE_FADE_S).coerceAtMost(1f)
            colors = currentColors()
        }
        invalidate()
    }

    private fun currentColors(): IntArray = IntArray(blobs.size) {
        evaluator.evaluate(paletteMix, fromColors[it % fromColors.size], toColors[it % toColors.size]) as Int
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val size = max(w, h)
        blobs.forEach { blob ->
            val drift = 0.06f * blob.depth
            val cx = w * (blob.baseX + drift * sin(time * blob.speed + blob.phase)) + tiltX * PARALLAX_PX * blob.depth
            val cy = h * (blob.baseY + drift * cos(time * blob.speed * 0.8f + blob.phase)) + tiltY * PARALLAX_PX * blob.depth
            val radius = size * blob.radius * (0.92f + 0.08f * sin(time * 1.3f + blob.phase))
            val color = colors[blob.colorIndex % colors.size]
            paint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(withAlpha(color, 0.85f), withAlpha(color, 0.35f), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius, paint)
        }
        canvas.drawRect(0f, 0f, w, h, grainPaint)
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((Color.alpha(color) * alpha).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun grainBitmap(): Bitmap {
        val random = Random(7)
        val bitmap = Bitmap.createBitmap(GRAIN_SIZE, GRAIN_SIZE, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(GRAIN_SIZE * GRAIN_SIZE) {
            val v = random.nextInt(256)
            Color.argb(255, v, v, v)
        }
        bitmap.setPixels(pixels, 0, GRAIN_SIZE, 0, 0, GRAIN_SIZE, GRAIN_SIZE)
        return bitmap
    }

    private companion object {
        const val BACKGROUND = 0xFF07070C.toInt()
        const val PARALLAX_PX = 90f
        const val PALETTE_FADE_S = 1.6f
        const val GRAIN_SIZE = 128
    }
}
