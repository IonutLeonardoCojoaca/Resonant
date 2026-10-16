package com.example.resonant.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Fondo ambiental de Aria, pintado en tres capas con profundidad distinta:
 *
 * 1. Auroras: manchas radiales con los colores de marca que derivan despacio
 *    (lissajous). Al hacer scroll se desplazan según su profundidad y vuelven
 *    a su sitio con un muelle, así que las capas "se separan" mientras te mueves.
 * 2. Ondas: anillos concéntricos que nacen arriba a la derecha y se expanden,
 *    como una vibración que resuena (el motivo de Resonant).
 * 3. Rejilla de puntos: textura fina con parallax real (sigue al scroll a otra
 *    velocidad y se repite sin costuras).
 *
 * [setEnergy] sube el brillo y el ritmo mientras Aria está pensando.
 */
class AriaAmbientView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Mancha de color; posiciones y radios relativos al ancho/alto de la vista. */
    private class Blob(
        val color: Int,
        val cx: Float, val cy: Float, val radius: Float,
        val ampX: Float, val ampY: Float,
        val freqX: Float, val freqY: Float,
        val phase: Float,
        val depth: Float
    ) {
        var shader: RadialGradient? = null
    }

    private val isDark =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private val blobs = listOf(
        Blob(Color.parseColor("#E21616"), 0.08f, 0.06f, 0.78f, 0.10f, 0.05f, 0.050f, 0.037f, 0.0f, 0.35f),
        Blob(Color.parseColor("#7117FF"), 0.96f, 0.30f, 0.72f, 0.08f, 0.07f, 0.041f, 0.053f, 1.7f, 0.60f),
        Blob(Color.parseColor("#FF2EA8"), 0.12f, 0.64f, 0.66f, 0.09f, 0.06f, 0.047f, 0.031f, 3.1f, 0.22f),
        Blob(Color.parseColor("#F21FD8"), 0.88f, 0.96f, 0.82f, 0.07f, 0.05f, 0.029f, 0.043f, 4.4f, 0.45f)
    )

    private val density = resources.displayMetrics.density
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.8f * density
    }
    private val shaderMatrix = Matrix()

    private val baseBlobAlpha = if (isDark) 0.24f else 0.13f
    private val inkColor = if (isDark) Color.WHITE else Color.BLACK
    private val dotSpacing = 26f * density
    private var dotRow = FloatArray(0)

    private var time = 0f
    private var energy = 0f
    private var targetEnergy = 0f

    // Parallax: [scrollOffset] es acumulado (rejilla), [drift] es el desfase de las
    // auroras, que se amortigua hacia 0 cuando dejas de hacer scroll.
    private var scrollOffset = 0f
    private var drift = 0f

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        var last = 0L
        addUpdateListener {
            val now = it.currentPlayTime
            val delta = if (last == 0L || now < last) 16L else (now - last).coerceAtMost(48L)
            last = now
            step(delta / 1000f)
            invalidate()
        }
    }

    /** Desplaza las capas según el scroll (dy en px, mismo signo que RecyclerView.onScrolled). */
    fun onScrollBy(dy: Int) {
        if (dy == 0) return
        scrollOffset += dy
        drift = (drift - dy * 0.35f).coerceIn(-height * 0.18f, height * 0.18f)
        if (!ticker.isRunning) invalidate()
    }

    /** 0 = reposo, 1 = Aria trabajando (más brillo y ritmo). */
    fun setEnergy(value: Float) {
        targetEnergy = value.coerceIn(0f, 1f)
        if (!ticker.isRunning) {
            energy = targetEnergy
            invalidate()
        }
    }

    private fun step(dt: Float) {
        time += dt * (1f + energy * 1.6f)
        energy += (targetEnergy - energy) * (1f - kotlin.math.exp(-dt * 3f))
        drift *= kotlin.math.exp(-dt * 2.6f)
        if (abs(drift) < 0.5f) drift = 0f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val unit = max(w, h).toFloat()
        blobs.forEach { blob ->
            val r = blob.radius * unit
            blob.shader = RadialGradient(
                0f, 0f, r,
                intArrayOf(
                    blob.color,
                    ColorUtils.setAlphaComponent(blob.color, 0x55),
                    ColorUtils.setAlphaComponent(blob.color, 0)
                ),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        dotRow = FloatArray(((w / dotSpacing).toInt() + 2) * 2)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        drawBlobs(canvas, w, h)
        drawRipples(canvas, w, h)
        drawDots(canvas, w, h)
    }

    private fun drawBlobs(canvas: Canvas, w: Float, h: Float) {
        val unit = max(w, h)
        val pulse = 1f + energy * (0.55f + 0.25f * sin(time * 2.2f))
        val alpha = (baseBlobAlpha * pulse).coerceAtMost(0.6f)
        blobs.forEach { blob ->
            val shader = blob.shader ?: return@forEach
            val x = w * blob.cx + w * blob.ampX * sin(time * blob.freqX * 6.283f + blob.phase)
            val y = h * blob.cy + h * blob.ampY * sin(time * blob.freqY * 6.283f + blob.phase * 1.3f) +
                drift * blob.depth
            shaderMatrix.setTranslate(x, y)
            shader.setLocalMatrix(shaderMatrix)
            blobPaint.shader = shader
            blobPaint.alpha = (alpha * 255).toInt()
            canvas.drawCircle(x, y, blob.radius * unit * (1f + energy * 0.08f), blobPaint)
        }
    }

    private fun drawRipples(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.86f
        val cy = h * 0.10f + drift * 0.6f
        val maxR = w * 0.95f
        val period = 7f
        val base = if (isDark) 0.10f else 0.07f
        for (i in 0 until 3) {
            val t = ((time / period) + i / 3f) % 1f
            val r = maxR * t
            val a = base * (1f - t) * (1f + energy * 0.8f)
            ringPaint.color = ColorUtils.setAlphaComponent(
                if (i % 2 == 0) Color.parseColor("#FF2EA8") else inkColor,
                (a.coerceIn(0f, 1f) * 255).toInt()
            )
            canvas.drawCircle(cx, cy, r, ringPaint)
        }
    }

    private fun drawDots(canvas: Canvas, w: Float, h: Float) {
        // Fila absoluta: la alternancia del tresbolillo no salta al repetir la rejilla.
        val base = scrollOffset * 0.25f
        var row = kotlin.math.floor(base / dotSpacing).toInt() - 1
        var y = row * dotSpacing - base
        val maxAlpha = if (isDark) 0.07f else 0.06f
        while (y < h + dotSpacing) {
            // Más visible arriba, se diluye hacia el centro: no compite con el contenido.
            val fade = (1f - (y / (h * 0.55f))).coerceIn(0f, 1f)
            if (fade > 0f) {
                val offsetX = if (Math.floorMod(row, 2) == 0) 0f else dotSpacing / 2f
                var n = 0
                var x = offsetX
                while (x < w + dotSpacing && n + 1 < dotRow.size) {
                    dotRow[n++] = x
                    dotRow[n++] = y
                    x += dotSpacing
                }
                dotPaint.color = ColorUtils.setAlphaComponent(inkColor, (maxAlpha * fade * 255).toInt())
                canvas.drawPoints(dotRow, 0, n, dotPaint)
            }
            y += dotSpacing
            row++
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
