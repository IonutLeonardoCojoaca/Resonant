package com.example.resonant.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Interactive dual-lane waveform editor.
 *
 * - Top half  = Song A (exiting). Drag left/right to move the exit point.
 * - Bottom half= Song B (entering). Drag left/right to move the entry point.
 * - A fixed center line acts as the scrub head; dragging scrolls the waveform under it.
 * - On finger-up the view snaps to the nearest BPM beat in the grid.
 * - The crossfade zone is highlighted and both waveforms are drawn overlapping there.
 * - A thin white playback cursor is updated via [setPlaybackPositionMs].
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Zoom ──────────────────────────────────────────────────────────────────
    /** Pixels per millisecond. Default = ~0.05 → a 3-min song is 9000 px wide. */
    var pixelsPerMs: Float = 0.05f
        set(v) {
            field = v.coerceIn(ZOOM_MIN, ZOOM_MAX)
            onZoomChanged?.invoke(field)
            invalidate()
        }

    var onZoomChanged: ((Float) -> Unit)? = null

    companion object {
        const val ZOOM_MIN = 0.0001f // muy alejado
        const val ZOOM_MAX = 1f      // muy cerca
        const val ZOOM_DEFAULT = 0.05f
        private const val AUTO_TRANSITION_WIDTH_RATIO = 0.68f
    }

    // ── Mix mode & curve ──────────────────────────────────────────────────────
    var mixMode: String = "crossfade"
        set(v) { field = v; invalidate() }
    var fadeCurveType: String = "linear"
        set(v) { field = v; invalidate() }
    var canvasBackgroundColor: Int = Color.BLACK
        set(v) { field = v; invalidate() }

    /** Per-band fade curve types: bass, mid, treble. Each can be linear/logarithmic/exponential/hold/cut. */
    var bandFadeTypeBass: String = "linear"
        set(v) { field = v; invalidate() }
    var bandFadeTypeMid: String = "linear"
        set(v) { field = v; invalidate() }
    var bandFadeTypeTreble: String = "linear"
        set(v) { field = v; invalidate() }

    // ── Song data ─────────────────────────────────────────────────────────────
    private var amplitudesA: List<Float> = emptyList()
    private var amplitudesB: List<Float> = emptyList()
    private var durationA: Int = 0
    private var durationB: Int = 0
    private var beatGridA: List<Int> = emptyList()
    private var beatGridB: List<Int> = emptyList()

    // ── Shared scroll offset ──────────────────────────────────────────────────
    // sharedOffsetMs: ms offset (negative = marker is to the RIGHT of left edge).
    // Both lanes compute their effective offset as: markerMs + sharedOffsetMs.
    private var sharedOffsetMs: Float = 0f
    // Float accumulators to avoid rounding drift while dragging
    private var exitPointMsF: Float = 0f
    private var entryPointMsF: Float = 0f

    // ── Transition points ─────────────────────────────────────────────────────
    private var exitPointMs: Int = 0
    private var entryPointMs: Int = 0
    private var crossfadeDurationMs: Int = 8000

    // ── Playback cursor ───────────────────────────────────────────────────────
    private var playbackPositionAMs: Float = -1f
    private var playbackPositionBMs: Float = -1f

    // ── Snap to beat ─────────────────────────────────────────────────────────
    var snapToBeatEnabled: Boolean = true

    // ── Callbacks ─────────────────────────────────────────────────────────────
    var onExitChanged: ((Int) -> Unit)? = null
    var onEntryChanged: ((Int) -> Unit)? = null
    var onScrubbing: ((exitMs: Int, entryMs: Int) -> Unit)? = null

    // ── Touch state ───────────────────────────────────────────────────────────
    private var touchingTopHalf = true
    private var lastTouchX = 0f
    private var isDragging = false

    // ── Zoom gesture ──────────────────────────────────────────────────────────
    // ── Paints ────────────────────────────────────────────────────────────────
    private val paintWaveA = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC1ED760")
        style = Paint.Style.FILL
    }
    private val paintWaveAOverlap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AA1ED760")
        style = Paint.Style.FILL
    }
    private val paintWaveB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC40C4FF")
        style = Paint.Style.FILL
    }
    private val paintWaveBOverlap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AA40C4FF")
        style = Paint.Style.FILL
    }
    private val paintDivider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44FFFFFF")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val paintCenterA = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1ED760")
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }
    private val paintCenterB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF40C4FF")
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }
    private val paintCfZoneA = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#221ED760")
        style = Paint.Style.FILL
    }
    private val paintCfZoneB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2240C4FF")
        style = Paint.Style.FILL
    }
    private val paintBeatA = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#551ED760")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val paintBeatB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5540C4FF")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val paintBeatSnap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9900FF88")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f)
    }
    private val paintCursor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DDFFFFFF")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val paintTimecode = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88FFFFFF")
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }
    private val paintOverlapLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAFFFFFF")
        textSize = 20f
        textAlign = Paint.Align.CENTER
    }
    private val paintSnapIndicator = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC00FF88")
        style = Paint.Style.FILL
    }

    // ── Minimap (navigation scrollbar) ─────────────────────────────────────
    private val minimapHeightPx get() = 22f * resources.displayMetrics.density
    private val paintMinimapBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33000000")
        style = Paint.Style.FILL
    }
    private val paintMinimapViewport = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
        style = Paint.Style.FILL
    }
    private val paintMinimapViewportBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFFFFF")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val paintMinimapMarkerA = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1ED760")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val paintMinimapMarkerB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF40C4FF")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val paintMinimapWaveA = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#661ED760")
        style = Paint.Style.FILL
    }
    private val paintMinimapWaveB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6640C4FF")
        style = Paint.Style.FILL
    }
    private var isDraggingMinimap = false
    private var minimapTouchingTop = true

    private val paintCfBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44FFFFFF")
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }
    private val paintDimOverlay = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#77000000") // Darker outside the transition window
        style = Paint.Style.FILL
    }
    private val paintAutoBass = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFF5030")
        strokeWidth = 4.1f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val paintAutoMid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFF9820")
        strokeWidth = 3.0f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f)
    }
    private val paintAutoTreble = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC40C4FF")
        strokeWidth = 2.2f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(3f, 3f), 0f)
    }
    private val paintBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val paintTransitionWindow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#221ED760")
        style = Paint.Style.FILL
    }
    private val paintHighlightBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC1ED760")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeJoin = Paint.Join.ROUND
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun setWaveformData(
        ampA: List<Float>,
        ampB: List<Float>,
        durA: Int,
        durB: Int,
        beatsA: List<Int>?,
        beatsB: List<Int>?
    ) {
        amplitudesA = ampA
        amplitudesB = ampB
        durationA = durA
        durationB = durB
        beatGridA = beatsA ?: emptyList()
        beatGridB = beatsB ?: emptyList()
        exitPointMsF = exitPointMs.toFloat()
        entryPointMsF = entryPointMs.toFloat()
        recenterOffsets()
        invalidate()
    }

    fun setTransitionPoints(exitMs: Int, entryMs: Int, crossfadeMs: Int) {
        if (isDragging) {
            // During drag the user owns the positions; only update cosmetic crossfade width
            crossfadeDurationMs = crossfadeMs
            invalidate()
            return
        }
        val positionChanged = exitMs != exitPointMs || entryMs != entryPointMs
        val durationChanged = crossfadeMs != crossfadeDurationMs
        exitPointMs = exitMs
        entryPointMs = entryMs
        exitPointMsF = exitMs.toFloat()
        entryPointMsF = entryMs.toFloat()
        crossfadeDurationMs = crossfadeMs
        if (positionChanged || durationChanged) {
            recenterOffsets()
        }
        invalidate()
    }

    fun setPlaybackPositionMs(posMs: Float) {
        playbackPositionAMs = posMs
        invalidate()
    }

    fun setPlaybackPosition(songAMs: Float, songBMs: Float) {
        playbackPositionAMs = songAMs
        playbackPositionBMs = songBMs
        invalidate()
    }

    /** Not needed in new design but kept for API compatibility. */
    fun setBpmRatios(ratioA: Float, ratioB: Float) { invalidate() }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun halfWindowMs(): Float = if (width > 0) width / 2f / pixelsPerMs else 5000f

    fun recenterOffsets() {
        if (width == 0) return
        val w = width.toFloat()
        val markerX: Float
        if (crossfadeDurationMs > 0) {
            val targetCfPx = w * AUTO_TRANSITION_WIDTH_RATIO
            pixelsPerMs = (targetCfPx / crossfadeDurationMs).coerceIn(ZOOM_MIN, ZOOM_MAX)
            val cfPx = crossfadeDurationMs * pixelsPerMs
            markerX = ((w - cfPx) / 2f).coerceAtLeast(0f)
        } else {
            markerX = w * 0.5f
        }
        sharedOffsetMs = -markerX / pixelsPerMs
        exitPointMsF = exitPointMs.toFloat()
        entryPointMsF = entryPointMs.toFloat()
    }

    private fun clampOffsets() {
        // No clamping – let the user scroll freely
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recenterOffsets()
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val mmH = 0f
        val halfH = h / 2f
        val cfPx = crossfadeDurationMs * pixelsPerMs

        paintBackground.shader = null
        paintBackground.color = canvasBackgroundColor
        canvas.drawRect(0f, 0f, w, h, paintBackground)

        // Unified single-scroll: both lanes anchored to the transition point.
        // sharedOffsetMs is the ms-from-transition-start at the LEFT edge of the screen.
        val offsetAMs = exitPointMs.toFloat() + sharedOffsetMs
        val offsetBMs = entryPointMs.toFloat() + sharedOffsetMs
        // EXIT and ENTRY markers are at the same screen x (they define time=0 of the transition)
        val markerX = -sharedOffsetMs * pixelsPerMs
        val markerAx = markerX
        val markerBx = markerX

        // Waveform drawing areas (above minimap)
        val waveTopA = 0f
        val waveBotA = halfH - mmH
        val waveTopB = halfH
        val waveBotB = h - mmH

        // ── Dim non-mix zones ─────────────────────────────────────────────────
        // We dim everything outside the transition window.
        val transLeft = markerAx.coerceAtLeast(0f)
        val transRight = (markerAx + cfPx).coerceAtMost(w)

        if (cfPx > 0) {
            val winRect = RectF(markerAx, waveTopA + 4f, markerAx + cfPx, waveBotB - 4f)
            canvas.drawRoundRect(winRect, 16f, 16f, paintTransitionWindow)
        }

        // Waveforms
        drawScrolledWaveform(canvas, amplitudesA, durationA, offsetAMs, waveTopA, waveBotA, paintWaveA)
        drawScrolledWaveform(canvas, amplitudesB, durationB, offsetBMs, waveTopB, waveBotB, paintWaveB)

        // Dim overlays
        if (transLeft > 0f) {
            canvas.drawRect(0f, 0f, transLeft, h, paintDimOverlay)
        }
        if (transRight < w) {
            canvas.drawRect(transRight, 0f, w, h, paintDimOverlay)
        }

        if (cfPx > 0) {
            val winRect = RectF(markerAx, waveTopA + 4f, markerAx + cfPx, waveBotB - 4f)
            canvas.drawRoundRect(winRect, 16f, 16f, paintHighlightBorder)
        }

        // Beat grids
        drawBeatGrid(canvas, beatGridA, durationA, offsetAMs, waveTopA, waveBotA, w, paintBeatA)
        drawBeatGrid(canvas, beatGridB, durationB, offsetBMs, waveTopB, waveBotB, w, paintBeatB)

        // Nearest-beat snap guides
        if (isDragging && !isDraggingMinimap && touchingTopHalf && beatGridA.isNotEmpty()) {
            val nearestMs = snapToNearestBeat(exitPointMs, beatGridA)
            val nearX = (nearestMs - offsetAMs) * pixelsPerMs
            if (nearX in 0f..w) canvas.drawLine(nearX, waveTopA, nearX, waveBotA, paintBeatSnap)
        }
        if (isDragging && !isDraggingMinimap && !touchingTopHalf && beatGridB.isNotEmpty()) {
            val nearestMs = snapToNearestBeat(entryPointMs, beatGridB)
            val nearX = (nearestMs - offsetBMs) * pixelsPerMs
            if (nearX in 0f..w) canvas.drawLine(nearX, waveTopB, nearX, waveBotB, paintBeatSnap)
        }

        // ── Minimaps ──────────────────────────────────────────────────────────
        // ── Divider ──────────────────────────────────────────────────────────
        canvas.drawLine(0f, halfH, w, halfH, paintDivider)

        // ── Playback cursors ──────────────────────────────────────────────────
        if (playbackPositionAMs >= 0) {
            val curAx = (playbackPositionAMs - offsetAMs) * pixelsPerMs
            if (curAx in 0f..w) canvas.drawLine(curAx, waveTopA, curAx, waveBotA, paintCursor)
        }
        if (playbackPositionBMs >= 0) {
            val curBx = (playbackPositionBMs - offsetBMs) * pixelsPerMs
            if (curBx in 0f..w) canvas.drawLine(curBx, waveTopB, curBx, waveBotB, paintCursor)
        }

        // ── Automation lines (Volume / EQ curves) ─────────────────────────────
        drawAutomationLines(canvas, markerAx, markerBx, cfPx, w, waveTopA, waveBotA, waveTopB, waveBotB)
    }

    private fun drawScrolledWaveform(
        canvas: Canvas,
        amps: List<Float>,
        durMs: Int,
        offsetMs: Float,
        top: Float,
        bottom: Float,
        paint: Paint
    ) {
        if (amps.isEmpty() || durMs <= 0) return
        val midY = (top + bottom) / 2f
        val maxAmp = (bottom - top) * 0.42f
        val barDurMs = durMs.toFloat() / amps.size
        val firstVisible = ((offsetMs / barDurMs).toInt() - 1).coerceAtLeast(0)
        val lastVisible = (((offsetMs + width / pixelsPerMs) / barDurMs).toInt() + 1).coerceAtMost(amps.size - 1)
        val visibleCount = (lastVisible - firstVisible + 1).coerceAtLeast(1)
        val groupSize = (visibleCount / 96).coerceAtLeast(1)

        var i = firstVisible
        while (i <= lastVisible) {
            val end = (i + groupSize - 1).coerceAtMost(lastVisible)
            var amp = 0f
            for (sampleIndex in i..end) {
                amp = maxOf(amp, amps[sampleIndex])
            }
            val timeMs = i * barDurMs
            val nextTimeMs = (end + 1) * barDurMs
            val x = (timeMs - offsetMs) * pixelsPerMs
            val x2 = (nextTimeMs - offsetMs) * pixelsPerMs
            val rectWidth = (x2 - x - 2.5f).coerceAtLeast(4f)
            val cornerRadius = (rectWidth / 2f).coerceAtMost(4f)
            val ampH = (amp * maxAmp).coerceAtLeast(3f)
            canvas.drawRoundRect(x, midY - ampH, x + rectWidth, midY + ampH, cornerRadius, cornerRadius, paint)
            i = end + 1
        }
    }

    private fun drawBeatGrid(
        canvas: Canvas,
        beats: List<Int>,
        durMs: Int,
        offsetMs: Float,
        top: Float,
        bottom: Float,
        totalWidth: Float,
        paint: Paint
    ) {
        if (beats.isEmpty() || durMs <= 0) return
        val viewEndMs = offsetMs + totalWidth / pixelsPerMs
        val originalStroke = paint.strokeWidth
        val originalAlpha = paint.alpha
        for ((index, beatMs) in beats.withIndex()) {
            if (beatMs < offsetMs - 500 || beatMs > viewEndMs + 500) continue
            val x = (beatMs - offsetMs) * pixelsPerMs
            if (x < 0f || x > totalWidth) continue
            if (index % 4 == 0) {
                paint.strokeWidth = originalStroke * 1.8f
                paint.alpha = (originalAlpha * 1.35f).toInt().coerceAtMost(255)
            } else {
                paint.strokeWidth = originalStroke
                paint.alpha = originalAlpha
            }
            canvas.drawLine(x, top, x, bottom, paint)
        }
        paint.strokeWidth = originalStroke
        paint.alpha = originalAlpha
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    private fun isTouchInMinimap(y: Float): Boolean {
        return false
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Claim touch immediately so parent (NestedScrollView, BottomSheet, etc.)
        // cannot intercept before onTouchEvent fires.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Manual zoom is disabled. Multi-touch is swallowed so automatic zoom keeps
        // the transition window centered and visually stable in both editor and preview.
        if (event.pointerCount > 1) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val halfH = height / 2f
                touchingTopHalf = event.y < halfH
                lastTouchX = event.x
                isDragging = true
                exitPointMsF = exitPointMs.toFloat()
                entryPointMsF = entryPointMs.toFloat()

                // Check if touch is inside minimap area
                isDraggingMinimap = isTouchInMinimap(event.y)
                minimapTouchingTop = event.y < halfH

                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    if (isDraggingMinimap) {
                        // ── Minimap drag: scroll the shared view window ──
                        val dur = if (minimapTouchingTop) durationA else durationB
                        if (dur > 0) {
                            val touchMs = (event.x / width.toFloat()) * dur
                            // touchMs = markerMs + sharedOffsetMs  →  solve for sharedOffsetMs
                            if (minimapTouchingTop) {
                                sharedOffsetMs = touchMs - exitPointMs
                            } else {
                                sharedOffsetMs = touchMs - entryPointMs
                            }
                            invalidate()
                        }
                    } else {
                        // ── Main area drag: move the exit/entry point ──
                        val dx = event.x - lastTouchX
                        if (touchingTopHalf) {
                            exitPointMsF -= dx / pixelsPerMs
                            exitPointMs = exitPointMsF.roundToInt()
                        } else {
                            entryPointMsF -= dx / pixelsPerMs
                            entryPointMs = entryPointMsF.roundToInt()
                        }
                        lastTouchX = event.x
                        onScrubbing?.invoke(exitPointMs, entryPointMs)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasMinimap = isDraggingMinimap
                isDragging = false
                isDraggingMinimap = false

                if (!wasMinimap) {
                    // Snap to nearest beat on release if enabled (only for marker drags)
                    if (snapToBeatEnabled) {
                        if (touchingTopHalf && beatGridA.isNotEmpty()) {
                            exitPointMs = snapToNearestBeat(exitPointMs, beatGridA)
                            exitPointMsF = exitPointMs.toFloat()
                        } else if (!touchingTopHalf && beatGridB.isNotEmpty()) {
                            entryPointMs = snapToNearestBeat(entryPointMs, beatGridB)
                            entryPointMsF = entryPointMs.toFloat()
                        }
                    }
                    if (touchingTopHalf) {
                        onExitChanged?.invoke(exitPointMs)
                    } else {
                        onEntryChanged?.invoke(entryPointMs)
                    }
                }
                invalidate()
            }
        }
        return true
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun snapToNearestBeat(ms: Int, beatGrid: List<Int>): Int {
        if (beatGrid.isEmpty()) return ms
        val nearest = beatGrid.minByOrNull { abs(it - ms) } ?: return ms
        // Only snap if the nearest beat is within 1 beat interval (~500ms) to avoid
        // yanking the marker back from positions beyond the last detected beat
        val snapThresholdMs = 500
        return if (abs(nearest - ms) <= snapThresholdMs) nearest else ms
    }

    private fun nextBeat(ms: Int, beatGrid: List<Int>): Int =
        beatGrid.filter { it > ms }.minOrNull() ?: ms

    private fun prevBeat(ms: Int, beatGrid: List<Int>): Int =
        beatGrid.filter { it < ms }.maxOrNull() ?: ms

    // ── Public snap-to-beat (one-shot, no threshold) ────────────────────────

    /** Snaps EXIT to the nearest beat in song A and ENTRY to the nearest beat in song B.
     *  Unlike the auto-snap on release, this ignores the proximity threshold. */
    fun snapBothToNearestBeat() {
        if (beatGridA.isNotEmpty()) {
            exitPointMs = beatGridA.minByOrNull { abs(it - exitPointMs) } ?: exitPointMs
            exitPointMsF = exitPointMs.toFloat()
            onExitChanged?.invoke(exitPointMs)
        }
        if (beatGridB.isNotEmpty()) {
            entryPointMs = beatGridB.minByOrNull { abs(it - entryPointMs) } ?: entryPointMs
            entryPointMsF = entryPointMs.toFloat()
            onEntryChanged?.invoke(entryPointMs)
        }
        invalidate()
    }

    // ── Public nudge API ──────────────────────────────────────────────────────

    fun nudgeExitToNextBeat() {
        if (beatGridA.isEmpty()) return
        exitPointMs = nextBeat(exitPointMs, beatGridA)
        exitPointMsF = exitPointMs.toFloat()
        onExitChanged?.invoke(exitPointMs)
        invalidate()
    }

    fun nudgeExitToPrevBeat() {
        if (beatGridA.isEmpty()) return
        exitPointMs = prevBeat(exitPointMs, beatGridA)
        exitPointMsF = exitPointMs.toFloat()
        onExitChanged?.invoke(exitPointMs)
        invalidate()
    }

    fun nudgeEntryToNextBeat() {
        if (beatGridB.isEmpty()) return
        entryPointMs = nextBeat(entryPointMs, beatGridB)
        entryPointMsF = entryPointMs.toFloat()
        onEntryChanged?.invoke(entryPointMs)
        invalidate()
    }

    fun nudgeEntryToPrevBeat() {
        if (beatGridB.isEmpty()) return
        entryPointMs = prevBeat(entryPointMs, beatGridB)
        entryPointMsF = entryPointMs.toFloat()
        onEntryChanged?.invoke(entryPointMs)
        invalidate()
    }

    private val paintAutoLegend = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.LEFT
    }

    private fun drawAutomationLines(
        canvas: Canvas, markerAx: Float, markerBx: Float, cfPx: Float, w: Float,
        waveTopA: Float, waveBotA: Float, waveTopB: Float, waveBotB: Float
    ) {
        val autoAreaHA = (waveBotA - waveTopA) * 0.22f
        val autoAreaHB = (waveBotB - waveTopB) * 0.22f
        val autoTopA = waveTopA + 4f
        val autoTopB = waveTopB + 4f
        val autoPaints = listOf(paintAutoBass, paintAutoMid, paintAutoTreble)
        val legendLabels = listOf("B", "M", "T")
        val legendColors = listOf(
            Color.parseColor("#CCFF5030"),
            Color.parseColor("#CCFF9820"),
            Color.parseColor("#CCBB86FC")
        )

        val bandCurveTypes = listOf(bandFadeTypeBass, bandFadeTypeMid, bandFadeTypeTreble)
        // Vertical offsets to separate overlapping lines (px)
        val bandOffsets = floatArrayOf(0f, -3f, -6f)

        val cfEndX = (markerAx + cfPx).coerceAtMost(w)
        if (cfEndX > markerAx && markerAx < w) {
            val curvesA = getAutoCurvesA(bandCurveTypes)
            curvesA.forEachIndexed { idx, (startLevel, endLevel) ->
                val off = bandOffsets[idx]
                val startY = autoTopA + (1f - startLevel) * autoAreaHA + off
                val endY   = autoTopA + (1f - endLevel)   * autoAreaHA + off
                canvas.drawPath(buildAutoCurvePath(markerAx.coerceAtLeast(0f), startY, cfEndX, endY, bandCurveTypes[idx]), autoPaints[idx])
            }
            // Legend labels at the start of the automation area
            if (curvesA.isNotEmpty()) {
                val legendX = (markerAx + 4f).coerceAtLeast(4f)
                curvesA.forEachIndexed { idx, (startLevel, _) ->
                    val y = autoTopA + (1f - startLevel) * autoAreaHA + 5f
                    paintAutoLegend.color = legendColors[idx]
                    canvas.drawText(legendLabels[idx], legendX + idx * 14f, y, paintAutoLegend)
                }
            }
        }

        val cfEndXB = (markerBx + cfPx).coerceAtMost(w)
        if (cfEndXB > markerBx && markerBx < w) {
            val curvesB = getAutoCurvesB(bandCurveTypes)
            curvesB.forEachIndexed { idx, (startLevel, endLevel) ->
                val off = bandOffsets[idx]
                val startY = autoTopB + (1f - startLevel) * autoAreaHB + off
                val endY   = autoTopB + (1f - endLevel)   * autoAreaHB + off
                canvas.drawPath(buildAutoCurvePath(markerBx.coerceAtLeast(0f), startY, cfEndXB, endY, bandCurveTypes[idx]), autoPaints[idx])
            }
            // Legend labels at the end of the automation area
            if (curvesB.isNotEmpty()) {
                val legendX = (cfEndXB - 40f).coerceAtLeast(4f)
                curvesB.forEachIndexed { idx, (_, endLevel) ->
                    val y = autoTopB + (1f - endLevel) * autoAreaHB + 5f
                    paintAutoLegend.color = legendColors[idx]
                    canvas.drawText(legendLabels[idx], legendX + idx * 14f, y, paintAutoLegend)
                }
            }
        }
    }

    private fun drawMinimap(
        canvas: Canvas, amps: List<Float>, durMs: Int, offsetMs: Float,
        markerMs: Int, cfMs: Int, top: Float, bottom: Float, totalWidth: Float,
        markerPaint: Paint, wavePaint: Paint
    ) {
        if (durMs <= 0) return
        val mmH = bottom - top

        // Background
        canvas.drawRect(0f, top, totalWidth, bottom, paintMinimapBg)

        // Tiny waveform across full minimap width
        if (amps.isNotEmpty()) {
            val midY = (top + bottom) / 2f
            val maxAmp = mmH * 0.4f
            val step = (amps.size / totalWidth).coerceAtLeast(1f).toInt()
            for (px in 0 until totalWidth.toInt()) {
                val idx = ((px.toFloat() / totalWidth) * amps.size).toInt().coerceIn(0, amps.size - 1)
                val amp = amps[idx] * maxAmp
                if (amp > 0.5f) {
                    canvas.drawRect(px.toFloat(), midY - amp, px + 1f, midY + amp, wavePaint)
                }
            }
        }

        // Viewport indicator: shows the visible portion of the song
        val viewStartMs = offsetMs.coerceAtLeast(0f)
        val viewEndMs = (offsetMs + totalWidth / pixelsPerMs).coerceAtMost(durMs.toFloat())
        val vpLeft = (viewStartMs / durMs) * totalWidth
        val vpRight = (viewEndMs / durMs) * totalWidth
        canvas.drawRect(vpLeft, top, vpRight, bottom, paintMinimapViewport)
        canvas.drawRect(vpLeft, top, vpRight, bottom, paintMinimapViewportBorder)

        // Marker line
        val markerX = (markerMs.toFloat() / durMs) * totalWidth
        if (markerX in 0f..totalWidth) {
            canvas.drawLine(markerX, top + 1f, markerX, bottom - 1f, markerPaint)
        }

        // Crossfade zone indicator on minimap
        val cfStartX = markerX
        val cfEndX = ((markerMs + cfMs).toFloat() / durMs) * totalWidth
        if (cfEndX > 0f && cfStartX < totalWidth) {
            val cfPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = markerPaint.color
                alpha = 40
                style = Paint.Style.FILL
            }
            canvas.drawRect(
                cfStartX.coerceAtLeast(0f), top,
                cfEndX.coerceAtMost(totalWidth), bottom, cfPaint
            )
        }
    }

    private fun buildAutoCurvePath(x1: Float, y1: Float, x2: Float, y2: Float, curveType: String): Path {
        val path = Path()
        path.moveTo(x1, y1)
        val dx = x2 - x1
        when (curveType) {
            "logarithmic" -> path.quadTo(x1 + dx * 0.15f, y2, x2, y2)
            "exponential" -> path.quadTo(x2 - dx * 0.15f, y1, x2, y2)
            "sCurve", "scurve", "s_curve", "constantPower", "constantpower", "constant_power" -> {
                path.cubicTo(x1 + dx * 0.35f, y1, x2 - dx * 0.35f, y2, x2, y2)
            }
            "hold"        -> { path.lineTo(x2, y1) }  // flat line at start level
            "cut"         -> { path.lineTo(x1, y2); path.lineTo(x2, y2) }  // instant drop then flat
            else          -> {
                path.lineTo(x1 + dx * 0.5f, y2)
                path.lineTo(x2, y2)
            }
        }
        return path
    }

    /** Returns (startLevel, endLevel) per band for Song A, adjusted by per-band fade type. */
    private fun getAutoCurvesA(bandCurves: List<String>): List<Pair<Float, Float>> {
        val base = when (mixMode) {
            "freq_split" -> listOf(1.0f to 1.0f, 1.0f to 0.4f, 1.0f to 0.0f)
            "club_drop"  -> listOf(1.0f to 0.1f, 1.0f to 0.8f, 1.0f to 1.0f)
            "hard_edit"  -> return emptyList()
            "overlap"    -> listOf(1.0f to 1.0f, 1.0f to 1.0f, 1.0f to 1.0f)
            else         -> listOf(1.0f to 0.0f, 1.0f to 0.0f, 1.0f to 0.0f)
        }
        return base.mapIndexed { idx, (start, end) ->
            when (bandCurves.getOrElse(idx) { "linear" }) {
                "hold" -> start to start  // maintain start level
                "cut"  -> start to 0.0f   // hard drop to zero
                else   -> start to end
            }
        }
    }

    /** Returns (startLevel, endLevel) per band for Song B, adjusted by per-band fade type. */
    private fun getAutoCurvesB(bandCurves: List<String>): List<Pair<Float, Float>> {
        val base = when (mixMode) {
            "freq_split" -> listOf(0.0f to 0.0f, 0.0f to 0.6f, 0.0f to 1.0f)
            "club_drop"  -> listOf(0.2f to 1.0f, 0.0f to 0.9f, 0.0f to 1.0f)
            "hard_edit"  -> return emptyList()
            "overlap"    -> listOf(1.0f to 1.0f, 1.0f to 1.0f, 1.0f to 1.0f)
            else         -> listOf(0.0f to 1.0f, 0.0f to 1.0f, 0.0f to 1.0f)
        }
        return base.mapIndexed { idx, (start, end) ->
            when (bandCurves.getOrElse(idx) { "linear" }) {
                "hold" -> end to end    // maintain end level
                "cut"  -> 1.0f to end   // hard jump to full then settle
                else   -> start to end
            }
        }
    }

    private fun formatMs(ms: Int): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val frac = (ms % 1000) / 100
        return String.format("%d:%02d.%d", min, sec, frac)
    }
}
