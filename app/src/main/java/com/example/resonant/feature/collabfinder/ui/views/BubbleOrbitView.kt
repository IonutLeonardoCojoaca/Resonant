package com.example.resonant.feature.collabfinder.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.example.resonant.R
import com.example.resonant.feature.collabfinder.domain.model.ArtistSearchItem
import com.example.resonant.feature.collabfinder.domain.model.CollaboratorNode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Mapa de órbitas de Collab Finder: artista central rodeado de sus colaboradores.
 *
 * Todo lo animado (entrada escalonada, flotado, pulso de carga, fundido de imágenes,
 * orbes ambientales) sale de un único reloj, así que no hay animadores que compitan
 * entre sí ni saltos de posición: el flotado crece desde 0 a la vez que cada orbe
 * entra. Los estados se encadenan sin cambiar de vista: reposo/carga muestran el
 * motivo de órbitas (orbes ambientales + radar) y al llegar datos esos orbes se
 * desvanecen mientras crecen los colaboradores.
 */
class BubbleOrbitView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onBubbleTapped: ((CollaboratorNode) -> Unit)? = null
    var onBubbleLongPressed: ((CollaboratorNode) -> Unit)? = null
    var onCenterTapped: (() -> Unit)? = null

    @ColorInt
    var accentColor: Int = 0xFF8C9DFF.toInt()
        set(value) {
            field = value
            shadersDirty = true
            invalidate()
        }

    private class Bubble(val node: CollaboratorNode, val index: Int) {
        var x = 0f
        var y = 0f
        var radius = 0f
        var depth = 0f
        var bitmap: Bitmap? = null
        var shader: BitmapShader? = null
        var imageLoadedAt = -1f
        var tapStartedAt = -1f
        var target: CustomTarget<Bitmap>? = null
        val phase = (index * 0.75f) % (2f * PI.toFloat())
        // Posición final dibujada en el último frame (para el hit testing).
        var drawX = 0f
        var drawY = 0f
        var drawRadius = 0f
    }

    private class Slot(val x: Float, val y: Float, val radiusDp: Float)

    private companion object {
        /** Tiempo tras el cual toda la coreografía de entrada ha terminado. */
        const val ENTRANCE_DONE_S = 3f
    }

    private val slots = listOf(
        Slot(0.34f, 0.13f, 26f),
        Slot(0.77f, 0.15f, 38f),
        Slot(0.13f, 0.44f, 33f),
        Slot(0.88f, 0.55f, 31f),
        Slot(0.25f, 0.84f, 34f),
        Slot(0.68f, 0.85f, 29f)
    )

    /** Orbes ambientales (mismo motivo que la tarjeta de Explorar), en radios de anillo. */
    private val ambient = floatArrayOf(
        1.17f, -1.66f, 0.44f, -0.61f, -1.74f, 0.27f, 0.43f, -1.51f, 0.15f, -1.04f, -1.29f, 0.16f,
        -0.53f, -1.22f, 0.08f, -1.16f, -0.87f, 0.08f, -1.73f, -0.61f, 0.40f, -1.94f, 0.06f, 0.14f,
        -1.63f, 0.35f, 0.08f, -1.23f, 0.59f, 0.16f, -1.39f, 1.25f, 0.34f, -0.53f, 1.64f, 0.35f,
        0.00f, 1.39f, 0.08f, 0.65f, 1.60f, 0.38f, 1.36f, 1.27f, 0.25f, 1.13f, 0.74f, 0.16f,
        1.65f, 0.22f, 0.37f, 1.44f, -0.73f, 0.24f, 1.00f, -1.02f, 0.12f, 1.83f, 1.15f, 0.08f
    )

    private var central: ArtistSearchItem? = null
    private var centralBitmap: Bitmap? = null
    private var centralShader: BitmapShader? = null
    private var centralLoadedAt = -1f
    private var centralTarget: CustomTarget<Bitmap>? = null
    private var bubbles: List<Bubble> = emptyList()

    private var isLoading = false
    private var dataShownAt = -1f
    private var loadingStartedAt = 0f
    private var time = 0f
    private var parallax = 0f

    private val density = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val badgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.unageo_bold)
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.white)
        textAlign = Paint.Align.CENTER
        textSize = 10.5f * density
        typeface = ResourcesCompat.getFont(context, R.font.unageo_medium)
    }
    private val shaderMatrix = Matrix()
    private val placeholderFill by lazy { ContextCompat.getColor(context, R.color.cardsTheme) }
    private val artistPlaceholder: Drawable? by lazy {
        ContextCompat.getDrawable(context, R.drawable.ic_user)?.mutate()?.apply {
            setTint(ContextCompat.getColor(context, R.color.textTheme))
        }
    }

    private var shadersDirty = true
    private var haloShader: Shader? = null
    private val glyphClip = Path()

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

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            hitBubble(e.x, e.y)?.let {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onBubbleLongPressed?.invoke(it.node)
            }
        }
    })

    // ─────────────────────────────────────────────── API

    /**
     * @param animate false para mostrar el resultado ya colocado (p. ej. al recrear la
     * vista al volver a la pantalla), sin repetir la entrada.
     */
    fun setData(centralArtist: ArtistSearchItem, collaborators: List<CollaboratorNode>, animate: Boolean = true) {
        isLoading = false
        val shown = collaborators.take(slots.size)
        val sameData = central?.id == centralArtist.id &&
            bubbles.map { it.node.id } == shown.map { it.id } &&
            dataShownAt >= 0f
        if (sameData) {
            // Mismo resultado (p. ej. al volver a la pantalla): no se re-anima nada.
            invalidate()
            return
        }

        clearImageRequests()
        central = centralArtist
        centralBitmap = null
        centralShader = null
        centralLoadedAt = -1f
        bubbles = shown.mapIndexed { index, node -> Bubble(node, index) }
        dataShownAt = if (animate) time else time - ENTRANCE_DONE_S
        layoutBubbles()
        loadImages(centralArtist)
        updateTicker()
        invalidate()
    }

    fun setLoading(loading: Boolean) {
        if (loading == isLoading) return
        isLoading = loading
        if (loading) {
            loadingStartedAt = time
            // Mientras carga se vuelve al motivo de órbitas (sin artista central).
            clearImageRequests()
            bubbles = emptyList()
            central = null
            centralBitmap = null
            centralShader = null
            dataShownAt = -1f
        }
        updateTicker()
        invalidate()
    }

    /** Desplazamiento de scroll del contenedor: las capas lejanas se quedan atrás. */
    fun setParallax(scrollOffset: Float) {
        if (parallax == scrollOffset) return
        parallax = scrollOffset
        invalidate()
    }

    // ─────────────────────────────────────────────── Layout

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shadersDirty = true
        layoutBubbles()
    }

    private fun layoutBubbles() {
        if (width == 0 || height == 0) return
        val side = 6 * density
        val labelSpace = 18 * density
        bubbles.forEach { bubble ->
            val slot = slots[bubble.index]
            val radius = slot.radiusDp * density
            bubble.radius = radius
            bubble.x = (width * slot.x).coerceIn(radius + side, width - radius - side)
            bubble.y = (height * slot.y).coerceIn(radius + side, height - radius - side - labelSpace)
            // Los orbes pequeños parecen más lejanos: se quedan más atrás con el parallax.
            bubble.depth = 1f - ((slot.radiusDp - 26f) / 12f).coerceIn(0f, 1f)
        }
    }

    private fun centerX() = width / 2f
    private fun centerY() = height * 0.5f
    private fun centralRadius(): Float =
        min(min(width * 0.18f, height * 0.17f), 66 * density).coerceAtLeast(50 * density)

    // ─────────────────────────────────────────────── Images

    private fun loadImages(centralArtist: ArtistSearchItem) {
        val centralSize = (centralRadius() * 2).toInt().coerceAtLeast(64)
        centralTarget = loadCircle(centralArtist.imageUrl, centralSize) { bitmap ->
            centralBitmap = bitmap
            centralShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            centralLoadedAt = time
        }
        bubbles.forEach { bubble ->
            val size = (slots[bubble.index].radiusDp * 2 * density).toInt()
            bubble.target = loadCircle(bubble.node.imageUrl, size) { bitmap ->
                bubble.bitmap = bitmap
                bubble.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                bubble.imageLoadedAt = time
            }
        }
    }

    private fun loadCircle(url: String?, sizePx: Int, onReady: (Bitmap) -> Unit): CustomTarget<Bitmap>? {
        if (url.isNullOrBlank()) return null
        val target = object : CustomTarget<Bitmap>(sizePx, sizePx) {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                onReady(resource)
                invalidate()
            }

            override fun onLoadCleared(placeholder: Drawable?) = Unit
        }
        Glide.with(this).asBitmap().load(url).centerCrop().into(target)
        return target
    }

    private fun clearImageRequests() {
        val glide = Glide.with(this)
        centralTarget?.let(glide::clear)
        centralTarget = null
        bubbles.forEach { bubble -> bubble.target?.let(glide::clear) }
    }

    // ─────────────────────────────────────────────── Drawing

    private fun rebuildShaders() {
        val cx = centerX()
        val cy = centerY()
        haloShader = RadialGradient(
            cx, cy, centralRadius() * 2.6f,
            ColorUtils.setAlphaComponent(accentColor, 0x55), 0x00000000, Shader.TileMode.CLAMP
        )
        shadersDirty = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        if (shadersDirty) rebuildShaders()

        val hasData = bubbles.isNotEmpty() || central != null
        val reveal = if (hasData && dataShownAt >= 0f) ease((time - dataShownAt) / 0.7f) else 0f

        drawHalo(canvas)
        // Orbes ambientales: presentes en reposo/carga, se desvanecen al llegar datos.
        drawAmbient(canvas, alpha = 1f - reveal)
        if (isLoading || !hasData) drawRadar(canvas, intensity = if (isLoading) 1f else 0.45f)

        if (hasData) {
            drawConnections(canvas)
            bubbles.forEach { drawBubble(canvas, it) }
        }
        drawCenter(canvas)
    }

    private fun drawHalo(canvas: Canvas) {
        fillPaint.shader = haloShader
        fillPaint.alpha = 255
        canvas.drawCircle(centerX(), centerY() + parallax * 0.12f, centralRadius() * 2.6f, fillPaint)
        fillPaint.shader = null
    }

    private fun drawAmbient(canvas: Canvas, alpha: Float) {
        if (alpha <= 0.01f) return
        val unit = centralRadius() * 1.12f
        val cx = centerX()
        val cy = centerY() + parallax * 0.35f
        var i = 0
        while (i < ambient.size) {
            val n = i / 3
            val wobble = 0.05f * unit
            val x = cx + ambient[i] * unit + sin(time * (0.6f + n % 5 * 0.12f) + n * 0.73f) * wobble
            val y = cy + ambient[i + 1] * unit + sin(time * 0.5f + n * 1.24f) * wobble
            val r = ambient[i + 2] * unit
            val strength = (ambient[i + 2] / 0.44f).coerceIn(0.25f, 1f)
            fillPaint.color = ColorUtils.blendARGB(accentColor, Color.BLACK, 0.55f - 0.25f * strength)
            fillPaint.alpha = ((70 + 90 * strength) * alpha).toInt()
            canvas.drawCircle(x, y, r, fillPaint)
            i += 3
        }
    }

    private fun drawRadar(canvas: Canvas, intensity: Float) {
        val maxR = centralRadius() * 2.4f
        val since = time - loadingStartedAt
        strokePaint.strokeWidth = 2 * density
        for (i in 0..2) {
            val phase = ((since / 1.8f) + i / 3f) % 1f
            strokePaint.color = ColorUtils.setAlphaComponent(accentColor, ((1f - phase) * 170 * intensity).toInt())
            canvas.drawCircle(centerX(), centerY() + parallax * 0.12f, centralRadius() + phase * (maxR - centralRadius()), strokePaint)
        }
    }

    private fun bubbleProgress(bubble: Bubble): Float {
        if (dataShownAt < 0f) return 0f
        return ((time - dataShownAt - 0.15f - bubble.index * 0.09f) / 0.75f).coerceIn(0f, 1f)
    }

    private fun drawConnections(canvas: Canvas) {
        val maxCount = bubbles.maxOfOrNull { it.node.collaborationCount }?.coerceAtLeast(1) ?: 1
        val cx = centerX()
        val cy = centerY() + parallax * 0.12f
        bubbles.forEach { bubble ->
            val p = ease(bubbleProgress(bubble))
            if (p <= 0f) return@forEach
            positionBubble(bubble)
            val weight = bubble.node.collaborationCount / maxCount.toFloat()
            linePaint.strokeWidth = (1f + 2f * weight) * density
            linePaint.color = ColorUtils.setAlphaComponent(accentColor, ((40 + 110 * weight) * p).toInt())
            // La línea "crece" del centro al colaborador durante su entrada.
            canvas.drawLine(cx, cy, cx + (bubble.drawX - cx) * p, cy + (bubble.drawY - cy) * p, linePaint)
        }
    }

    private fun positionBubble(bubble: Bubble) {
        val progress = bubbleProgress(bubble)
        val entrance = overshoot(progress)
        // El flotado crece con la entrada: nunca hay un salto al terminarla.
        val amp = ease(progress)
        val fx = cos(time * 1.16f + bubble.phase) * 5 * density * amp
        val fy = sin(time * 2.32f + bubble.phase) * 7 * density * amp
        val tap = tapScale(bubble)
        bubble.drawX = bubble.x + fx
        bubble.drawY = bubble.y + fy + parallax * (0.06f + 0.16f * bubble.depth)
        bubble.drawRadius = bubble.radius * entrance * tap
    }

    private fun drawBubble(canvas: Canvas, bubble: Bubble) {
        positionBubble(bubble)
        val r = bubble.drawRadius
        if (r <= 0.5f) return
        val cx = bubble.drawX
        val cy = bubble.drawY

        fillPaint.color = placeholderFill
        fillPaint.alpha = 255
        canvas.drawCircle(cx, cy, r, fillPaint)

        val shader = bubble.shader
        val imageAlpha = if (bubble.imageLoadedAt >= 0f) ease((time - bubble.imageLoadedAt) / 0.3f) else 0f
        if (imageAlpha < 1f) drawPlaceholder(canvas, cx, cy, r, 1f - imageAlpha)
        if (shader != null && bubble.bitmap != null) {
            drawImage(canvas, shader, bubble.bitmap!!, cx, cy, r, imageAlpha)
        }

        strokePaint.strokeWidth = 1.5f * density
        strokePaint.color = ColorUtils.setAlphaComponent(accentColor, 0x99)
        canvas.drawCircle(cx, cy, r, strokePaint)

        drawCountBadge(canvas, cx, cy, r, bubble.node.collaborationCount)

        val labelAlpha = ((bubbleProgress(bubble) - 0.5f) * 2f).coerceIn(0f, 1f)
        if (labelAlpha > 0f) {
            labelPaint.alpha = (215 * labelAlpha).toInt()
            val label = TextUtils.ellipsize(bubble.node.name, labelPaint, r * 2.6f, TextUtils.TruncateAt.END)
            canvas.drawText(label, 0, label.length, cx, cy + bubble.radius + 14 * density, labelPaint)
        }
    }

    private fun drawCountBadge(canvas: Canvas, cx: Float, cy: Float, r: Float, count: Int) {
        val badgeR = (r * 0.3f).coerceIn(9 * density, 13 * density)
        val bx = cx + r * 0.7f
        val by = cy - r * 0.7f
        fillPaint.color = ColorUtils.blendARGB(accentColor, Color.BLACK, 0.25f)
        fillPaint.alpha = 255
        canvas.drawCircle(bx, by, badgeR, fillPaint)
        strokePaint.strokeWidth = 1.5f * density
        strokePaint.color = Color.BLACK
        canvas.drawCircle(bx, by, badgeR, strokePaint)
        val label = count.toString()
        badgeTextPaint.textSize = badgeR * if (label.length >= 3) 0.78f else if (label.length == 2) 0.95f else 1.1f
        canvas.drawText(label, bx, by - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f, badgeTextPaint)
    }

    private fun drawCenter(canvas: Canvas) {
        val cx = centerX()
        val cy = centerY() + parallax * 0.12f
        val radius = centralRadius()
        // Respira suavemente mientras busca.
        val breathe = if (isLoading) 1f + sin(time * 3f) * 0.025f else 1f

        strokePaint.strokeWidth = 2.5f * density
        strokePaint.color = ColorUtils.setAlphaComponent(accentColor, 0xCC)
        canvas.drawCircle(cx, cy, (radius + 8 * density) * breathe, strokePaint)
        strokePaint.strokeWidth = 1f * density
        strokePaint.color = ColorUtils.setAlphaComponent(accentColor, 0x66)
        canvas.drawCircle(cx, cy, (radius + 2.5f * density) * breathe, strokePaint)

        fillPaint.color = ColorUtils.blendARGB(accentColor, Color.BLACK, 0.72f)
        fillPaint.alpha = 255
        canvas.drawCircle(cx, cy, radius * breathe, fillPaint)

        val imageAlpha = if (centralLoadedAt >= 0f) ease((time - centralLoadedAt) / 0.35f) else 0f
        if (imageAlpha < 1f) drawPersonGlyph(canvas, cx, cy, radius * breathe, 1f - imageAlpha)
        val shader = centralShader
        val bitmap = centralBitmap
        if (shader != null && bitmap != null) drawImage(canvas, shader, bitmap, cx, cy, radius - 1.5f * density, imageAlpha)
    }

    /** Silueta de persona del prototipo (cabeza + hombros) dentro del círculo central. */
    private fun drawPersonGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Float) {
        fillPaint.color = ColorUtils.blendARGB(accentColor, Color.BLACK, 0.9f)
        fillPaint.alpha = (255 * alpha).toInt()
        canvas.save()
        glyphClip.reset()
        glyphClip.addCircle(cx, cy, r * 0.97f, Path.Direction.CW)
        canvas.clipPath(glyphClip)
        canvas.drawCircle(cx, cy - r * 0.27f, r * 0.34f, fillPaint)
        canvas.drawOval(cx - r * 0.62f, cy + r * 0.2f, cx + r * 0.62f, cy + r * 1.25f, fillPaint)
        canvas.restore()
    }

    private fun drawPlaceholder(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Float) {
        val drawable = artistPlaceholder ?: return
        val half = (r * 0.57f).toInt()
        drawable.alpha = (255 * alpha).toInt()
        drawable.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        drawable.draw(canvas)
    }

    private fun drawImage(canvas: Canvas, shader: BitmapShader, bitmap: Bitmap, cx: Float, cy: Float, r: Float, alpha: Float) {
        if (alpha <= 0f) return
        val scale = (2 * r) / min(bitmap.width, bitmap.height)
        shaderMatrix.setScale(scale, scale)
        shaderMatrix.postTranslate(cx - bitmap.width * scale / 2f, cy - bitmap.height * scale / 2f)
        shader.setLocalMatrix(shaderMatrix)
        imagePaint.shader = shader
        imagePaint.alpha = (255 * alpha).toInt()
        canvas.drawCircle(cx, cy, r, imagePaint)
    }

    // ─────────────────────────────────────────────── Touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val centerDist = hypot(event.x - centerX(), event.y - (centerY() + parallax * 0.12f))
            if (central != null && centerDist <= centralRadius() + 9 * density) {
                onCenterTapped?.invoke()
                return true
            }
            hitBubble(event.x, event.y)?.let { bubble ->
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                bubble.tapStartedAt = time
                onBubbleTapped?.invoke(bubble.node)
                return true
            }
        }
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun hitBubble(x: Float, y: Float): Bubble? =
        bubbles.firstOrNull { hypot(x - it.drawX, y - it.drawY) <= it.drawRadius + 4 * density }

    private fun tapScale(bubble: Bubble): Float {
        if (bubble.tapStartedAt < 0f) return 1f
        val t = (time - bubble.tapStartedAt) / 0.28f
        if (t >= 1f) return 1f
        return 1f - 0.14f * sin(t * PI.toFloat())
    }

    // ─────────────────────────────────────────────── Easing / lifecycle

    private fun ease(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return 1f - (1f - c) * (1f - c) * (1f - c)
    }

    /** Escala con un pequeño rebote amortiguado al final (sin pasar de ~8%). */
    private fun overshoot(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        return (1f - exp(-6f * t) * cos(9f * t)).coerceAtLeast(0f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateTicker()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        clearImageRequests()
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
            // Sin animaciones del sistema: todo directamente en su estado final.
            time += 10f
            invalidate()
        }
    }
}
