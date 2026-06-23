package com.example.resonant.feature.collabfinder.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.example.resonant.R
import com.example.resonant.feature.collabfinder.domain.model.ArtistSearchItem
import com.example.resonant.feature.collabfinder.domain.model.CollaboratorNode
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class BubbleOrbitView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onBubbleTapped: ((CollaboratorNode) -> Unit)? = null
    var onBubbleLongPressed: ((CollaboratorNode) -> Unit)? = null
    var onCenterTapped: (() -> Unit)? = null

    private var centralArtistBitmap: Bitmap? = null
    private var centralArtistName: String = ""
    private var bubbles: List<BubbleData> = emptyList()
    private var isLoading: Boolean = false
    private var showAmbientMockup: Boolean = true

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isDither = true
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private var floatAnimator: ValueAnimator? = null
    private var loadingAnimator: ValueAnimator? = null
    private var loadingRadius = 0f

    private val accentRed = Color.parseColor("#F51B1F")
    private val dotColor = Color.parseColor("#353535")
    private val emptyBubbleColor = Color.parseColor("#303030")
    private val artistPlaceholder: Drawable? by lazy {
        ContextCompat.getDrawable(context, R.drawable.ic_user)?.mutate()?.apply {
            setTint(Color.parseColor("#6B6B6B"))
        }
    }

    private val collaboratorSlots = listOf(
        BubbleSlot(x = 0.36f, y = 0.11f, radiusDp = 23f),
        BubbleSlot(x = 0.77f, y = 0.14f, radiusDp = 40f),
        BubbleSlot(x = 0.13f, y = 0.42f, radiusDp = 34f),
        BubbleSlot(x = 0.89f, y = 0.58f, radiusDp = 32f),
        BubbleSlot(x = 0.24f, y = 0.89f, radiusDp = 35f),
        BubbleSlot(x = 0.66f, y = 0.87f, radiusDp = 30f)
    )

    private val backgroundDots = listOf(
        DotSpec(0.28f, 0.07f, 5f),
        DotSpec(0.55f, 0.06f, 5f),
        DotSpec(0.21f, 0.16f, 5f),
        DotSpec(0.29f, 0.20f, 13f),
        DotSpec(0.49f, 0.21f, 8f),
        DotSpec(0.58f, 0.16f, 9f),
        DotSpec(0.68f, 0.16f, 13f),
        DotSpec(0.88f, 0.22f, 5f),
        DotSpec(0.26f, 0.29f, 7f),
        DotSpec(0.75f, 0.27f, 10f),
        DotSpec(0.84f, 0.34f, 19f),
        DotSpec(0.94f, 0.41f, 9f),
        DotSpec(0.11f, 0.50f, 11f),
        DotSpec(0.21f, 0.49f, 5f),
        DotSpec(0.17f, 0.56f, 8f),
        DotSpec(0.27f, 0.66f, 13f),
        DotSpec(0.74f, 0.64f, 13f),
        DotSpec(0.15f, 0.74f, 5f),
        DotSpec(0.89f, 0.70f, 7f),
        DotSpec(0.40f, 0.84f, 27f),
        DotSpec(0.51f, 0.78f, 7f),
        DotSpec(0.80f, 0.76f, 20f),
        DotSpec(0.28f, 0.88f, 5f)
    )

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            bubbles.forEach { bubble ->
                val dist = distance(e.x, e.y, bubble.drawX(), bubble.drawY())
                if (dist <= bubble.currentRadius()) {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onBubbleLongPressed?.invoke(bubble.node)
                    return
                }
            }
        }
    })

    data class BubbleData(
        val node: CollaboratorNode,
        var bitmap: Bitmap? = null,
        var x: Float = 0f,
        var y: Float = 0f,
        var radius: Float = 0f,
        var floatOffsetX: Float = 0f,
        var floatOffsetY: Float = 0f,
        var floatPhase: Float = 0f,
        var scale: Float = 1f,
        var baseDistance: Float = 0f,
        var baseAngle: Float = 0f
    )

    private data class BubbleSlot(
        val x: Float,
        val y: Float,
        val radiusDp: Float
    )

    private data class DotSpec(
        val x: Float,
        val y: Float,
        val radiusDp: Float
    )

    fun setData(centralArtist: ArtistSearchItem, collaborators: List<CollaboratorNode>) {
        isLoading = false
        showAmbientMockup = false
        loadingAnimator?.cancel()
        centralArtistBitmap = null
        centralArtistName = centralArtist.name

        bubbles = collaborators
            .take(collaboratorSlots.size)
            .mapIndexed { index, node ->
                BubbleData(
                    node = node,
                    floatPhase = (index * 0.75f) % (2 * PI.toFloat())
                )
            }

        calculateBubblePositions()
        animateBubblesIn()
        loadImages(centralArtist)
    }

    private fun loadImages(centralArtist: ArtistSearchItem) {
        if (!centralArtist.imageUrl.isNullOrEmpty()) {
            Glide.with(context)
                .asBitmap()
                .load(centralArtist.imageUrl)
                .centerCrop()
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        centralArtistBitmap = resource
                        invalidate()
                    }

                    override fun onLoadCleared(placeholder: Drawable?) = Unit
                })
        }

        bubbles.forEach { bubble ->
            if (!bubble.node.imageUrl.isNullOrEmpty()) {
                Glide.with(context)
                    .asBitmap()
                    .load(bubble.node.imageUrl)
                    .centerCrop()
                    .into(object : CustomTarget<Bitmap>() {
                        override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                            bubble.bitmap = resource
                            invalidate()
                        }

                        override fun onLoadCleared(placeholder: Drawable?) = Unit
                    })
            }
        }
    }

    fun setLoading(loading: Boolean) {
        isLoading = loading
        if (loading) {
            showAmbientMockup = false
            startLoadingAnimation()
        } else {
            loadingAnimator?.cancel()
        }
        invalidate()
    }

    private fun calculateBubblePositions() {
        if (width == 0 || height == 0) return

        val centerX = width / 2f
        val centerY = centerY()
        val sidePadding = dp(6f)

        bubbles.forEachIndexed { index, bubble ->
            val slot = collaboratorSlots[index]
            val radius = dp(slot.radiusDp)

            bubble.radius = radius
            bubble.x = (width * slot.x).coerceIn(radius + sidePadding, width - radius - sidePadding)
            bubble.y = (height * slot.y).coerceIn(radius + sidePadding, height - radius - sidePadding)
            bubble.baseDistance = distance(bubble.x, bubble.y, centerX, centerY)
            bubble.baseAngle = atan2(bubble.y - centerY, bubble.x - centerX)
        }
    }

    private fun animateBubblesIn() {
        floatAnimator?.cancel()

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                bubbles.forEach { bubble ->
                    bubble.scale = fraction
                }
                invalidate()
            }
            doOnEnd { startFloatAnimation() }
            start()
        }
    }

    private fun ValueAnimator.doOnEnd(action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                action()
            }
        })
    }

    private fun startFloatAnimation() {
        val horizontalAmplitude = dp(5f)
        val verticalAmplitude = dp(8f)

        floatAnimator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
            duration = 5400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val phase = animator.animatedValue as Float
                bubbles.forEach { bubble ->
                    val bubblePhase = phase + bubble.floatPhase
                    bubble.floatOffsetX = cos(bubblePhase) * horizontalAmplitude
                    bubble.floatOffsetY = sin(phase * 2f + bubble.floatPhase) * verticalAmplitude
                }
                invalidate()
            }
            start()
        }
    }

    private fun startLoadingAnimation() {
        loadingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                loadingRadius = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateBubblePositions()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isLoading) {
            drawLoadingState(canvas)
            return
        }

        if (bubbles.isEmpty()) {
            if (showAmbientMockup) drawAmbientDots(canvas)
            return
        }

        drawBubbles(canvas)
        drawCentralArtist(canvas)
    }

    private fun drawAmbientDots(canvas: Canvas) {
        if (width == 0 || height == 0) return

        val scale = min(width / dp(393f), height / dp(390f)).coerceIn(0.84f, 1.14f)
        paint.alpha = 255
        paint.style = Paint.Style.FILL
        paint.color = dotColor

        backgroundDots.forEach { dot ->
            canvas.drawCircle(
                width * dot.x,
                height * dot.y,
                dp(dot.radiusDp) * scale,
                paint
            )
        }
    }

    private fun drawLoadingState(canvas: Canvas) {
        val cx = width / 2f
        val cy = centerY()
        val maxR = min(width, height) * 0.34f

        paint.style = Paint.Style.STROKE
        paint.color = accentRed

        for (i in 0..2) {
            val phase = (loadingRadius + i * 0.33f) % 1f
            paint.alpha = ((1f - phase) * 190).toInt()
            paint.strokeWidth = dp(2f)
            canvas.drawCircle(cx, cy, phase * maxR, paint)
        }
        paint.alpha = 255
    }

    private fun drawBubbles(canvas: Canvas) {
        bubbles.forEach { bubble ->
            val cx = bubble.drawX()
            val cy = bubble.drawY()
            val radius = bubble.currentRadius()

            paint.alpha = 255
            paint.style = Paint.Style.FILL
            paint.color = emptyBubbleColor
            canvas.drawCircle(cx, cy, radius, paint)

            val bitmap = bubble.bitmap
            if (bitmap != null) {
                drawCircularBitmap(canvas, bitmap, cx, cy, radius)
            } else {
                drawArtistPlaceholder(canvas, cx, cy, radius)
            }

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.4f)
            paint.color = Color.parseColor("#050505")
            canvas.drawCircle(cx, cy, radius, paint)

            drawCollabCountOrb(canvas, cx, cy, radius, bubble.node.collaborationCount)
        }
    }

    private fun drawCollabCountOrb(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        count: Int
    ) {
        if (radius <= 0f) return

        val badgeRadius = (radius * 0.27f).coerceIn(dp(10f), dp(15f))
        val badgeX = cx + radius * 0.68f
        val badgeY = cy - radius * 0.68f
        val label = count.toString()

        paint.alpha = 255
        paint.style = Paint.Style.FILL
        paint.color = accentRed
        canvas.drawCircle(badgeX, badgeY, badgeRadius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.2f)
        paint.color = Color.BLACK
        canvas.drawCircle(badgeX, badgeY, badgeRadius, paint)

        textPaint.textSize = when {
            label.length >= 3 -> badgeRadius * 0.76f
            label.length == 2 -> badgeRadius * 0.92f
            else -> badgeRadius * 1.08f
        }
        val textY = badgeY - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, badgeX, textY, textPaint)
    }

    private fun drawCentralArtist(canvas: Canvas) {
        val cx = width / 2f
        val cy = centerY()
        val radius = centralRadius()

        paint.alpha = 255
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(3f)
        paint.color = accentRed
        canvas.drawCircle(cx, cy, radius + dp(9f), paint)

        paint.strokeWidth = dp(1.25f)
        paint.alpha = 180
        canvas.drawCircle(cx, cy, radius + dp(2.5f), paint)
        paint.alpha = 255

        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#151515")
        canvas.drawCircle(cx, cy, radius, paint)

        val bitmap = centralArtistBitmap
        if (bitmap != null) {
            drawCircularBitmap(canvas, bitmap, cx, cy, radius - dp(1.5f))
        } else {
            drawArtistPlaceholder(canvas, cx, cy, radius - dp(1.5f))
        }
    }

    private fun drawArtistPlaceholder(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float
    ) {
        val drawable = artistPlaceholder ?: return
        val iconSize = (radius * 1.14f).toInt()
        val halfSize = iconSize / 2

        drawable.setBounds(
            (cx - halfSize).toInt(),
            (cy - halfSize).toInt(),
            (cx + halfSize).toInt(),
            (cy + halfSize).toInt()
        )
        drawable.draw(canvas)
    }

    private fun drawCircularBitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        cx: Float,
        cy: Float,
        radius: Float
    ) {
        val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
        val destRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val clipPath = Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) }

        canvas.save()
        canvas.clipPath(clipPath)
        paint.style = Paint.Style.FILL
        canvas.drawBitmap(bitmap, srcRect, destRect, paint)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val centerDist = distance(event.x, event.y, width / 2f, centerY())
            if (centerDist <= centralRadius() + dp(9f)) {
                onCenterTapped?.invoke()
                return true
            }

            bubbles.forEach { bubble ->
                val dist = distance(event.x, event.y, bubble.drawX(), bubble.drawY())
                if (dist <= bubble.currentRadius()) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    animateBubbleTap(bubble)
                    onBubbleTapped?.invoke(bubble.node)
                    return true
                }
            }
        }

        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun animateBubbleTap(bubble: BubbleData) {
        ValueAnimator.ofFloat(1f, 0.86f, 1f).apply {
            duration = 280
            addUpdateListener { anim ->
                bubble.scale = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun BubbleData.currentRadius(): Float = radius * scale

    private fun BubbleData.drawX(): Float = x + floatOffsetX

    private fun BubbleData.drawY(): Float = y + floatOffsetY

    private fun centralRadius(): Float {
        val byWidth = width * 0.195f
        val byHeight = height * 0.185f
        return min(min(byWidth, byHeight), dp(74f)).coerceAtLeast(dp(56f))
    }

    private fun centerY(): Float = height * 0.52f

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return sqrt((x1 - x2).pow(2) + (y1 - y2).pow(2))
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        floatAnimator?.cancel()
        loadingAnimator?.cancel()
    }
}
