package com.example.resonant.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
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
        set(v) { field = v; invalidate() }

    // ── Song data ─────────────────────────────────────────────────────────────
    private var amplitudesA: List<Float> = emptyList()
    private var amplitudesB: List<Float> = emptyList()
    private var durationA: Int = 0
    private var durationB: Int = 0
    private var beatGridA: List<Int> = emptyList()
    private var beatGridB: List<Int> = emptyList()

    // ── Scroll offsets (ms at left edge of each lane) ─────────────────────────
    private var offsetAMs: Float = 0f
    private var offsetBMs: Float = 0f

    // ── Transition points ─────────────────────────────────────────────────────
    private var exitPointMs: Int = 0
    private var entryPointMs: Int = 0
    private var crossfadeDurationMs: Int = 8000

    // ── Playback cursor ───────────────────────────────────────────────────────
    private var playbackPositionMs: Float = -1f

    // ── Callbacks ─────────────────────────────────────────────────────────────
    var onExitChanged: ((Int) -> Unit)? = null
    var onEntryChanged: ((Int) -> Unit)? = null

    // ── Touch state ───────────────────────────────────────────────────────────
    private var touchingTopHalf = true
    private var lastTouchX = 0f
    private var isDragging = false

    // ── Paints ────────────────────────────────────────────────────────────────
    private val paintWaveA = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC E21616".replace(" ", ""))
        style = Paint.Style.FILL
    }
    private val paintWaveAOverlap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAE21616")
        style = Paint.Style.FILL
    }
    private val paintWaveB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCBB86FC")
        style = Paint.Style.FILL
    }
    private val paintWaveBOverlap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AABB86FC")
        style = Paint.Style.FILL
    }
    private val paintDivider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44FFFFFF")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val paintCenterA = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE21616")
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }
    private val paintCenterB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFBB86FC")
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }
    private val paintCfZoneA = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22E21616")
        style = Paint.Style.FILL
    }
    private val paintCfZoneB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22BB86FC")
        style = Paint.Style.FILL
    }
    private val paintBeatA = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55E21616")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val paintBeatB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55BB86FC")
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
    private val cfBorderPath = Path()
    private val paintCfBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44FFFFFF")
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
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
        recenterOffsets()
        invalidate()
    }

    fun setTransitionPoints(exitMs: Int, entryMs: Int, crossfadeMs: Int) {
        exitPointMs = exitMs
        entryPointMs = entryMs
        crossfadeDurationMs = crossfadeMs
        recenterOffsets()
        invalidate()
    }

    fun setPlaybackPositionMs(posMs: Float) {
        playbackPositionMs = posMs
        invalidate()
    }

    /** Not needed in new design but kept for API compatibility. */
    fun setBpmRatios(ratioA: Float, ratioB: Float) { invalidate() }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun halfWindowMs(): Float = if (width > 0) width / 2f / pixelsPerMs else 5000f

    private fun recenterOffsets() {
        if (width == 0) return
        val hw = halfWindowMs()
        offsetAMs = (exitPointMs - hw).coerceAtLeast(0f - hw)
        offsetBMs = (entryPointMs - hw).coerceAtLeast(0f - hw)
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
        val halfH = h / 2f
        val cx = w / 2f
        val cfPx = crossfadeDurationMs * pixelsPerMs

        // ── Song A lane ──────────────────────────────────────────────────────
        // Crossfade zone: from center rightward
        canvas.drawRect(cx, 0f, (cx + cfPx).coerceAtMost(w), halfH - 1f, paintCfZoneA)

        // Waveform A
        drawScrolledWaveform(canvas, amplitudesA, durationA, offsetAMs, 0f, halfH, paintWaveA)

        // Beat grid A
        drawBeatGrid(canvas, beatGridA, durationA, offsetAMs, 0f, halfH, w, paintBeatA)

        // Nearest-beat snap guide for A (while dragging)
        if (isDragging && touchingTopHalf && beatGridA.isNotEmpty()) {
            val nearestMs = snapToNearestBeat(exitPointMs, beatGridA)
            val nearX = (nearestMs - offsetAMs) * pixelsPerMs
            if (nearX in 0f..w) canvas.drawLine(nearX, 0f, nearX, halfH, paintBeatSnap)
        }

        // Center EXIT marker
        canvas.drawLine(cx, 2f, cx, halfH - 2f, paintCenterA)
        paintLabel.color = Color.parseColor("#E21616")
        canvas.drawText("EXIT", cx, halfH - 6f, paintLabel)
        // Timecode
        paintTimecode.textAlign = Paint.Align.CENTER
        canvas.drawText(formatMs(exitPointMs), cx, 22f, paintTimecode)

        // CF border right edge
        val cfEndX = (cx + cfPx).coerceAtMost(w)
        canvas.drawLine(cfEndX, 0f, cfEndX, halfH, paintCfBorder)

        // ── Divider ──────────────────────────────────────────────────────────
        canvas.drawLine(0f, halfH, w, halfH, paintDivider)

        // ── Song B lane ──────────────────────────────────────────────────────
        // Crossfade zone: from center leftward (B is entering, so it fades IN)
        canvas.drawRect((cx - cfPx).coerceAtLeast(0f), halfH + 1f, cx, h, paintCfZoneB)

        // Waveform B
        drawScrolledWaveform(canvas, amplitudesB, durationB, offsetBMs, halfH, h, paintWaveB)

        // Beat grid B
        drawBeatGrid(canvas, beatGridB, durationB, offsetBMs, halfH, h, w, paintBeatB)

        // Nearest-beat snap guide for B (while dragging)
        if (isDragging && !touchingTopHalf && beatGridB.isNotEmpty()) {
            val nearestMs = snapToNearestBeat(entryPointMs, beatGridB)
            val nearX = (nearestMs - offsetBMs) * pixelsPerMs
            if (nearX in 0f..w) canvas.drawLine(nearX, halfH, nearX, h, paintBeatSnap)
        }

        // Center ENTRY marker
        canvas.drawLine(cx, halfH + 2f, cx, h - 2f, paintCenterB)
        paintLabel.color = Color.parseColor("#BB86FC")
        canvas.drawText("ENTRY", cx, h - 6f, paintLabel)
        // Timecode
        canvas.drawText(formatMs(entryPointMs), cx, halfH + 22f, paintTimecode)

        // CF border left edge
        val cfStartX = (cx - cfPx).coerceAtLeast(0f)
        canvas.drawLine(cfStartX, halfH, cfStartX, h, paintCfBorder)

        // ── Overlap zone label ────────────────────────────────────────────────
        val overlapCx = cx + cfPx / 2f
        if (overlapCx < w && cfPx > 60f) {
            canvas.drawText("OVERLAP", overlapCx.coerceAtMost(w - 40f), halfH - halfH / 2f, paintOverlapLabel)
        }

        // ── Playback cursor ───────────────────────────────────────────────────
        if (playbackPositionMs >= 0) {
            val curAx = (playbackPositionMs - offsetAMs) * pixelsPerMs
            if (curAx in 0f..w) canvas.drawLine(curAx, 0f, curAx, halfH, paintCursor)
        }

        // ── Scroll hint arrows ────────────────────────────────────────────────
        drawScrollHint(canvas, 0f, halfH, w)
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
        val barPx = barDurMs * pixelsPerMs
        val gap = (barPx * 0.15f).coerceAtLeast(0.5f)

        val firstVisible = ((offsetMs / barDurMs).toInt() - 1).coerceAtLeast(0)
        val lastVisible = ((( offsetMs + width / pixelsPerMs) / barDurMs).toInt() + 1).coerceAtMost(amps.size - 1)

        for (i in firstVisible..lastVisible) {
            val timeMs = i * barDurMs
            val x = (timeMs - offsetMs) * pixelsPerMs
            val ampH = amps[i] * maxAmp
            canvas.drawRect(x, midY - ampH, x + barPx - gap, midY + ampH, paint)
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
        for (beatMs in beats) {
            if (beatMs < offsetMs - 500 || beatMs > viewEndMs + 500) continue
            val x = (beatMs - offsetMs) * pixelsPerMs
            if (x < 0f || x > totalWidth) continue
            canvas.drawLine(x, top, x, bottom, paint)
        }
    }

    private fun drawScrollHint(canvas: Canvas, top: Float, bottom: Float, w: Float) {
        val midY = (top + bottom) / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#22FFFFFF")
            style = Paint.Style.FILL
        }
        // Left arrow hint
        val path = Path()
        path.moveTo(12f, midY)
        path.lineTo(22f, midY - 8f)
        path.lineTo(22f, midY + 8f)
        path.close()
        canvas.drawPath(path, paint)
        // Right arrow hint
        val path2 = Path()
        path2.moveTo(w - 12f, midY)
        path2.lineTo(w - 22f, midY - 8f)
        path2.lineTo(w - 22f, midY + 8f)
        path2.close()
        canvas.drawPath(path2, paint)
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchingTopHalf = event.y < height / 2f
                lastTouchX = event.x
                isDragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                if (touchingTopHalf) {
                    offsetAMs -= dx / pixelsPerMs
                    exitPointMs = (offsetAMs + halfWindowMs()).roundToInt()
                        .coerceIn(0, if (durationA > 0) durationA else Int.MAX_VALUE)
                    onExitChanged?.invoke(exitPointMs)
                } else {
                    offsetBMs -= dx / pixelsPerMs
                    entryPointMs = (offsetBMs + halfWindowMs()).roundToInt()
                        .coerceIn(0, if (durationB > 0) durationB else Int.MAX_VALUE)
                    onEntryChanged?.invoke(entryPointMs)
                }
                lastTouchX = event.x
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                // BPM snap on release
                if (touchingTopHalf && beatGridA.isNotEmpty()) {
                    exitPointMs = snapToNearestBeat(exitPointMs, beatGridA)
                    offsetAMs = exitPointMs - halfWindowMs()
                    onExitChanged?.invoke(exitPointMs)
                } else if (!touchingTopHalf && beatGridB.isNotEmpty()) {
                    entryPointMs = snapToNearestBeat(entryPointMs, beatGridB)
                    offsetBMs = entryPointMs - halfWindowMs()
                    onEntryChanged?.invoke(entryPointMs)
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
        }
        return true
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun snapToNearestBeat(ms: Int, beatGrid: List<Int>): Int =
        beatGrid.minByOrNull { abs(it - ms) } ?: ms

    private fun formatMs(ms: Int): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val frac = (ms % 1000) / 100
        return String.format("%d:%02d.%d", min, sec, frac)
    }
}
