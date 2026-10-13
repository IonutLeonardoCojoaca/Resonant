package com.example.resonant.feature.collabfinder.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.example.resonant.R
import com.example.resonant.ui.components.ResonantMotion
import kotlin.math.max

/**
 * Banner doble de Collab Finder: las fotos de los dos artistas separadas por un corte
 * diagonal con brillo de acento. Entra deslizando cada mitad desde su lado, las fotos
 * aparecen con fundido y tiene parallax interno ([setParallax]).
 */
class CollabDuoBannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    @ColorInt
    var accentColor: Int = 0xFF8C9DFF.toInt()
        set(value) {
            field = value
            if (width > 0) buildShaders(width, height)
            invalidate()
        }

    private class Side {
        var url: String? = null
        var bitmap: Bitmap? = null
        var alpha = 0f
        var target: CustomTarget<Bitmap>? = null
        var fade: ValueAnimator? = null
        val matrix = Matrix()
    }

    private val sideA = Side()
    private val sideB = Side()

    private val density = resources.displayMetrics.density
    private val clipPathA = Path()
    private val clipPathB = Path()
    private val roundedPath = Path()
    private val boundsRect = RectF()
    private val cornerRadius = 22f * density

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val dividerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val bottomGradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val placeholderDrawable: Drawable? by lazy {
        ContextCompat.getDrawable(context, R.drawable.ic_user)?.mutate()?.apply {
            setTint(ContextCompat.getColor(context, R.color.textTertiaryAlpha))
        }
    }

    private val splitTopRatio = 0.56f
    private val splitBottomRatio = 0.44f
    /** Margen extra de las fotos para que el parallax nunca deje bordes vacíos. */
    private val parallaxOverscan = 1.16f

    private var reveal = 1f
    private var revealAnimator: ValueAnimator? = null
    private var parallax = 0f

    /** Idempotente: si las URLs no cambian no se vuelve a pedir ni a parpadear nada. */
    fun loadArtists(urlA: String?, urlB: String?) {
        load(sideA, urlA)
        load(sideB, urlB)
    }

    /** Entrada: cada mitad se desliza desde su lado y la línea diagonal se dibuja. */
    fun animateIn() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        revealAnimator?.cancel()
        reveal = 0f
        revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            interpolator = ResonantMotion.expoOut
            addUpdateListener {
                reveal = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Desplazamiento de scroll del contenedor: las fotos se mueven más despacio. */
    fun setParallax(scrollOffset: Float) {
        parallax = scrollOffset
        invalidate()
    }

    private fun load(side: Side, url: String?) {
        if (url == side.url && (side.bitmap != null || url == null)) return
        side.url = url
        side.target?.let { Glide.with(this).clear(it) }
        side.target = null
        if (url.isNullOrBlank()) {
            side.bitmap = null
            side.alpha = 0f
            invalidate()
            return
        }
        val w = if (width > 0) (width * 0.62f * parallaxOverscan).toInt() else 600
        val h = if (height > 0) (height * parallaxOverscan).toInt() else 400
        val target = object : CustomTarget<Bitmap>(w, h) {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                side.bitmap = resource
                fadeIn(side)
            }

            override fun onLoadCleared(placeholder: Drawable?) = Unit
        }
        side.target = target
        Glide.with(this).asBitmap().load(url).centerCrop()
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).into(target)
    }

    private fun fadeIn(side: Side) {
        side.fade?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            side.alpha = 1f
            invalidate()
            return
        }
        side.fade = ValueAnimator.ofFloat(side.alpha, 1f).apply {
            duration = 420
            addUpdateListener {
                side.alpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        boundsRect.set(0f, 0f, w.toFloat(), h.toFloat())
        roundedPath.reset()
        roundedPath.addRoundRect(boundsRect, cornerRadius, cornerRadius, Path.Direction.CW)

        val splitTop = w * splitTopRatio
        val splitBottom = w * splitBottomRatio
        clipPathA.reset()
        clipPathA.moveTo(0f, 0f)
        clipPathA.lineTo(splitTop, 0f)
        clipPathA.lineTo(splitBottom, h.toFloat())
        clipPathA.lineTo(0f, h.toFloat())
        clipPathA.close()

        clipPathB.reset()
        clipPathB.moveTo(splitTop, 0f)
        clipPathB.lineTo(w.toFloat(), 0f)
        clipPathB.lineTo(w.toFloat(), h.toFloat())
        clipPathB.lineTo(splitBottom, h.toFloat())
        clipPathB.close()

        buildShaders(w, h)
    }

    private fun buildShaders(w: Int, h: Int) {
        val splitTop = w * splitTopRatio
        val splitBottom = w * splitBottomRatio
        dividerPaint.shader = LinearGradient(
            splitTop, 0f, splitBottom, h.toFloat(),
            intArrayOf(accentColor, Color.argb(220, 255, 255, 255), accentColor),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        dividerGlowPaint.shader = LinearGradient(
            splitTop, 0f, splitBottom, h.toFloat(),
            intArrayOf(
                ColorUtils.setAlphaComponent(accentColor, 90),
                Color.argb(50, 255, 255, 255),
                ColorUtils.setAlphaComponent(accentColor, 90)
            ),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        bottomGradientPaint.shader = LinearGradient(
            0f, h * 0.4f, 0f, h.toFloat(),
            intArrayOf(Color.TRANSPARENT, Color.argb(100, 0, 0, 0), Color.argb(215, 0, 0, 0)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        placeholderPaint.color = ColorUtils.blendARGB(accentColor, Color.BLACK, 0.8f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val save = canvas.save()
        canvas.clipPath(roundedPath)
        canvas.drawRect(boundsRect, placeholderPaint)

        // Cada mitad entra desde su lado; las fotos se mueven a menos velocidad que el scroll.
        val slide = (1f - reveal) * w * 0.22f
        val maxShift = h * (parallaxOverscan - 1f) / 2f
        val shift = (parallax * 0.22f).coerceIn(-maxShift, maxShift)

        drawSide(canvas, sideA, clipPathA, -slide, shift, 0f, w * splitTopRatio + 30f, h)
        drawSide(canvas, sideB, clipPathB, slide, shift, w * splitBottomRatio - 30f, w, h)

        canvas.drawRect(boundsRect, bottomGradientPaint)

        // La diagonal crece desde el centro durante la entrada.
        val splitTop = w * splitTopRatio
        val splitBottom = w * splitBottomRatio
        val midX = (splitTop + splitBottom) / 2f
        val midY = h / 2f
        val p = reveal
        val x0 = midX + (splitTop - midX) * p
        val y0 = midY - midY * p
        val x1 = midX + (splitBottom - midX) * p
        val y1 = midY + (h - midY) * p
        canvas.drawLine(x0, y0, x1, y1, dividerGlowPaint)
        canvas.drawLine(x0, y0, x1, y1, dividerPaint)

        canvas.restoreToCount(save)
    }

    private fun drawSide(
        canvas: Canvas,
        side: Side,
        clip: Path,
        offsetX: Float,
        offsetY: Float,
        left: Float,
        right: Float,
        h: Float
    ) {
        val save = canvas.save()
        canvas.clipPath(clip)
        canvas.translate(offsetX, 0f)
        val bitmap = side.bitmap
        if (side.alpha < 1f) drawPlaceholder(canvas, left, right, h, 1f - side.alpha)
        if (bitmap != null && side.alpha > 0f) {
            val targetW = (right - left) * parallaxOverscan
            val targetH = h * parallaxOverscan
            val scale = max(targetW / bitmap.width, targetH / bitmap.height)
            val dx = left + (right - left - bitmap.width * scale) / 2f
            val dy = (h - bitmap.height * scale) / 2f + offsetY
            side.matrix.reset()
            side.matrix.postScale(scale, scale)
            side.matrix.postTranslate(dx, dy)
            imagePaint.alpha = (255 * side.alpha).toInt()
            canvas.drawBitmap(bitmap, side.matrix, imagePaint)
        }
        canvas.restoreToCount(save)
    }

    private fun drawPlaceholder(canvas: Canvas, left: Float, right: Float, h: Float, alpha: Float) {
        val d = placeholderDrawable ?: return
        val iconSize = (48f * density).toInt()
        val cx = ((left + right) / 2f).toInt()
        val cy = (h / 2f).toInt()
        d.alpha = (255 * alpha).toInt()
        d.setBounds(cx - iconSize / 2, cy - iconSize / 2, cx + iconSize / 2, cy + iconSize / 2)
        d.draw(canvas)
    }

    override fun onDetachedFromWindow() {
        revealAnimator?.cancel()
        sideA.fade?.cancel()
        sideB.fade?.cancel()
        super.onDetachedFromWindow()
    }
}
