package com.example.resonant.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.example.resonant.R
import com.example.resonant.data.network.MixDocumentDTO
import com.example.resonant.playback.BeatMagnet
import com.example.resonant.playback.MixComposer
import com.example.resonant.playback.MixLanes
import com.example.resonant.playback.WaveformBands
import com.example.resonant.ui.viewmodels.MixEditorV2State
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Editor visual de la mezcla v2 (spotify-style-transition-engine D9) sobre una pantalla oscura: ondas de 3 bandas de
 * A y B como capas suaves (atenuadas fuera de la ventana), la ventana de mezcla como un recuadro con el degradado de
 * PlayMix y una muesca que apunta al desplegable de compases, los carriles del documento (volumen, EQ, filtro, efectos
 * y loop) y los tiempos de la ventana en cada canción. La rejilla de beats y compases solo se dibuja con
 * beatmatching: sin él, los beats de A y B no casan y las líneas solo confunden.
 *
 * Cada onda se arrastra por separado; al soltar se avisa con [onDragA]/[onDragB] y el imán (compás, o beat con
 * zoom) lo aplica el ViewModel. El pellizco cambia el zoom.
 */
class MixEditorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** Inicio nuevo de A (ms de A) y si el imán va al beat (zoom alto). */
    var onDragA: ((aStartMs: Int, toBeat: Boolean) -> Unit)? = null
    var onDragB: ((bStartMs: Int, toBeat: Boolean) -> Unit)? = null

    /** Sin edición (vista previa): las ondas no se arrastran; el pellizco sigue cambiando el zoom. */
    var editable: Boolean = true

    /** Posición de reproducción en ms de A o de B (−1 = esa canción no suena). */
    private var playheadAMs = -1f
    private var playheadBMs = -1f

    private var state: MixEditorV2State? = null
    private var coincident: List<Double> = emptyList()
    private var fallbackA: List<Float>? = null
    private var fallbackB: List<Float>? = null

    private var zoom = 1f
    private var dragTrack = 0
    private var dragStartX = 0f
    private var dragPx = 0f

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val rulerH = dp(18f)
    private var trackH = dp(84f)
    private val laneStripH = dp(12f)
    private val gap = dp(6f)
    private val sampleStep = dp(3f)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); strokeCap = Paint.Cap.ROUND
    }
    private val lane = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.75f); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val laneGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(6f); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val windowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val windowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f) }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val timeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT_DIM; textSize = dp(10f)
        typeface = runCatching { ResourcesCompat.getFont(context, R.font.unageo_medium) }.getOrNull()
    }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A0F0C"); textSize = dp(9f); textAlign = Paint.Align.CENTER
        typeface = runCatching { ResourcesCompat.getFont(context, R.font.unageo_bold) }.getOrNull()
    }
    private val path = Path()
    private val wavePath = Path()
    private val waves = WavePaths()
    private var levels = FloatArray(0)
    private val rect = RectF()
    private val windowRect = RectF()

    /** Pulso del recuadro al cambiar el documento (1 → 0). */
    private var pulse = 0f
    private var pulseAnimator: ValueAnimator? = null

    /** Barrido de luz por la ventana mientras el servidor renderiza la mezcla (0..1, en bucle). */
    private var sweep = -1f
    private var sweepAnimator: ValueAnimator? = null

    private fun clock(ms: Double): String {
        val total = (max(0.0, ms) / 1000).toInt()
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
            invalidate()
            return true
        }
    })

    fun setState(value: MixEditorV2State?, coincidentBeats: List<Double>) {
        val previous = state?.document
        state = value
        coincident = coincidentBeats
        if (dragTrack == 0) dragPx = 0f
        if (previous != null && value != null && previous != value.document) startPulse()
        invalidate()
    }

    /** Onda simple (segmentos o amplitudes) para cuando aún no hay forma de onda de 3 bandas. */
    fun setFallbackWaveforms(a: List<Float>?, b: List<Float>?) {
        fallbackA = a
        fallbackB = b
        invalidate()
    }

    /** Línea de reproducción: la canción que suena y su posición. */
    fun setPlayhead(songAMs: Float, songBMs: Float) {
        if (songAMs == playheadAMs && songBMs == playheadBMs) return
        playheadAMs = songAMs
        playheadBMs = songBMs
        invalidate()
    }

    /** Barrido animado dentro de la ventana mientras el servidor renderiza la mezcla guardada. */
    fun setRendering(rendering: Boolean) {
        if (rendering == (sweepAnimator != null)) return
        if (rendering) {
            sweepAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = SWEEP_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { sweep = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            sweepAnimator?.cancel()
            sweepAnimator = null
            sweep = -1f
            invalidate()
        }
    }

    private fun startPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = PULSE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        sweepAnimator?.cancel()
        sweepAnimator = null
        super.onDetachedFromWindow()
    }

    /** Con altura fija (el contenedor del editor) las pistas crecen para ocuparla. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val fixed = rulerH + laneStripH + gap + laneStripH + rulerH
        trackH = max(dp(84f), (h - fixed) / 2)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = rulerH + trackH + laneStripH + gap + trackH + laneStripH + rulerH
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(h.toInt(), heightMeasureSpec))
    }

    // ─── Geometría: eje X en ms de la línea de tiempo (0 = a.startMs) ─────────────────────────

    private fun visibleSpanMs(doc: MixDocumentDTO): Double = max(doc.durationMs, 2000) * 2.0 / zoom

    private fun viewStartMs(doc: MixDocumentDTO): Double = doc.durationMs / 2.0 - visibleSpanMs(doc) / 2

    private fun xOf(doc: MixDocumentDTO, t: Double): Float = ((t - viewStartMs(doc)) / visibleSpanMs(doc) * width).toFloat()

    private fun msPerPx(doc: MixDocumentDTO): Double = visibleSpanMs(doc) / max(1, width)

    /** ms de A en la línea de tiempo [t] (con el arrastre en curso). */
    private fun aMs(doc: MixDocumentDTO, t: Double): Double =
        doc.a.startMs + t - (if (dragTrack == TRACK_A) dragPx * msPerPx(doc) else 0.0)

    private fun bMs(s: MixEditorV2State, doc: MixDocumentDTO, t: Double): Double =
        doc.b.startMs + (t - doc.b.offsetMs) * s.rateB - (if (dragTrack == TRACK_B) dragPx * msPerPx(doc) * s.rateB else 0.0)

    // ─── Dibujo ──────────────────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Pantalla oscura propia: el editor la enmarca en su escenario y la vista previa la usa tal cual.
        fill.color = COLOR_SCREEN
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, dp(18f), dp(18f), fill)

        val s = state ?: return
        val doc = s.document
        val beatmatched = doc.beatSync == true
        var y = 0f
        drawRuler(canvas, doc, y, aSide = true, s, beatmatched)
        y += rulerH
        val topA = y
        y += trackH
        val stripTop = y
        y += laneStripH + gap
        val topB = y
        y += trackH + laneStripH
        val rulerBTop = y

        windowRect.set(xOf(doc, 0.0), topA - dp(2f), xOf(doc, doc.durationMs.toDouble()), topB + trackH + dp(2f))
        drawWindowBackground(canvas)
        drawTrack(canvas, s, doc, topA, isA = true, beatmatched)
        drawLaneStrip(canvas, doc, stripTop)
        drawTrack(canvas, s, doc, topB, isA = false, beatmatched)
        drawRuler(canvas, doc, rulerBTop, aSide = false, s, beatmatched)
        drawWindowFrame(canvas)
        drawSweep(canvas)
        drawPlayhead(canvas, s, doc, topA, topB + trackH)
    }

    /** Relleno del recuadro de la mezcla, debajo de las ondas (degradado de A a B, más vivo tras un cambio). */
    private fun drawWindowBackground(canvas: Canvas) {
        if (windowRect.width() <= 0f) return
        val alpha = (0x16 + 0x30 * pulse).toInt().coerceIn(0, 255)
        windowFill.shader = LinearGradient(
            0f, windowRect.top, 0f, windowRect.bottom,
            ColorUtils.setAlphaComponent(COLOR_SIDE_A, alpha), ColorUtils.setAlphaComponent(COLOR_SIDE_B, alpha),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(windowRect, dp(16f), dp(16f), windowFill)
    }

    /** Borde del recuadro con el degradado de PlayMix y la muesca inferior que señala el desplegable de compases. */
    private fun drawWindowFrame(canvas: Canvas) {
        if (windowRect.width() <= 0f) return
        windowStroke.strokeWidth = dp(1.5f + 1.5f * pulse)
        windowStroke.shader = LinearGradient(
            0f, windowRect.top, 0f, windowRect.bottom,
            ColorUtils.setAlphaComponent(COLOR_SIDE_A, 0xE6), ColorUtils.setAlphaComponent(COLOR_SIDE_B, 0xE6),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(windowRect, dp(16f), dp(16f), windowStroke)
        val cx = windowRect.centerX()
        if (cx < 0f || cx > width) return
        val notch = dp(5f)
        path.reset()
        path.moveTo(cx - notch * 1.4f, windowRect.bottom)
        path.lineTo(cx, windowRect.bottom + notch)
        path.lineTo(cx + notch * 1.4f, windowRect.bottom)
        path.close()
        fill.shader = null
        fill.color = COLOR_SIDE_B
        canvas.drawPath(path, fill)
    }

    private fun drawSweep(canvas: Canvas) {
        if (sweep < 0f || windowRect.width() <= 0f) return
        val band = windowRect.width() * 0.45f
        val x = windowRect.left - band + (windowRect.width() + band) * sweep
        sweepPaint.shader = LinearGradient(
            x, 0f, x + band, 0f,
            intArrayOf(Color.TRANSPARENT, Color.argb(0x3A, 255, 255, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
        )
        canvas.save()
        path.reset()
        path.addRoundRect(windowRect, dp(16f), dp(16f), Path.Direction.CW)
        canvas.clipPath(path)
        canvas.drawRect(x, windowRect.top, x + band, windowRect.bottom, sweepPaint)
        canvas.restore()
    }

    /** Línea de reproducción sobre las dos pistas (B a su velocidad en la mezcla). */
    private fun drawPlayhead(canvas: Canvas, s: MixEditorV2State, doc: MixDocumentDTO, top: Float, bottom: Float) {
        val t = when {
            playheadAMs >= 0 -> playheadAMs - doc.a.startMs.toDouble()
            playheadBMs >= 0 -> (playheadBMs - doc.b.startMs) / s.rateB + doc.b.offsetMs
            else -> return
        }
        val x = xOf(doc, t)
        if (x < 0 || x > width) return
        laneGlow.shader = null
        laneGlow.color = ColorUtils.setAlphaComponent(Color.WHITE, 0x38)
        canvas.drawLine(x, top - dp(4f), x, bottom + dp(4f), laneGlow)
        stroke.color = Color.WHITE
        stroke.strokeWidth = dp(1.5f)
        canvas.drawLine(x, top - dp(4f), x, bottom + dp(4f), stroke)
        stroke.strokeWidth = dp(1f)
        fill.shader = null
        fill.color = Color.WHITE
        canvas.drawCircle(x, top - dp(4f), dp(3.5f), fill)
    }

    /** Inicio y fin de la ventana en la canción y, con beatmatching, los beats de A que coinciden con los de B. */
    private fun drawRuler(canvas: Canvas, doc: MixDocumentDTO, top: Float, aSide: Boolean, s: MixEditorV2State, beatmatched: Boolean) {
        val startMs = if (aSide) aMs(doc, 0.0) else bMs(s, doc, 0.0)
        val endMs = if (aSide) aMs(doc, doc.durationMs.toDouble()) else bMs(s, doc, doc.durationMs.toDouble())
        val baseline = top + rulerH * 0.68f
        val startLabel = clock(startMs)
        val endLabel = clock(endMs)
        timeText.textAlign = Paint.Align.RIGHT
        val startX = (xOf(doc, 0.0) - dp(6f)).coerceAtLeast(timeText.measureText(startLabel) + dp(4f))
        canvas.drawText(startLabel, startX, baseline, timeText)
        timeText.textAlign = Paint.Align.LEFT
        val endX = (xOf(doc, doc.durationMs.toDouble()) + dp(6f)).coerceAtMost(width - timeText.measureText(endLabel) - dp(4f))
        canvas.drawText(endLabel, endX, baseline, timeText)
        if (aSide && beatmatched) {
            fill.shader = null
            fill.color = COLOR_COINCIDENT
            for (t in coincident) canvas.drawCircle(xOf(doc, t), top + rulerH - dp(4f), dp(1.8f), fill)
        }
    }

    private fun drawTrack(canvas: Canvas, s: MixEditorV2State, doc: MixDocumentDTO, top: Float, isA: Boolean, beatmatched: Boolean) {
        val song = if (isA) s.songA else s.songB
        val side = if (isA) COLOR_SIDE_A else COLOR_SIDE_B
        val dragging = dragTrack == (if (isA) TRACK_A else TRACK_B)
        // Fondo de la pista: algo más claro y con borde de su color mientras se arrastra.
        fill.shader = null
        fill.color = if (dragging) COLOR_TRACK_ACTIVE else COLOR_TRACK_BG
        rect.set(0f, top, width.toFloat(), top + trackH)
        canvas.drawRoundRect(rect, dp(12f), dp(12f), fill)
        if (dragging) {
            stroke.color = ColorUtils.setAlphaComponent(side, 0x66)
            canvas.drawRoundRect(rect, dp(12f), dp(12f), stroke)
        }

        drawWave(canvas, s, doc, top, isA, song)
        if (beatmatched) drawBeatGrid(canvas, s, doc, top, isA, song)
        if (isA) drawSilenceAfterEnd(canvas, doc, top, song)
        drawLanes(canvas, doc, top, if (isA) 'a' else 'b')
        drawBadge(canvas, if (isA) "A" else "B", side, top)
    }

    /**
     * Onda de la pista en capas planas (graves detrás, agudos delante) con el tono de su canción: tenue fuera de la
     * ventana y viva dentro. Sin ondas de 3 bandas usa la simple; sin ninguna, una línea base donde suena la canción.
     */
    private fun drawWave(canvas: Canvas, s: MixEditorV2State, doc: MixDocumentDTO, top: Float, isA: Boolean, song: MixComposer.Song) {
        val bands = if (isA) s.bandsA else s.bandsB
        val fallback = if (isA) fallbackA else fallbackB
        val mid = top + trackH / 2
        val half = trackH / 2 - dp(6f)
        val count = (width / sampleStep).toInt() + 2
        if (levels.size < count) levels = FloatArray(count)
        val stepMs = sampleStep * msPerPx(doc)
        val start = viewStartMs(doc)
        val layers: List<Pair<Int, Float>> = if (bands != null) {
            listOf(0 to 1f, 1 to 0.74f, 2 to 0.46f)
        } else {
            listOf(-1 to 1f)
        }
        val palette = if (isA) PALETTE_A else PALETTE_B
        for ((band, scale) in layers) {
            for (i in 0 until count) {
                val t = start + i * stepMs
                val from = if (isA) aMs(doc, t) else bMs(s, doc, t)
                val to = if (isA) aMs(doc, t + stepMs) else bMs(s, doc, t + stepMs)
                levels[i] = when {
                    from > song.durationMs || to < 0 -> Float.NaN
                    bands != null -> bandPeak(bands, band, from, to) * scale
                    !fallback.isNullOrEmpty() -> amplitudePeak(fallback, song.durationMs, from, to)
                    else -> 0f
                }
            }
            wavePath.reset()
            waves.mirrored(wavePath, levels, count, 0f, sampleStep, mid, half, dp(0.75f))
            val color = palette[if (band < 0) 0 else band]
            fill.shader = null
            fill.color = ColorUtils.setAlphaComponent(color, (Color.alpha(color) * DIM_OUTSIDE).toInt())
            canvas.drawPath(wavePath, fill)
            canvas.save()
            canvas.clipRect(max(0f, windowRect.left), top, min(width.toFloat(), windowRect.right), top + trackH)
            fill.color = color
            canvas.drawPath(wavePath, fill)
            canvas.restore()
        }
    }

    private fun bandPeak(bands: WaveformBands, band: Int, fromMs: Double, toMs: Double): Float {
        val data = when (band) { 0 -> bands.low; 1 -> bands.mid; else -> bands.high }
        val first = (max(0.0, fromMs) / bands.hopMs).toInt()
        val last = min((toMs / bands.hopMs).toInt(), data.size - 1)
        if (first > last) return bands.level(data, fromMs)
        var peak = 0
        var i = first
        // Como mucho ~64 lecturas por muestra: con zoom muy abierto basta con un muestreo de la ventana.
        val stride = max(1, (last - first) / 64)
        while (i <= last) {
            peak = max(peak, data[i].toInt() and 0xff)
            i += stride
        }
        return peak / 255f
    }

    private fun amplitudePeak(amps: List<Float>, durationMs: Int, fromMs: Double, toMs: Double): Float {
        if (durationMs <= 0) return 0f
        val first = (max(0.0, fromMs) / durationMs * amps.size).toInt().coerceIn(0, amps.size - 1)
        val last = (toMs / durationMs * amps.size).toInt().coerceIn(0, amps.size - 1)
        var peak = 0f
        for (i in first..max(first, last)) peak = max(peak, amps[i])
        return peak.coerceIn(0f, 1f)
    }

    /** Desde el fin del audio de A la pista se oscurece: ahí A ya es silencio. */
    private fun drawSilenceAfterEnd(canvas: Canvas, doc: MixDocumentDTO, top: Float, song: MixComposer.Song) {
        val x = xOf(doc, song.audioEndMs - aMs(doc, 0.0))
        if (x >= width) return
        val from = max(0f, x)
        fill.shader = null
        fill.color = COLOR_SILENCE
        rect.set(from, top, width.toFloat(), top + trackH)
        canvas.save()
        canvas.clipRect(rect)
        rect.set(0f, top, width.toFloat(), top + trackH)
        canvas.drawRoundRect(rect, dp(12f), dp(12f), fill)
        canvas.restore()
        if (x >= 0f) {
            stroke.color = COLOR_END
            canvas.drawLine(x, top + dp(8f), x, top + trackH - dp(8f), stroke)
        }
    }

    /** Pastilla con la letra de la pista, del color de su canción en la cabecera. */
    private fun drawBadge(canvas: Canvas, label: String, color: Int, top: Float) {
        val size = dp(16f)
        fill.shader = null
        fill.color = color
        rect.set(dp(7f), top + dp(7f), dp(7f) + size, top + dp(7f) + size)
        canvas.drawRoundRect(rect, size / 2, size / 2, fill)
        canvas.drawText(label, rect.centerX(), rect.centerY() + badgeText.textSize * 0.36f, badgeText)
    }

    /** Rejilla suave (solo con beatmatching): compases como líneas tenues y beats como marcas cortas en los bordes. */
    private fun drawBeatGrid(canvas: Canvas, s: MixEditorV2State, doc: MixDocumentDTO, top: Float, isA: Boolean, song: MixComposer.Song) {
        val from = viewStartMs(doc)
        val to = from + visibleSpanMs(doc)
        val songFrom = if (isA) aMs(doc, from) else bMs(s, doc, from)
        val songTo = if (isA) aMs(doc, to) else bMs(s, doc, to)
        val bar = MixComposer.barMs(song) ?: return
        val beats = BeatMagnet.beats(song, max(0.0, songFrom), songTo)
        val first = (song.firstDownbeatMs ?: song.downbeats?.firstOrNull())?.toDouble() ?: return
        val beatPx = bar / (song.beatsPerBar ?: 4).coerceIn(1, 16) / msPerPx(doc)
        val tick = dp(4f)
        for (b in beats) {
            val t = if (isA) b - aMs(doc, 0.0) else (b - bMs(s, doc, 0.0)) / s.rateB
            val x = xOf(doc, t)
            val phase = abs(((b - first) / bar) - Math.rint((b - first) / bar))
            if (phase < 0.02) {
                stroke.color = COLOR_BAR_LINE
                canvas.drawLine(x, top + dp(6f), x, top + trackH - dp(6f), stroke)
            } else if (beatPx >= dp(6f)) {
                stroke.color = COLOR_BEAT_TICK
                canvas.drawLine(x, top + dp(3f), x, top + dp(3f) + tick, stroke)
                canvas.drawLine(x, top + trackH - dp(3f) - tick, x, top + trackH - dp(3f), stroke)
            }
        }
    }

    /** Carriles de una pista: volumen (0..1), EQ en dB (−40..0) y filtro bipolar (−1..+1, centro = neutro). */
    private fun drawLanes(canvas: Canvas, doc: MixDocumentDTO, top: Float, side: Char) {
        val lanes = doc.lanes ?: return
        val pad = dp(8f)
        val h = trackH - 2 * pad
        fun line(key: String, color: Int, toY: (Double) -> Float) {
            if (lanes[key].isNullOrEmpty()) return
            path.reset()
            val stepMs = max(1.0, msPerPx(doc) * 2)
            var t = 0.0
            var first = true
            while (t <= doc.durationMs) {
                val x = xOf(doc, t)
                val y = top + pad + toY(MixLanes.value(doc, key, t))
                if (first) path.moveTo(x, y) else path.lineTo(x, y)
                first = false
                t += stepMs
            }
            laneGlow.shader = null
            laneGlow.color = ColorUtils.setAlphaComponent(color, 0x2C)
            canvas.drawPath(path, laneGlow)
            lane.color = color
            canvas.drawPath(path, lane)
        }
        val eqY = { db: Double -> ((EQ_MAX_DB - db.coerceIn(EQ_MIN_DB, EQ_MAX_DB)) / (EQ_MAX_DB - EQ_MIN_DB) * h).toFloat() }
        line("$side.volume", COLOR_VOLUME) { v -> ((1 - v.coerceIn(0.0, 1.0)) * h).toFloat() }
        line("$side.eq.low", COLOR_EQ, eqY)
        line("$side.eq.mid", COLOR_EQ_DIM, eqY)
        line("$side.eq.high", COLOR_EQ_DIM, eqY)
        line("$side.filter", COLOR_FILTER) { v -> ((1 - (v.coerceIn(-1.0, 1.0) + 1) / 2) * h).toFloat() }
        if (side == 'a') line("a.noise", COLOR_FILTER) { v -> ((1 - v.coerceIn(0.0, 1.0)) * h).toFloat() }
    }

    /** Franja bajo A: efectos y loop como tramos redondeados de la línea de tiempo. */
    private fun drawLaneStrip(canvas: Canvas, doc: MixDocumentDTO, top: Float) {
        val h = dp(3.5f)
        val y1 = top + dp(2.5f)
        val y2 = y1 + h + dp(2f)
        fill.shader = null
        doc.fx.orEmpty().forEach { fx ->
            fill.color = COLOR_FX
            rect.set(xOf(doc, fx.fromMs.toDouble()), y1, xOf(doc, fx.toMs.toDouble()), y1 + h)
            canvas.drawRoundRect(rect, h / 2, h / 2, fill)
        }
        doc.loop?.let { loop ->
            fill.color = COLOR_LOOP
            rect.set(xOf(doc, loop.atMs.toDouble()), y2, xOf(doc, doc.durationMs.toDouble()), y2 + h)
            canvas.drawRoundRect(rect, h / 2, h / 2, fill)
        }
    }

    // ─── Arrastre ────────────────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val s = state ?: return false
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress || event.pointerCount > 1) {
            dragTrack = 0
            dragPx = 0f
            return true
        }
        val trackATop = rulerH
        val trackBTop = trackATop + trackH + laneStripH + gap
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!editable) return true
                dragTrack = when (event.y) {
                    in trackATop..(trackATop + trackH + laneStripH) -> TRACK_A
                    in trackBTop..(trackBTop + trackH + laneStripH + rulerH) -> TRACK_B
                    else -> 0
                }
                dragStartX = event.x
                dragPx = 0f
                if (dragTrack != 0) parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return dragTrack != 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragTrack == 0) return false
                dragPx = event.x - dragStartX
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (dragTrack == 0) return false
                val doc = s.document
                val deltaMs = dragPx * msPerPx(doc)
                val toBeat = zoom >= BEAT_SNAP_ZOOM
                val track = dragTrack
                dragTrack = 0
                if (abs(dragPx) > dp(4f)) {
                    if (track == TRACK_A) onDragA?.invoke((doc.a.startMs - deltaMs).toInt(), toBeat)
                    else onDragB?.invoke((doc.b.startMs - deltaMs * s.rateB).toInt(), toBeat)
                } else {
                    dragPx = 0f
                    performClick()
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragTrack = 0
                dragPx = 0f
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    companion object {
        private const val TRACK_A = 1
        private const val TRACK_B = 2
        private const val MIN_ZOOM = 0.5f
        private const val MAX_ZOOM = 6f
        private const val PULSE_MS = 520L
        private const val SWEEP_MS = 1600L
        /** Opacidad de la onda fuera de la ventana respecto a dentro. */
        private const val DIM_OUTSIDE = 0.3f
        /** Con más zoom que esto el imán va al beat en lugar de al compás. */
        const val BEAT_SNAP_ZOOM = 2f
        private const val EQ_MIN_DB = MixComposer.KILL_DB
        /** Tope de la escala del EQ: 0 dB (la banda entera) arriba del todo; los realces, si los hay, se recortan. */
        private const val EQ_MAX_DB = 0.0

        /** Colores de A y B de la cabecera (degradado de PlayMix: naranja → rosa). */
        val COLOR_SIDE_A = Color.parseColor("#FF7A59")
        val COLOR_SIDE_B = Color.parseColor("#FF4FB8")
        val COLOR_A = COLOR_SIDE_A
        val COLOR_B = COLOR_SIDE_B
        /** Capas de la onda de cada canción: graves, medios y agudos en tonos de su color. */
        private val PALETTE_A = intArrayOf(
            Color.parseColor("#E0FF7A59"), Color.parseColor("#D9FFAE96"), Color.parseColor("#E6FFE4DA")
        )
        private val PALETTE_B = intArrayOf(
            Color.parseColor("#E0FF4FB8"), Color.parseColor("#D9FF9BD7"), Color.parseColor("#E6FFE2F3")
        )
        /** Carriles: tonos que no se confunden con el naranja de A ni el rosa de B. */
        val COLOR_VOLUME = Color.parseColor("#5BC0FF")
        val COLOR_EQ = Color.parseColor("#FFD166")
        val COLOR_EQ_DIM = Color.parseColor("#99FFD166")
        val COLOR_FILTER = Color.parseColor("#B794FF")
        val COLOR_FX = Color.parseColor("#F2F2F7")
        val COLOR_LOOP = Color.parseColor("#7CF2B0")
        val COLOR_END = Color.parseColor("#80FF6B6B")
        val COLOR_COINCIDENT = Color.parseColor("#7CF2B0")
        val COLOR_PLAYHEAD = Color.WHITE
        private val COLOR_SCREEN = Color.parseColor("#0E0E12")
        private val COLOR_TRACK_BG = Color.parseColor("#0CFFFFFF")
        private val COLOR_TRACK_ACTIVE = Color.parseColor("#17FFFFFF")
        private val COLOR_SILENCE = Color.parseColor("#66000000")
        private val COLOR_BAR_LINE = Color.parseColor("#2EFFFFFF")
        private val COLOR_BEAT_TICK = Color.parseColor("#40FFFFFF")
        private val COLOR_TEXT_DIM = Color.parseColor("#8CFFFFFF")
    }
}
