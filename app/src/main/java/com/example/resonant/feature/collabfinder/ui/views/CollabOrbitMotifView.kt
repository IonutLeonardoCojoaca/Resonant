package com.example.resonant.feature.collabfinder.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Motivo visual de Collab Finder (basado en el prototipo de Figma): un avatar central
 * con anillo rodeado de orbes de distintos tamaños que flotan suavemente, y un pulso
 * tipo radar que sale del anillo. Es puramente decorativo (no recibe datos).
 *
 * La animación solo corre mientras la vista está adjunta y visible, y respeta la
 * escala de animaciones del sistema (si están desactivadas, se dibuja estático).
 */
class CollabOrbitMotifView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * Orbes en unidades relativas al radio del anillo central (= 1): desplazamiento
     * desde el centro (dx, dy) y radio. Sacados de la composición del prototipo.
     */
    private class Orb(val dx: Float, val dy: Float, val r: Float, val phase: Float, val speed: Float)

    private val orbs: List<Orb> = listOf(
        floatArrayOf(1.17f, -1.66f, 0.44f), floatArrayOf(-0.61f, -1.74f, 0.27f),
        floatArrayOf(0.43f, -1.51f, 0.15f), floatArrayOf(-0.07f, -1.51f, 0.11f),
        floatArrayOf(-1.04f, -1.29f, 0.16f), floatArrayOf(-0.53f, -1.22f, 0.08f),
        floatArrayOf(0.20f, -1.85f, 0.05f), floatArrayOf(-1.08f, -1.81f, 0.05f),
        floatArrayOf(-1.44f, -1.45f, 0.05f), floatArrayOf(-1.16f, -0.87f, 0.08f),
        floatArrayOf(-1.73f, -0.61f, 0.40f), floatArrayOf(-1.94f, 0.06f, 0.14f),
        floatArrayOf(-1.44f, 0.02f, 0.05f), floatArrayOf(-1.63f, 0.35f, 0.08f),
        floatArrayOf(-1.23f, 0.59f, 0.16f), floatArrayOf(-1.73f, 0.76f, 0.05f),
        floatArrayOf(-1.39f, 1.25f, 0.34f), floatArrayOf(-1.10f, 1.78f, 0.05f),
        floatArrayOf(-0.53f, 1.64f, 0.35f), floatArrayOf(0.00f, 1.39f, 0.08f),
        floatArrayOf(0.65f, 1.60f, 0.38f), floatArrayOf(1.36f, 1.27f, 0.25f),
        floatArrayOf(1.13f, 0.74f, 0.16f), floatArrayOf(1.83f, 1.15f, 0.08f),
        floatArrayOf(1.78f, 0.80f, 0.08f), floatArrayOf(1.65f, 0.22f, 0.37f),
        floatArrayOf(1.44f, -0.73f, 0.24f), floatArrayOf(1.00f, -1.02f, 0.12f),
        floatArrayOf(1.68f, -1.14f, 0.05f)
    ).mapIndexed { i, (dx, dy, r) ->
        // Fase y velocidad deterministas por orbe: el movimiento no parece sincronizado
        // pero siempre es el mismo (sin aleatoriedad entre redibujados).
        Orb(dx, dy, r, phase = (i * 0.73f) % (2f * PI.toFloat()), speed = 0.6f + (i % 5) * 0.12f)
    }

    @ColorInt
    var accentColor: Int = 0xFF8C9DFF.toInt()
        set(value) {
            field = value
            shadersDirty = true
            invalidate()
        }

    /** Amplitud del flotado en unidades de anillo. */
    var floatAmplitude: Float = 0.05f

    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arcRect = RectF()
    private val avatarClip = Path()
    private var orbShaders: List<Shader> = emptyList()

    private var unit = 0f
    private var centerX = 0f
    private var centerY = 0f
    private var shadersDirty = true
    private var time = 0f

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        var last = 0L
        addUpdateListener {
            val now = it.currentPlayTime
            val delta = if (last == 0L || now < last) 16L else now - last
            last = now
            time += delta / 1000f
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val contentW = (w - paddingLeft - paddingRight).toFloat()
        val contentH = (h - paddingTop - paddingBottom).toFloat()
        // La composición mide ~4.2 x 4.1 radios de anillo.
        unit = min(contentW / 4.2f, contentH / 4.1f)
        centerX = paddingLeft + contentW / 2f
        centerY = paddingTop + contentH / 2f
        shadersDirty = true
    }

    private fun rebuildShaders() {
        val accent = accentColor
        avatarPaint.shader = RadialGradient(
            centerX - unit * 0.3f, centerY - unit * 0.35f, unit * 1.1f,
            ColorUtils.blendARGB(accent, Color.WHITE, 0.08f),
            ColorUtils.blendARGB(accent, Color.BLACK, 0.55f),
            Shader.TileMode.CLAMP
        )
        glyphPaint.color = ColorUtils.blendARGB(accent, Color.BLACK, 0.82f)
        ringPaint.color = ColorUtils.setAlphaComponent(accent, 0x8C)
        ringPaint.strokeWidth = unit * 0.035f
        pulsePaint.strokeWidth = unit * 0.03f
        avatarClip.reset()
        avatarClip.addCircle(centerX, centerY, unit * 0.86f * 0.96f, Path.Direction.CW)
        // Un shader por orbe, centrado en el origen: al dibujar se traslada el lienzo,
        // así no se crea ningún objeto por fotograma.
        orbShaders = orbs.map { orb ->
            val radius = orb.r * unit
            val strength = (orb.r / 0.44f).coerceIn(0.25f, 1f)
            RadialGradient(
                -radius * 0.35f, -radius * 0.4f, radius * 1.4f,
                ColorUtils.setAlphaComponent(ColorUtils.blendARGB(accent, Color.WHITE, 0.12f), (110 + 110 * strength).toInt()),
                ColorUtils.setAlphaComponent(ColorUtils.blendARGB(accent, Color.BLACK, 0.6f), (60 + 90 * strength).toInt()),
                Shader.TileMode.CLAMP
            )
        }
        shadersDirty = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (unit <= 0f) return
        if (shadersDirty) rebuildShaders()
        val accent = accentColor

        // Orbes: los grandes más opacos y cálidos, los pequeños como polvo de fondo.
        val wobble = floatAmplitude * unit
        orbs.forEachIndexed { i, orb ->
            val x = centerX + orb.dx * unit + sin(time * orb.speed + orb.phase) * wobble
            val y = centerY + orb.dy * unit + sin(time * orb.speed * 0.8f + orb.phase * 1.7f) * wobble
            orbPaint.shader = orbShaders[i]
            canvas.save()
            canvas.translate(x, y)
            canvas.drawCircle(0f, 0f, orb.r * unit, orbPaint)
            canvas.restore()
        }

        // Pulso tipo radar desde el anillo (cada 2.4 s).
        val pulse = (time % 2.4f) / 2.4f
        pulsePaint.color = ColorUtils.setAlphaComponent(accent, ((1f - pulse) * 120).toInt())
        canvas.drawCircle(centerX, centerY, unit * (1f + pulse * 0.55f), pulsePaint)

        // Anillo + avatar central con silueta.
        canvas.drawCircle(centerX, centerY, unit, ringPaint)
        val avatarR = unit * 0.86f
        canvas.drawCircle(centerX, centerY, avatarR, avatarPaint)
        canvas.save()
        canvas.clipPath(avatarClip)
        canvas.drawCircle(centerX, centerY - avatarR * 0.27f, avatarR * 0.34f, glyphPaint)
        arcRect.set(centerX - avatarR * 0.62f, centerY + avatarR * 0.2f, centerX + avatarR * 0.62f, centerY + avatarR * 1.25f)
        canvas.drawOval(arcRect, glyphPaint)
        canvas.restore()
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
