package com.example.resonant.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
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
 * Editor visual de la mezcla v2 (spotify-style-transition-engine D9): minimapa de las dos canciones, regla de A
 * con los beats coincidentes resaltados, ondas de 3 bandas de A y B (atenuadas fuera de la ventana), rejilla de
 * beats y compases, marca de fin de audio de A, regla de B y los carriles del documento con sus colores
 * (volumen azul, EQ amarillo, filtro rosa, efectos blanco, loop naranja).
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

    private val minimapH = dp(18f)
    private val rulerH = dp(16f)
    private val trackH = dp(84f)
    private val laneStripH = dp(10f)
    private val gap = dp(4f)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f) }
    private val lane = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2f); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 255, 255, 255); textSize = dp(9f) }
    private val path = Path()
    private val rect = RectF()

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
            invalidate()
            return true
        }
    })

    fun setState(value: MixEditorV2State?, coincidentBeats: List<Double>) {
        state = value
        coincident = coincidentBeats
        if (dragTrack == 0) dragPx = 0f
        invalidate()
    }

    /** Onda simple (segmentos o amplitudes) para cuando aún no hay forma de onda de 3 bandas. */
    fun setFallbackWaveforms(a: List<Float>?, b: List<Float>?) {
        fallbackA = a
        fallbackB = b
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = minimapH + gap + rulerH + trackH + laneStripH + gap + trackH + laneStripH + rulerH
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
        val s = state ?: return
        val doc = s.document
        var y = 0f
        drawMinimap(canvas, s, doc, y)
        y += minimapH + gap
        drawRuler(canvas, doc, y, s.songA, aSide = true, s)
        y += rulerH
        val topA = y
        drawTrack(canvas, s, doc, topA, isA = true)
        y += trackH
        drawLaneStrip(canvas, doc, y)
        y += laneStripH + gap
        val topB = y
        drawTrack(canvas, s, doc, topB, isA = false)
        y += trackH
        y += laneStripH
        drawRuler(canvas, doc, y, s.songB, aSide = false, s)
        drawWindow(canvas, doc, topA, topB + trackH)
    }

    private fun drawMinimap(canvas: Canvas, s: MixEditorV2State, doc: MixDocumentDTO, top: Float) {
        val half = minimapH / 2 - dp(1f)
        fun bar(y: Float, durationMs: Int, fromMs: Int, toMs: Int, color: Int) {
            fill.color = Color.argb(50, 255, 255, 255)
            rect.set(0f, y, width.toFloat(), y + half)
            canvas.drawRoundRect(rect, dp(2f), dp(2f), fill)
            if (durationMs <= 0) return
            fill.color = color
            rect.set(fromMs.toFloat() / durationMs * width, y, max(toMs.toFloat() / durationMs * width, fromMs.toFloat() / durationMs * width + dp(2f)), y + half)
            canvas.drawRect(rect, fill)
        }
        bar(top, s.songA.durationMs, doc.a.startMs, doc.a.startMs + doc.durationMs, COLOR_A)
        val bEnd = doc.b.startMs + ((doc.durationMs - doc.b.offsetMs) * s.rateB).toInt()
        bar(top + half + dp(2f), s.songB.durationMs, doc.b.startMs, bEnd, COLOR_B)
    }

    private fun drawRuler(canvas: Canvas, doc: MixDocumentDTO, top: Float, song: MixComposer.Song, aSide: Boolean, s: MixEditorV2State) {
        val from = viewStartMs(doc)
        val to = from + visibleSpanMs(doc)
        val bar = MixComposer.barMs(song) ?: return
        val first = (song.firstDownbeatMs ?: song.downbeats?.firstOrNull())?.toDouble() ?: return
        // Compases de la canción proyectados en la línea de tiempo.
        val songFrom = if (aSide) aMs(doc, from) else bMs(s, doc, from)
        val songTo = if (aSide) aMs(doc, to) else bMs(s, doc, to)
        var k = kotlin.math.floor((songFrom - first) / bar).toInt()
        stroke.color = Color.argb(140, 255, 255, 255)
        while (true) {
            val songT = first + k * bar
            if (songT > songTo) break
            val t = if (aSide) songT - aMs(doc, 0.0) else (songT - bMs(s, doc, 0.0)) / s.rateB
            val x = xOf(doc, t)
            if (x in 0f..width.toFloat() && k >= 0) {
                canvas.drawLine(x, top + rulerH * 0.4f, x, top + rulerH, stroke)
                canvas.drawText((k + 1).toString(), x + dp(2f), top + rulerH * 0.75f, text)
            }
            k++
        }
        if (aSide) {
            fill.color = COLOR_COINCIDENT
            for (t in coincident) {
                val x = xOf(doc, t)
                canvas.drawCircle(x, top + dp(3f), dp(2f), fill)
            }
        }
    }

    private fun drawTrack(canvas: Canvas, s: MixEditorV2State, doc: MixDocumentDTO, top: Float, isA: Boolean) {
        val bands = if (isA) s.bandsA else s.bandsB
        val fallback = if (isA) fallbackA else fallbackB
        val song = if (isA) s.songA else s.songB
        val mid = top + trackH / 2
        val step = max(1, (dp(2f)).toInt())
        val inWindow = { t: Double -> t >= 0 && t <= doc.durationMs }
        for (px in 0 until width step step) {
            val t = viewStartMs(doc) + px * msPerPx(doc)
            val songMs = if (isA) aMs(doc, t) else bMs(s, doc, t)
            if (songMs < 0 || songMs > song.durationMs) continue
            val alpha = if (inWindow(t)) 255 else 90
            if (bands != null) {
                val low = bands.level(bands.low, songMs)
                val m = bands.level(bands.mid, songMs)
                val high = bands.level(bands.high, songMs)
                drawBar(canvas, px.toFloat(), mid, low * trackH / 2, COLOR_LOW, alpha, step)
                drawBar(canvas, px.toFloat(), mid, m * trackH / 2 * 0.8f, COLOR_MID, alpha, step)
                drawBar(canvas, px.toFloat(), mid, high * trackH / 2 * 0.55f, COLOR_HIGH, alpha, step)
            } else if (!fallback.isNullOrEmpty()) {
                val index = (songMs / song.durationMs * fallback.size).toInt().coerceIn(0, fallback.size - 1)
                drawBar(canvas, px.toFloat(), mid, fallback[index].coerceIn(0f, 1f) * trackH / 2, if (isA) COLOR_A else COLOR_B, alpha, step)
            }
        }
        drawBeatGrid(canvas, s, doc, top, isA, song)
        if (isA) {
            // Fin del audio de A: a partir de aquí A es silencio.
            val endT = song.audioEndMs - aMs(doc, 0.0)
            val x = xOf(doc, endT)
            if (x in 0f..width.toFloat()) {
                stroke.color = COLOR_END
                canvas.drawLine(x, top, x, top + trackH, stroke)
            }
        }
        drawLanes(canvas, doc, top, if (isA) 'a' else 'b')
    }

    private fun drawBar(canvas: Canvas, x: Float, mid: Float, h: Float, color: Int, alpha: Int, step: Int) {
        fill.color = color
        fill.alpha = alpha * Color.alpha(color) / 255
        rect.set(x, mid - h, x + step * 0.8f, mid + h)
        canvas.drawRect(rect, fill)
    }

    private fun drawBeatGrid(canvas: Canvas, s: MixEditorV2State, doc: MixDocumentDTO, top: Float, isA: Boolean, song: MixComposer.Song) {
        val from = viewStartMs(doc)
        val to = from + visibleSpanMs(doc)
        val songFrom = if (isA) aMs(doc, from) else bMs(s, doc, from)
        val songTo = if (isA) aMs(doc, to) else bMs(s, doc, to)
        val bar = MixComposer.barMs(song) ?: return
        val beats = BeatMagnet.beats(song, max(0.0, songFrom), songTo)
        val first = (song.firstDownbeatMs ?: song.downbeats?.firstOrNull())?.toDouble() ?: return
        for (b in beats) {
            val t = if (isA) b - aMs(doc, 0.0) else (b - bMs(s, doc, 0.0)) / s.rateB
            val x = xOf(doc, t)
            val phase = abs(((b - first) / bar) - Math.rint((b - first) / bar))
            stroke.color = if (phase < 0.02) Color.argb(80, 255, 255, 255) else Color.argb(28, 255, 255, 255)
            canvas.drawLine(x, top, x, top + trackH, stroke)
        }
    }

    /** Carriles de una pista: volumen (0..1), graves en dB (−40..+6) y filtro bipolar (−1..+1, centro = neutro). */
    private fun drawLanes(canvas: Canvas, doc: MixDocumentDTO, top: Float, side: Char) {
        val lanes = doc.lanes ?: return
        val pad = dp(4f)
        val h = trackH - 2 * pad
        fun line(key: String, color: Int, toY: (Double) -> Float) {
            if (lanes[key].isNullOrEmpty()) return
            lane.color = color
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
            canvas.drawPath(path, lane)
        }
        line("$side.volume", COLOR_VOLUME) { v -> ((1 - v.coerceIn(0.0, 1.0)) * h).toFloat() }
        line("$side.eq.low", COLOR_EQ) { db -> ((EQ_MAX_DB - db.coerceIn(EQ_MIN_DB, EQ_MAX_DB)) / (EQ_MAX_DB - EQ_MIN_DB) * h).toFloat() }
        line("$side.eq.mid", COLOR_EQ_DIM) { db -> ((EQ_MAX_DB - db.coerceIn(EQ_MIN_DB, EQ_MAX_DB)) / (EQ_MAX_DB - EQ_MIN_DB) * h).toFloat() }
        line("$side.eq.high", COLOR_EQ_DIM) { db -> ((EQ_MAX_DB - db.coerceIn(EQ_MIN_DB, EQ_MAX_DB)) / (EQ_MAX_DB - EQ_MIN_DB) * h).toFloat() }
        line("$side.filter", COLOR_FILTER) { v -> ((1 - (v.coerceIn(-1.0, 1.0) + 1) / 2) * h).toFloat() }
        if (side == 'a') line("a.noise", COLOR_FILTER) { v -> ((1 - v.coerceIn(0.0, 1.0)) * h).toFloat() }
    }

    /** Franja bajo A: efectos (blanco) y loop (naranja) como tramos de la línea de tiempo. */
    private fun drawLaneStrip(canvas: Canvas, doc: MixDocumentDTO, top: Float) {
        val h = laneStripH - dp(3f)
        doc.fx.orEmpty().forEach { fx ->
            fill.color = COLOR_FX
            rect.set(xOf(doc, fx.fromMs.toDouble()), top + dp(1f), xOf(doc, fx.toMs.toDouble()), top + dp(1f) + h / 2)
            canvas.drawRoundRect(rect, dp(2f), dp(2f), fill)
        }
        doc.loop?.let { loop ->
            fill.color = COLOR_LOOP
            val from = loop.atMs.toDouble()
            rect.set(xOf(doc, from), top + dp(1f) + h / 2, xOf(doc, doc.durationMs.toDouble()), top + dp(1f) + h)
            canvas.drawRoundRect(rect, dp(2f), dp(2f), fill)
        }
    }

    private fun drawWindow(canvas: Canvas, doc: MixDocumentDTO, top: Float, bottom: Float) {
        val x0 = xOf(doc, 0.0)
        val x1 = xOf(doc, doc.durationMs.toDouble())
        stroke.color = Color.argb(150, 255, 255, 255)
        canvas.drawLine(x0, top, x0, bottom, stroke)
        canvas.drawLine(x1, top, x1, bottom, stroke)
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
        val trackATop = minimapH + gap + rulerH
        val trackBTop = trackATop + trackH + laneStripH + gap
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragTrack = when (event.y) {
                    in trackATop..(trackATop + trackH + laneStripH) -> TRACK_A
                    in trackBTop..(trackBTop + trackH + laneStripH + rulerH) -> TRACK_B
                    else -> 0
                }
                dragStartX = event.x
                dragPx = 0f
                if (dragTrack != 0) parent?.requestDisallowInterceptTouchEvent(true)
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
        /** Con más zoom que esto el imán va al beat en lugar de al compás. */
        const val BEAT_SNAP_ZOOM = 2f
        private const val EQ_MIN_DB = MixComposer.KILL_DB
        private const val EQ_MAX_DB = 6.0

        val COLOR_A = Color.parseColor("#B3FFFFFF")
        val COLOR_B = Color.parseColor("#B3B8C4FF")
        val COLOR_LOW = Color.parseColor("#CC3D5AFE")
        val COLOR_MID = Color.parseColor("#CC00BFA5")
        val COLOR_HIGH = Color.parseColor("#CCFFFFFF")
        val COLOR_VOLUME = Color.parseColor("#4FA3FF")
        val COLOR_EQ = Color.parseColor("#FFD54F")
        val COLOR_EQ_DIM = Color.parseColor("#80FFD54F")
        val COLOR_FILTER = Color.parseColor("#FF5FA2")
        val COLOR_FX = Color.parseColor("#FFFFFF")
        val COLOR_LOOP = Color.parseColor("#FF9800")
        val COLOR_END = Color.parseColor("#FF5252")
        val COLOR_COINCIDENT = Color.parseColor("#69F0AE")
    }
}
