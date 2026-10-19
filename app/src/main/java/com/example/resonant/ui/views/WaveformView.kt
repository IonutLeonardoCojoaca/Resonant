package com.example.resonant.ui.views

import com.example.resonant.data.network.MixDocumentDTO
import com.example.resonant.playback.BarGrid
import com.example.resonant.playback.BeatMagnet
import com.example.resonant.playback.MixLanes
import com.example.resonant.playback.PhaseAlignment
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Interactive dual-lane waveform editor.
 *
 * - Top half  = Song A (exiting). Drag left/right to move the exit point.
 * - Bottom half= Song B (entering). Drag left/right to move the entry point.
 * - A fixed center line acts as the scrub head; dragging scrolls the waveform under it.
 * - Grid: bar lines (downbeats) and slightly stronger phrase lines; beat lines only when zoomed in
 *   enough. With bpmPrecise + firstDownbeatMs the exact bar grid ([BarGrid]) is used.
 * - Tempo lock: when both songs have exact grids and compatible tempos, song B is drawn at A's tempo
 *   from its entry marker (as playback does with beatSync), so B's bars line up with A's.
 * - While dragging, markers stay locked to their song's bars (phrase starts first); without real
 *   downbeats the old beat magnet (BeatMagnet) applies.
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
        /** Separación mínima entre beats para dibujarlos (dp). */
        private const val BEAT_LINE_MIN_SPACING_DP = 12f
        /** Separación mínima entre líneas de compás; por debajo se dibuja uno de cada 2, 4… (dp). */
        private const val BAR_LINE_MIN_SPACING_DP = 6f
        /** Colores de A y B (degradado de PlayMix), los mismos que la cabecera del editor. */
        private val COLOR_A = Color.parseColor("#FF7A59")
        private val COLOR_B = Color.parseColor("#FF4FB8")
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    // ── Contorno suave de las ondas ──────────────────────────────────────────
    private val waves = WavePaths()
    private val wavePath = Path()
    private val corePath = Path()
    private var waveLevels = FloatArray(0)

    // ── Mix mode & curve ──────────────────────────────────────────────────────
    var mixMode: String = "crossfade"
        set(v) { field = v; invalidate() }
    var fadeCurveType: String = "linear"
        set(v) { field = v; invalidate() }
    var canvasBackgroundColor: Int = Color.BLACK
        set(v) { field = v; invalidate() }

    // ── Documento de mezcla (add-rhythmic-mix-styles) ────────────────────────
    /**
     * Con documento, las curvas se dibujan evaluando sus carriles (`MixLanes`): volumen y graves, medios y
     * agudos de cada canción, con los escalones en los compases tal como sonarán. Sin documento se dibujan
     * las curvas del editor antiguo (mixMode y bandFadeType*).
     */
    private var mixDocument: MixDocumentDTO? = null

    fun setMixDocument(doc: MixDocumentDTO?) {
        mixDocument = doc
        invalidate()
    }

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
    // Fase 4: compases y frases (vacíos sin análisis v2) para el imán por niveles
    private var downbeatsA: List<Int> = emptyList()
    private var downbeatsB: List<Int> = emptyList()
    private var phrasesA: List<Int> = emptyList()
    private var phrasesB: List<Int> = emptyList()
    // Rejilla exacta (bpmPrecise + firstDownbeatMs) de cada canción; null sin ajuste
    private var tempoGridA: PhaseAlignment.Grid? = null
    private var tempoGridB: PhaseAlignment.Grid? = null
    private var beatsPerBarA: Int? = null
    private var beatsPerBarB: Int? = null
    private var barGridA: BarGrid = BarGrid.EMPTY
    private var barGridB: BarGrid = BarGrid.EMPTY
    /**
     * ms de B por ms de la mezcla mientras suenan juntas: `bpmA / bpmB` con tempos bloqueados (la reproducción
     * acelera B a ese ritmo), 1 si no. B se dibuja escalada desde su marcador para que sus compases caigan
     * sobre los de A.
     */
    private var tempoRatioB: Float = 1f

    /**
     * Tempos bloqueados (beatmatching): solo entonces se dibuja la rejilla de beats y compases. Sin él los beats de A
     * y B no casan y las líneas solo confunden.
     */
    private var tempoLocked: Boolean = false

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
        color = Color.parseColor("#E0FF7A59")
        style = Paint.Style.FILL
    }
    private val paintWaveCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val paintTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0CFFFFFF")
        style = Paint.Style.FILL
    }
    private val paintCursorGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38FFFFFF")
        strokeWidth = dp(6f)
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val paintWaveAOverlap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAFF7A59")
        style = Paint.Style.FILL
    }
    private val paintWaveB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0FF4FB8")
        style = Paint.Style.FILL
    }
    private val paintWaveBOverlap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAFF4FB8")
        style = Paint.Style.FILL
    }
    private val paintDivider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44FFFFFF")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val paintCenterA = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFF7A59")
        strokeWidth = dp(1.75f)
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    private val paintCenterB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFF4FB8")
        strokeWidth = dp(1.75f)
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    private val paintCfZoneA = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22FF7A59")
        style = Paint.Style.FILL
    }
    private val paintCfZoneB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22FF4FB8")
        style = Paint.Style.FILL
    }
    private val paintBeatA = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#26FFFFFF")
        strokeWidth = dp(0.75f)
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val paintBeatB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#26FFFFFF")
        strokeWidth = dp(0.75f)
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val paintBeatSnap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3FFD166")
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val paintCursor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
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
        color = Color.parseColor("#CCFFD27A")
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
        color = Color.parseColor("#FFFF7A59")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val paintMinimapMarkerB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFF4FB8")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val paintMinimapWaveA = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FF7A59")
        style = Paint.Style.FILL
    }
    private val paintMinimapWaveB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FF4FB8")
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
    private val paintAutoGain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = dp(1.75f)
        strokeJoin = Paint.Join.ROUND
    }
    private val paintAutoBass = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFF5030")
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val paintAutoMid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFF9820")
        strokeWidth = dp(1.75f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(3f)), 0f)
    }
    private val paintAutoTreble = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFF4FB8")
        strokeWidth = dp(1.5f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(2.5f)), 0f)
    }
    private val paintBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val paintTransitionWindow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22FF7A59")
        style = Paint.Style.FILL
    }
    private val paintHighlightBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFF7A59")
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
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
        rebuildGrids()
        exitPointMsF = exitPointMs.toFloat()
        entryPointMsF = entryPointMs.toFloat()
        recenterOffsets()
        invalidate()
    }

    /** Compases y frases de cada canción (análisis v2). Sin ellos el imán solo usa beats. */
    fun setStructureGrids(downA: List<Int>?, phraseStartsA: List<Int>?, downB: List<Int>?, phraseStartsB: List<Int>?) {
        downbeatsA = downA ?: emptyList()
        phrasesA = phraseStartsA ?: emptyList()
        downbeatsB = downB ?: emptyList()
        phrasesB = phraseStartsB ?: emptyList()
        rebuildGrids()
        invalidate()
    }

    /**
     * Rejillas exactas de cada canción (`PhaseAlignment.gridOf(bpmPrecise, firstDownbeatMs, beatsPerBar)`), o null
     * para usar la rejilla en bruto. Con las dos y tempos compatibles, B se dibuja al tempo de A.
     */
    fun setTempoGrids(gridA: PhaseAlignment.Grid?, gridB: PhaseAlignment.Grid?, bpbA: Int? = null, bpbB: Int? = null) {
        tempoGridA = gridA
        tempoGridB = gridB
        beatsPerBarA = bpbA
        beatsPerBarB = bpbB
        rebuildGrids()
        invalidate()
    }

    /** true si B se dibuja al tempo de A (tempos bloqueados). */
    val isTempoLocked: Boolean get() = tempoLocked

    private fun rebuildGrids() {
        barGridA = BarGrid.of(tempoGridA, beatGridA, downbeatsA, phrasesA, beatsPerBarA, durationA)
        barGridB = BarGrid.of(tempoGridB, beatGridB, downbeatsB, phrasesB, beatsPerBarB, durationB)
        val ratio = BarGrid.tempoLockRatio(barGridA, barGridB)
        tempoLocked = ratio != null
        tempoRatioB = ratio?.toFloat() ?: 1f
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
        val laneGap = dp(3f)

        paintBackground.shader = null
        paintBackground.color = canvasBackgroundColor
        canvas.drawRect(0f, 0f, w, h, paintBackground)
        // Una pista redondeada por canción (sustituye a la línea divisoria).
        canvas.drawRoundRect(0f, 0f, w, halfH - laneGap, dp(12f), dp(12f), paintTrack)
        canvas.drawRoundRect(0f, halfH + laneGap, w, h, dp(12f), dp(12f), paintTrack)

        // Unified single-scroll: both lanes anchored to the transition point.
        // sharedOffsetMs is the ms-from-transition-start at the LEFT edge of the screen.
        // markerX = time 0 of the mix (A's exit). B enters `b.offsetMs` later on the mix timeline and,
        // with tempo lock, runs `tempoRatioB` B-ms per mix-ms from its entry marker.
        val markerX = -sharedOffsetMs * pixelsPerMs
        val markerAx = markerX
        val markerBx = markerX + (mixDocument?.b?.offsetMs ?: 0) * pixelsPerMs
        val scaleB = tempoRatioB
        val exitMsF = exitPointMs.toFloat()
        val entryMsF = entryPointMs.toFloat()

        // Waveform drawing areas (above minimap)
        val waveTopA = 0f
        val waveBotA = halfH - mmH - laneGap
        val waveTopB = halfH + laneGap
        val waveBotB = h - mmH

        // ── Ventana de mezcla: relleno con el degradado de A a B, debajo de las ondas ──
        val winRect = RectF(markerAx, waveTopA + dp(2f), markerAx + cfPx, waveBotB - dp(2f))
        if (cfPx > 0) {
            paintTransitionWindow.shader = LinearGradient(
                0f, winRect.top, 0f, winRect.bottom,
                ColorUtils.setAlphaComponent(COLOR_A, 0x1C), ColorUtils.setAlphaComponent(COLOR_B, 0x1C),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(winRect, dp(16f), dp(16f), paintTransitionWindow)
        }

        // Ondas: tenues fuera de la ventana, vivas dentro.
        drawScrolledWaveform(canvas, amplitudesA, durationA, exitMsF, markerAx, 1f, waveTopA, waveBotA, paintWaveA, winRect, cfPx > 0)
        drawScrolledWaveform(canvas, amplitudesB, durationB, entryMsF, markerBx, scaleB, waveTopB, waveBotB, paintWaveB, winRect, cfPx > 0)

        if (cfPx > 0) {
            paintHighlightBorder.shader = LinearGradient(
                0f, winRect.top, 0f, winRect.bottom,
                ColorUtils.setAlphaComponent(COLOR_A, 0xE6), ColorUtils.setAlphaComponent(COLOR_B, 0xE6),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(winRect, dp(16f), dp(16f), paintHighlightBorder)
        }

        // Rejilla (compases, frases y, con zoom, beats): solo con tempos bloqueados (beatmatching).
        if (tempoLocked) {
            drawBarGrid(canvas, barGridA, exitMsF, markerAx, 1f, waveTopA, waveBotA, w, paintBeatA)
            drawBarGrid(canvas, barGridB, entryMsF, markerBx, scaleB, waveTopB, waveBotB, w, paintBeatB)

            // Snap guides: where the dragged marker is locked
            if (isDragging && !isDraggingMinimap && touchingTopHalf && barGridA.hasBars) {
                val nearestMs = lockExit(exitPointMs)
                val nearX = markerAx + (nearestMs - exitMsF) * pixelsPerMs
                if (nearX in 0f..w) canvas.drawLine(nearX, waveTopA + dp(6f), nearX, waveBotA - dp(6f), paintBeatSnap)
            }
            if (isDragging && !isDraggingMinimap && !touchingTopHalf && barGridB.hasBars) {
                val nearestMs = lockEntry(entryPointMs)
                val nearX = markerBx + (nearestMs - entryMsF) / scaleB * pixelsPerMs
                if (nearX in 0f..w) canvas.drawLine(nearX, waveTopB + dp(6f), nearX, waveBotB - dp(6f), paintBeatSnap)
            }
        }

        // ── Playback cursors ──────────────────────────────────────────────────
        if (playbackPositionAMs >= 0) {
            val curAx = markerAx + (playbackPositionAMs - exitMsF) * pixelsPerMs
            if (curAx in 0f..w) drawCursor(canvas, curAx, waveTopA, waveBotA)
        }
        if (playbackPositionBMs >= 0) {
            val curBx = markerBx + (playbackPositionBMs - entryMsF) / scaleB * pixelsPerMs
            if (curBx in 0f..w) drawCursor(canvas, curBx, waveTopB, waveBotB)
        }

        // ── Automation lines (Volume / EQ curves) ─────────────────────────────
        drawAutomationLines(canvas, markerAx, markerBx, cfPx, w, waveTopA, waveBotA, waveTopB, waveBotB)
    }

    /**
     * Onda de una canción como contorno suave y plano: el instante [anchorMs] de la canción cae en [anchorX] y cada
     * ms de la mezcla avanza [scale] ms de la canción (1 para A; `bpmA / bpmB` para B con tempos bloqueados). Con
     * [dimOutside] se dibuja tenue fuera de [window] y viva dentro; sin amplitudes, una línea base donde suena.
     */
    private fun drawScrolledWaveform(
        canvas: Canvas,
        amps: List<Float>,
        durMs: Int,
        anchorMs: Float,
        anchorX: Float,
        scale: Float,
        top: Float,
        bottom: Float,
        paint: Paint,
        window: RectF,
        dimOutside: Boolean
    ) {
        if (durMs <= 0 || width <= 0) return
        val midY = (top + bottom) / 2f
        val half = (bottom - top) * 0.42f
        val pxPerSongMs = pixelsPerMs / scale
        val offsetMs = anchorMs - anchorX / pxPerSongMs // ms de la canción en el borde izquierdo
        val step = dp(3f)
        val count = (width / step).toInt() + 2
        if (waveLevels.size < count) waveLevels = FloatArray(count)
        val stepMs = step / pxPerSongMs
        for (i in 0 until count) {
            val from = offsetMs + i * stepMs
            val to = from + stepMs
            waveLevels[i] = when {
                from > durMs || to < 0 -> Float.NaN
                amps.isEmpty() -> 0f
                else -> {
                    val first = (from.coerceAtLeast(0f) / durMs * amps.size).toInt().coerceIn(0, amps.size - 1)
                    val last = (to / durMs * amps.size).toInt().coerceIn(first, amps.size - 1)
                    var peak = 0f
                    for (k in first..last) peak = maxOf(peak, amps[k])
                    peak.coerceIn(0f, 1f)
                }
            }
        }
        wavePath.reset()
        waves.mirrored(wavePath, waveLevels, count, 0f, step, midY, half, dp(0.75f))
        // Núcleo más claro encima: da profundidad sin volver a las barras.
        corePath.reset()
        for (i in 0 until count) if (!waveLevels[i].isNaN()) waveLevels[i] *= 0.5f
        waves.mirrored(corePath, waveLevels, count, 0f, step, midY, half, 0f)

        val baseAlpha = paint.alpha
        if (dimOutside) {
            paint.alpha = (baseAlpha * 0.32f).toInt()
            canvas.drawPath(wavePath, paint)
            paint.alpha = baseAlpha
            canvas.save()
            canvas.clipRect(window.left.coerceAtLeast(0f), top, window.right.coerceAtMost(width.toFloat()), bottom)
        }
        canvas.drawPath(wavePath, paint)
        paintWaveCore.alpha = 0x5C
        canvas.drawPath(corePath, paintWaveCore)
        if (dimOutside) canvas.restore()
    }

    private fun drawCursor(canvas: Canvas, x: Float, top: Float, bottom: Float) {
        canvas.drawLine(x, top + dp(2f), x, bottom - dp(2f), paintCursorGlow)
        canvas.drawLine(x, top + dp(2f), x, bottom - dp(2f), paintCursor)
    }

    /**
     * Rejilla de una canción con el mismo anclaje que [drawScrolledWaveform]: una línea por compás (aclarada si
     * los compases quedan muy juntos), las frases algo más marcadas y los beats solo con zoom suficiente.
     */
    private fun drawBarGrid(
        canvas: Canvas,
        grid: BarGrid,
        anchorMs: Float,
        anchorX: Float,
        scale: Float,
        top: Float,
        bottom: Float,
        totalWidth: Float,
        paint: Paint
    ) {
        if (!grid.hasBars) return
        val pxPerSongMs = pixelsPerMs / scale
        val fromMs = (anchorMs - anchorX / pxPerSongMs).toDouble()
        val toMs = (anchorMs + (totalWidth - anchorX) / pxPerSongMs).toDouble()
        val centerMs = (fromMs + toMs) / 2.0
        val density = resources.displayMetrics.density
        val originalStroke = paint.strokeWidth
        val originalAlpha = paint.alpha
        fun xOf(ms: Double): Float = (anchorX + (ms - anchorMs) * pxPerSongMs).toFloat()

        val inset = 6f * density
        val tick = 4f * density

        // Beats: marcas cortas en los bordes, solo cuando quedan a ≥ BEAT_LINE_MIN_SPACING_DP entre sí.
        val beatPx = grid.beatMsAt(centerMs) * pxPerSongMs
        if (beatPx >= BEAT_LINE_MIN_SPACING_DP * density) {
            paint.strokeWidth = originalStroke
            paint.alpha = (originalAlpha * 1.4f).toInt().coerceAtMost(255)
            grid.forEachBeat(fromMs, toMs, includeDownbeats = false) { ms ->
                val x = xOf(ms)
                canvas.drawLine(x, top + inset / 2, x, top + inset / 2 + tick, paint)
                canvas.drawLine(x, bottom - inset / 2 - tick, x, bottom - inset / 2, paint)
            }
        }

        // Compases: uno de cada `step` si quedan a menos de BAR_LINE_MIN_SPACING_DP (índice estable al desplazarse).
        val barPx = grid.barMsAt(centerMs) * pxPerSongMs
        var step = 1
        if (barPx > 0.0) {
            while (barPx * step < BAR_LINE_MIN_SPACING_DP * density && step < 1024) step *= 2
        }
        paint.strokeWidth = originalStroke * 1.6f
        paint.alpha = (originalAlpha * 1.3f).toInt().coerceAtMost(255)
        grid.forEachDownbeat(fromMs, toMs) { index, ms ->
            if (index.mod(step) == 0) {
                val x = xOf(ms)
                canvas.drawLine(x, top + inset, x, bottom - inset, paint)
            }
        }

        // Frases: algo más marcadas que los compases.
        paint.strokeWidth = originalStroke * 2f
        paint.alpha = (originalAlpha * 2f).toInt().coerceAtMost(255)
        for (phraseMs in grid.phraseStarts) {
            if (phraseMs < fromMs || phraseMs > toMs) continue
            val x = xOf(phraseMs.toDouble())
            canvas.drawLine(x, top + inset, x, bottom - inset, paint)
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
                        // La posición real del dedo vive en exitPointMsF/entryPointMsF; lo que se muestra
                        // y se guarda es el punto enganchado. Con rejilla de compases el marcador queda siempre en un downbeat (frase primero):
                        // salida y entrada en compás = A y B en fase. B se mueve a su escala (tempo bloqueado).
                        val dx = event.x - lastTouchX
                        if (touchingTopHalf) {
                            exitPointMsF -= dx / pixelsPerMs
                            exitPointMs = lockExit(exitPointMsF.roundToInt())
                        } else {
                            entryPointMsF -= dx * tempoRatioB / pixelsPerMs
                            entryPointMs = lockEntry(entryPointMsF.roundToInt())
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
                    // Same lock on release (only for marker drags)
                    if (snapToBeatEnabled) {
                        if (touchingTopHalf) {
                            exitPointMs = lockExit(exitPointMs)
                            exitPointMsF = exitPointMs.toFloat()
                        } else {
                            entryPointMs = lockEntry(entryPointMs)
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

    /** Salida bloqueada a los compases de A (ver [lockToBars]). */
    private fun lockExit(ms: Int): Int = lockToBars(ms, barGridA, beatGridA, downbeatsA, phrasesA)

    /** Entrada bloqueada a los compases de B (ver [lockToBars]). */
    private fun lockEntry(ms: Int): Int = lockToBars(ms, barGridB, beatGridB, downbeatsB, phrasesB)

    /**
     * Con downbeats reales (rejilla exacta o del análisis), el inicio de frase cercano o el downbeat más próximo,
     * siempre: salida y entrada en compás dejan A y B en fase. Sin ellos, el imán a beats de siempre.
     */
    private fun lockToBars(ms: Int, grid: BarGrid, beats: List<Int>, downbeats: List<Int>, phrases: List<Int>): Int {
        if (!snapToBeatEnabled) return ms
        val bar = grid.snapToBar(ms)
        if (bar.level != BeatMagnet.Level.NONE) return bar.ms
        return BeatMagnet.snapLevels(ms, beats, downbeats, phrases).ms
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
        textSize = dp(9f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.LEFT
    }

    private fun drawAutomationLines(
        canvas: Canvas, markerAx: Float, markerBx: Float, cfPx: Float, w: Float,
        waveTopA: Float, waveBotA: Float, waveTopB: Float, waveBotB: Float
    ) {
        mixDocument?.let { doc ->
            // Los carriles de las dos canciones van en la línea de tiempo de la mezcla (tiempo de A), que empieza en
            // el marcador de A; el de B ya se desplaza `b.offsetMs` dentro de drawDocumentLanes.
            drawDocumentLanes(canvas, doc, markerAx, markerAx, w, waveTopA, waveBotA, waveTopB, waveBotB)
            return
        }
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

    /**
     * Carriles del documento sobre cada onda: volumen (blanco) y graves, medios y agudos (colores). El carril
     * de A empieza en su marcador; el de B en su marcador más `b.offsetMs`. El EQ se dibuja en [0, 1] con
     * 0 dB arriba y −40 dB abajo; el volumen directamente.
     */
    private fun drawDocumentLanes(
        canvas: Canvas, doc: MixDocumentDTO, markerAx: Float, markerBx: Float, w: Float,
        waveTopA: Float, waveBotA: Float, waveTopB: Float, waveBotB: Float
    ) {
        val durPx = doc.durationMs * pixelsPerMs
        if (durPx <= 0f) return
        val lanePaints = listOf(paintAutoGain, paintAutoBass, paintAutoMid, paintAutoTreble)
        val bands = listOf("low", "mid", "high")

        fun drawSide(side: Char, startX: Float, top: Float, bottom: Float, alignLegendEnd: Boolean) {
            val areaH = (bottom - top) * 0.26f
            val areaTop = top + 4f
            val sideOffsetMs = if (side == 'b') doc.b.offsetMs else 0
            val x0 = startX + sideOffsetMs * pixelsPerMs
            val x1 = startX + durPx
            if (x1 <= 0f || x0 >= w) return
            val from = x0.coerceAtLeast(0f)
            val to = x1.coerceAtMost(w)
            val stepPx = 2f
            fun levelAt(lane: Int, x: Float): Float {
                val t = ((x - startX) / pixelsPerMs).toDouble()
                return if (lane == 0) MixLanes.gain(doc, side, t).toFloat().coerceIn(0f, 1f)
                else ((MixLanes.eqDb(doc, side, bands[lane - 1], t) + 40.0) / 40.0).toFloat().coerceIn(0f, 1.15f)
            }
            for (lane in 0..3) {
                if (lane > 0 && doc.lanes?.get("$side.eq.${bands[lane - 1]}").isNullOrEmpty()) continue
                val path = Path()
                var x = from
                var first = true
                while (x <= to) {
                    val y = areaTop + (1f - levelAt(lane, x)) * areaH
                    if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                    x += stepPx
                }
                canvas.drawPath(path, lanePaints[lane])
            }
            // Leyenda: V, B, M, T al borde del tramo visible.
            val labels = listOf("V", "B", "M", "T")
            val legendX = if (alignLegendEnd) (to - 56f).coerceAtLeast(4f) else (from + 4f)
            for (lane in 0..3) {
                if (lane > 0 && doc.lanes?.get("$side.eq.${bands[lane - 1]}").isNullOrEmpty()) continue
                paintAutoLegend.color = lanePaints[lane].color
                canvas.drawText(labels[lane], legendX + lane * 14f, areaTop + areaH + 14f, paintAutoLegend)
            }
        }
        drawSide('a', markerAx, waveTopA, waveBotA, alignLegendEnd = false)
        drawSide('b', markerBx, waveTopB, waveBotB, alignLegendEnd = true)
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
