package com.example.resonant.feature.collabfinder.ui.views

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
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.example.resonant.R
import kotlin.math.max

class CollabDuoBannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bitmapA: Bitmap? = null
    private var bitmapB: Bitmap? = null

    private val clipPathA = Path()
    private val clipPathB = Path()
    private val boundsRect = RectF()
    private val cornerRadius = 18f * resources.displayMetrics.density

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    private val dividerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    private val bottomGradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val matrixA = Matrix()
    private val matrixB = Matrix()

    private val placeholderDrawable: Drawable? by lazy {
        ContextCompat.getDrawable(context, R.drawable.ic_user)?.mutate()
    }

    // Split ratio at top and bottom for a dynamic diagonal slice
    private val splitTopRatio = 0.56f
    private val splitBottomRatio = 0.44f

    fun loadArtists(urlA: String?, urlB: String?) {
        loadBitmap(urlA) {
            bitmapA = it
            invalidate()
        }
        loadBitmap(urlB) {
            bitmapB = it
            invalidate()
        }
    }

    private fun loadBitmap(url: String?, onLoaded: (Bitmap?) -> Unit) {
        if (url.isNullOrBlank()) {
            onLoaded(null)
            return
        }
        Glide.with(context)
            .asBitmap()
            .load(url)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    onLoaded(resource)
                }
                override fun onLoadCleared(placeholder: Drawable?) {
                    onLoaded(null)
                }
            })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        boundsRect.set(0f, 0f, w.toFloat(), h.toFloat())

        val splitTop = w * splitTopRatio
        val splitBottom = w * splitBottomRatio

        // Path for Left Artist A
        clipPathA.reset()
        clipPathA.moveTo(0f, 0f)
        clipPathA.lineTo(splitTop, 0f)
        clipPathA.lineTo(splitBottom, h.toFloat())
        clipPathA.lineTo(0f, h.toFloat())
        clipPathA.close()

        // Path for Right Artist B
        clipPathB.reset()
        clipPathB.moveTo(splitTop, 0f)
        clipPathB.lineTo(w.toFloat(), 0f)
        clipPathB.lineTo(w.toFloat(), h.toFloat())
        clipPathB.lineTo(splitBottom, h.toFloat())
        clipPathB.close()

        // Diagonal Divider Gradient with accent color
        val accentColor = ContextCompat.getColor(context, R.color.secondaryColorTheme)
        val r = Color.red(accentColor)
        val g = Color.green(accentColor)
        val b = Color.blue(accentColor)

        dividerPaint.shader = LinearGradient(
            splitTop, 0f,
            splitBottom, h.toFloat(),
            intArrayOf(
                Color.argb(230, r, g, b),
                Color.argb(200, 255, 255, 255),
                Color.argb(230, r, g, b)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        dividerGlowPaint.shader = LinearGradient(
            splitTop, 0f,
            splitBottom, h.toFloat(),
            intArrayOf(
                Color.argb(60, r, g, b),
                Color.argb(40, 255, 255, 255),
                Color.argb(60, r, g, b)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        // Bottom vignette gradient for contrast
        bottomGradientPaint.shader = LinearGradient(
            0f, h * 0.45f,
            0f, h.toFloat(),
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(90, 0, 0, 0),
                Color.argb(190, 0, 0, 0)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val fullRoundedPath = Path().apply {
            addRoundRect(boundsRect, cornerRadius, cornerRadius, Path.Direction.CW)
        }

        val saveFull = canvas.save()
        canvas.clipPath(fullRoundedPath)

        placeholderPaint.color = ContextCompat.getColor(context, R.color.discTheme)
        canvas.drawRect(boundsRect, placeholderPaint)

        // --- DRAW ARTIST A (Left half) ---
        val saveA = canvas.save()
        canvas.clipPath(clipPathA)
        bitmapA?.let { bmp ->
            drawBitmapScaled(canvas, bmp, 0f, 0f, w * splitTopRatio + 30f, h, matrixA)
        } ?: run {
            drawPlaceholder(canvas, 0f, 0f, w * splitTopRatio, h)
        }
        canvas.restoreToCount(saveA)

        // --- DRAW ARTIST B (Right half) ---
        val saveB = canvas.save()
        canvas.clipPath(clipPathB)
        bitmapB?.let { bmp ->
            drawBitmapScaled(canvas, bmp, w * splitBottomRatio - 30f, 0f, w, h, matrixB)
        } ?: run {
            drawPlaceholder(canvas, w * splitBottomRatio, 0f, w, h)
        }
        canvas.restoreToCount(saveB)

        // --- BOTTOM VIGNETTE OVERLAY ---
        canvas.drawRect(boundsRect, bottomGradientPaint)

        // --- DIAGONAL DIVIDER SEPARATOR LINE ---
        val splitTop = w * splitTopRatio
        val splitBottom = w * splitBottomRatio
        canvas.drawLine(splitTop, 0f, splitBottom, h, dividerGlowPaint)
        canvas.drawLine(splitTop, 0f, splitBottom, h, dividerPaint)

        canvas.restoreToCount(saveFull)
    }

    private fun drawBitmapScaled(
        canvas: Canvas,
        bitmap: Bitmap,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        matrix: Matrix
    ) {
        val targetWidth = right - left
        val targetHeight = bottom - top
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()

        val scale = max(targetWidth / bw, targetHeight / bh)
        val scaledWidth = bw * scale
        val scaledHeight = bh * scale

        val dx = left + (targetWidth - scaledWidth) / 2f
        val dy = top + (targetHeight - scaledHeight) / 2f

        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(dx, dy)

        canvas.drawBitmap(bitmap, matrix, imagePaint)
    }

    private fun drawPlaceholder(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        placeholderDrawable?.let { d ->
            val iconSize = (50f * resources.displayMetrics.density).toInt()
            val cx = ((left + right) / 2f).toInt()
            val cy = ((top + bottom) / 2f).toInt()
            d.setBounds(cx - iconSize / 2, cy - iconSize / 2, cx + iconSize / 2, cy + iconSize / 2)
            d.setTint(ContextCompat.getColor(context, R.color.textTertiaryAlpha))
            d.draw(canvas)
        }
    }
}
