package com.example.resonant.playback.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.example.resonant.data.network.MixDocumentDTO
import com.example.resonant.playback.dsp.MixDsp
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Motor de salida único (spotify-style-transition-engine D5). Los ExoPlayer no sacan audio al sistema: cada uno
 * escribe su PCM en una [BusSource] (vía [BusAudioOutput]) y este motor las mezcla en un hilo de audio y escribe
 * en un único `AudioTrack` persistente con una sesión fija, como hace Spotify. Durante una transición la
 * [MixSession] aplica el documento con [MixDsp] a la muestra exacta: la mezcla empieza en el frame en que A llega
 * a `a.startMs` y B entra a la vez, así que no hay dos relojes que se desvíen.
 *
 * Hilos: las fuentes las escribe el hilo de reproducción de cada ExoPlayer; el motor las lee en su hilo; las
 * sesiones se arman y se cancelan desde el hilo principal. Los avisos de la sesión llegan al hilo principal.
 */
class MixOutputEngine(context: Context) : BusHost {

    val sessionId: Int = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId()

    /** ExoPlayer → fuente, para que la transición sepa qué fuente es A y cuál B. */
    val players = BusPlayers()

    private val mixer = BusMixer()
    private val sources get() = mixer.sources
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wakeLock = Object()

    @Volatile private var running = true
    private var session: MixSession?
        get() = mixer.session
        set(value) { mixer.session = value }
    val underruns: Long get() = mixer.underruns

    // Estado del hilo de audio.
    private var track: AudioTrack? = null
    private var trackPlaying = false
    private var writtenFrames = 0L
    private var headBase = 0L
    private val mixBuffer = FloatArray(2 * BLOCK_FRAMES)

    private val thread = Thread({ loop() }, "MixOutputEngine").apply { start() }

    /** Fuente nueva del player de [handle]; la anterior (liberada por un flush de Media3) queda enlazada a ella. */
    fun newSource(handle: BusHandle): BusSource {
        val created = BusSource(this, handle.name)
        sources += created
        handle.source?.replacedBy = created
        handle.source = created
        return created
    }

    override fun remove(source: BusSource) {
        sources -= source
        val s = session ?: return
        // Preparar B con un seek hace que Media3 suelte su salida y cree otra: en espera, la sesión sigue a la nueva
        // ([BusSource.replacedBy]) y hasta que tenga audio B cuenta como "no lista" (misma espera que sin datos).
        if (source === s.b && s.state == MixSession.State.WAITING) return
        if (s.a === source || s.b === source) s.abandonFromEngine("fuente liberada")
    }

    override fun wake() = synchronized(wakeLock) { wakeLock.notifyAll() }

    /** Frames de salida que ya han sonado (cabeza de reproducción del `AudioTrack`). */
    @Volatile override var playedFrames: Long = 0
        private set

    // ─── Sesiones de mezcla (hilo principal) ─────────────────────────────────────────────────────

    /**
     * Arma una transición: la mezcla empieza cuando A llegue a `a.startMs` y B tenga audio (si no, se espera al
     * siguiente compás de A, [barFrames], hasta [BusMixer.MAX_WAIT_FRAMES]). Sustituye a cualquier sesión anterior.
     */
    fun arm(
        doc: MixDocumentDTO,
        a: BusSource,
        b: BusSource,
        barFrames: Int?,
        listener: MixSession.Listener,
        bSpeed: Double = 1.0
    ): MixSession {
        val created = MixSession(doc, a, b, barFrames, listener, { mainHandler.post(it) }, { Log.i("MixSession", it) }, bSpeed)
        session?.abandonFromEngine("sustituida")
        session = created
        wake()
        return created
    }

    /** Cancela la sesión: A sigue como estaba y B deja de sonar. */
    fun cancel(target: MixSession) {
        if (session === target) {
            target.cancelled = true
            session = null
        }
    }

    /** Termina la sesión ya: A deja de sonar y B sigue sola. */
    fun completeNow(target: MixSession) {
        if (session === target) {
            target.a.retired = true
            target.state = MixSession.State.DONE
            session = null
        }
    }

    fun release() {
        running = false
        wake()
        thread.join(500)
    }

    // ─── Hilo de audio ───────────────────────────────────────────────────────────────────────────

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (running) {
            try {
                if (!mixer.anyPlaying()) {
                    pauseTrack()
                    synchronized(wakeLock) { wakeLock.wait(IDLE_WAIT_MS) }
                    continue
                }
                val output = ensureTrack()
                handleFlushRequests(output)
                mixer.render(mixBuffer, writtenFrames)
                if (!trackPlaying) {
                    output.play()
                    trackPlaying = true
                }
                val written = output.write(mixBuffer, 0, 2 * BLOCK_FRAMES, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    Log.e(TAG, "AudioTrack.write falló ($written); se recrea")
                    releaseTrack()
                    continue
                }
                writtenFrames += written / 2
                updatePlayed(output)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error en el hilo de audio", e)
            }
        }
        releaseTrack()
    }

    private fun ensureTrack(): AudioTrack {
        track?.let { return it }
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val created = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(max(minBytes, TRACK_BUFFER_FRAMES * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setSessionId(sessionId)
            .build()
        track = created
        trackPlaying = false
        writtenFrames = playedFrames
        headBase = playedFrames
        Log.i(TAG, "AudioTrack creado (sesión $sessionId, búfer ${created.bufferSizeInFrames} frames)")
        return created
    }

    private fun pauseTrack() {
        val output = track ?: return
        if (trackPlaying) {
            runCatching { output.pause() }
            trackPlaying = false
        }
        updatePlayed(output)
    }

    /** Una búsqueda en la única fuente que suena descarta lo ya mezclado que aún no ha sonado. */
    private fun handleFlushRequests(output: AudioTrack) {
        val pending = sources.filter { it.consumeOutputFlushRequest() }
        if (pending.isEmpty()) return
        val others = sources.any { it.isPlaying && !it.retired && it !in pending }
        if (others || session != null) return
        updatePlayed(output)
        runCatching {
            output.pause()
            output.flush()
        }
        trackPlaying = false
        writtenFrames = playedFrames
        headBase = playedFrames
        sources.forEach { it.dropUnplayedRecords(playedFrames) }
    }

    private fun updatePlayed(output: AudioTrack) {
        val head = output.playbackHeadPosition.toLong() and 0xffffffffL
        playedFrames = min(writtenFrames, headBase + head)
    }

    private fun releaseTrack() {
        val output = track ?: return
        runCatching { output.pause(); output.flush(); output.release() }
        track = null
        trackPlaying = false
        headBase = playedFrames
        writtenFrames = playedFrames
    }

    companion object {
        private const val TAG = "MixOutputEngine"
        const val SAMPLE_RATE = BusMixer.SAMPLE_RATE
        const val BLOCK_FRAMES = BusMixer.BLOCK_FRAMES
        const val TRACK_BUFFER_FRAMES = BLOCK_FRAMES * 8
        const val IDLE_WAIT_MS = 20L
    }
}

/**
 * Una transición armada en el motor. WAITING: A suena normal hasta el frame en que su posición llega a
 * `a.startMs` (+ los compases que haya que esperar a B). RUNNING: [MixDsp] mezcla A y B frame a frame.
 * DONE/FAILED: terminada; los avisos ([Listener]) llegan al hilo principal.
 */
class MixSession internal constructor(
    val doc: MixDocumentDTO,
    val a: BusSource,
    b: BusSource,
    private val barFrames: Int?,
    private val listener: Listener,
    private val post: (Runnable) -> Unit,
    private val log: (String) -> Unit,
    /** Velocidad de B (Sonic, `bpmA/bpmB` con beatSync): convierte tiempo de canción de B en frames de su salida. */
    private val bSpeed: Double = 1.0
) {
    interface Listener {
        /** B empieza a sonar (la interfaz pasa a B). */
        fun onAudible()
        fun onCompleted()
        fun onFailed(reason: String)
    }

    enum class State { WAITING, RUNNING, DONE, FAILED }

    @Volatile private var bSource = b
    /** Fuente de B: si Media3 la sustituyó (seek al prepararla), la nueva. */
    val b: BusSource get() = bSource.latest().also { bSource = it }

    val dsp = MixDsp(doc)
    val bOffsetFrames = MixDsp.frames(doc.b.offsetMs.toDouble()).toLong()
    /** Frame de la línea de tiempo en que entra el audio de B: [bOffsetFrames] más el ajuste de [alignB]. */
    internal var bStartFrames = bOffsetFrames
        private set
    @Volatile var state = State.WAITING
        internal set
    @Volatile var cancelled = false
        internal set
    @Volatile var timelineFrame = 0L
        internal set
    internal var waitedFrames = 0L
    private var shiftFrames = 0L
    private var audibleReported = false

    /** Instante de la línea de tiempo (ms) que acaba de mezclarse; para la interfaz. */
    val timelineMs: Double get() = timelineFrame * 1000.0 / MixDsp.SAMPLE_RATE

    /** La posición de A no casa con el documento (más de [MAX_LATE_MS] pasada): la mezcla no puede empezar a tiempo. */
    @Volatile internal var incoherent = false
        private set

    /** Frames de A que faltan para empezar la mezcla, o null si A aún no tiene posición. */
    internal fun framesUntilStart(sampleRate: Int): Long? {
        val nextUs = a.nextMediaTimeUs() ?: return null
        val startUs = doc.a.startMs * 1000L + shiftFrames * 1_000_000L / sampleRate
        var frames = (startUs - nextUs) * sampleRate / 1_000_000L
        // Unos milisegundos tarde es "a tiempo": la posición de A se conoce por bloques y no cae justo en la muestra.
        if (frames < 0 && -frames <= LATE_TOLERANCE_MS * sampleRate / 1000) return 0
        if (-frames > MAX_LATE_MS * sampleRate / 1000) {
            if (!incoherent) log("Posición de A incoherente (${nextUs / 1000} ms frente a ${doc.a.startMs} ms): no se mezcla")
            incoherent = true
            return null
        }
        if (frames < 0) {
            // Disparo tardío: se empieza en el siguiente compás de A para no entrar a destiempo.
            val bar = barFrames?.toLong() ?: return 0
            val skip = ceil(-frames.toDouble() / bar).toLong() * bar
            shiftFrames += skip
            frames += skip
            log("Mezcla tardía: se desplaza ${skip * 1000 / sampleRate} ms (compases enteros de A)")
        }
        return frames
    }

    internal fun bReady(): Boolean = b.availableFrames() >= B_READY_FRAMES || b.ended

    /** B aún no tiene audio: se espera un compás más (o un bloque sin compás). False si se agotó la espera. */
    internal fun postpone(sampleRate: Int): Boolean {
        if (waitedFrames > BusMixer.MAX_WAIT_FRAMES) return false
        shiftFrames += barFrames?.toLong() ?: (sampleRate / 50L)
        return true
    }

    internal fun start(engineFrame: Long) {
        state = State.RUNNING
        alignB(engineFrame)
        log("Mezcla en marcha (${doc.durationMs} ms, desplazada ${shiftFrames * 1000 / MixDsp.SAMPLE_RATE} ms)")
    }

    /**
     * B empieza justo en `b.startMs`: el seek de Media3 cae en un frame MP3 entero (hasta ~24 ms después) y su ancla
     * dice dónde empieza de verdad su audio. Si B trae audio de antes se descarta; si empieza después, entra más tarde.
     */
    private fun alignB(engineFrame: Long) {
        val bUs = b.nextMediaTimeUs() ?: return
        val diffUs = doc.b.startMs * 1000L - bUs
        if (abs(diffUs) > MAX_B_ALIGN_US) {
            log("B empieza en ${bUs / 1000} ms frente a ${doc.b.startMs} ms del documento: no se alinea")
            return
        }
        val frames = Math.round(diffUs * MixDsp.SAMPLE_RATE / 1_000_000.0 / bSpeed)
        if (frames == 0L) return
        if (frames > 0) b.skip(frames.toInt(), engineFrame) else bStartFrames = bOffsetFrames - frames
        log("B alineada a ${doc.b.startMs} ms: empezaba ${-diffUs / 1000.0} ms desviada")
    }

    internal fun reportAudibleIfNeeded() {
        if (audibleReported || timelineFrame < bOffsetFrames) return
        audibleReported = true
        post(Runnable { if (!cancelled) listener.onAudible() })
    }

    internal fun finish() {
        state = State.DONE
        a.retired = true
        post(Runnable { if (!cancelled) listener.onCompleted() })
    }

    internal fun fail(reason: String) {
        state = State.FAILED
        // B no debe sonar suelta mientras el hilo principal la libera.
        b.retired = true
        post(Runnable { if (!cancelled) listener.onFailed(reason) })
    }

    internal fun abandonFromEngine(reason: String) {
        if (state == State.DONE || state == State.FAILED || cancelled) return
        fail(reason)
    }

    companion object {
        /** Audio mínimo de B para empezar (~46 ms). */
        const val B_READY_FRAMES = 2048
        /** Retraso que se acepta como "a tiempo" al llegar al inicio de la mezcla. */
        const val LATE_TOLERANCE_MS = 60L
        /** Más tarde que esto (búsqueda lejos de la ventana o marcas de tiempo mal leídas) no se intenta la mezcla. */
        const val MAX_LATE_MS = 60_000L
        /** Desvío de B mayor que esto no es el redondeo del seek (marcas mal leídas): no se corrige. */
        const val MAX_B_ALIGN_US = 100_000L
    }
}

/** Lo que una [BusSource] necesita del motor (interfaz para poder probarla sin Android). */
interface BusHost {
    /** Frames de salida que ya han sonado. */
    val playedFrames: Long
    fun wake()
    fun remove(source: BusSource)
}

/**
 * Fuente del motor: PCM float estéreo de un ExoPlayer en un búfer circular (productor = hilo de reproducción,
 * consumidor = hilo del motor), con la posición de medio de cada escritura y lo que ya ha sonado de ella.
 */
class BusSource(private val engine: BusHost, val name: String) {

    private val lock = Object()
    private val ring = FloatArray(2 * RING_FRAMES)
    private var writeIndex = 0L
    private var readIndex = 0L
    /** (frame del búfer, posición de medio en µs) de cada escritura, para saber qué instante de la canción es cada frame. */
    private val anchors = ArrayDeque<LongArray>()
    /** (frame del motor al final del bloque, frames de esta fuente consumidos hasta entonces, frames del bloque). */
    private val records = ArrayDeque<LongArray>()
    private var consumedSinceFlush = 0L
    private var outputFlushRequested = false
    private var advancingReported = false

    @Volatile var isPlaying = false
        private set
    @Volatile var ended = false
        private set
    /** La sesión terminó con esta fuente (A tras la mezcla): el motor ya no la lee. */
    @Volatile var retired = false
        internal set
    /** Salida que Media3 creó para el mismo player después de liberar esta (flush por un seek). */
    @Volatile internal var replacedBy: BusSource? = null

    internal fun latest(): BusSource {
        var s = this
        while (true) s = s.replacedBy ?: return s
    }
    @Volatile var volume = 1f

    private var currentVolume = 1f
    private val volumes = FloatArray(MixOutputEngine.BLOCK_FRAMES)
    private val listeners = CopyOnWriteArrayList<BusAudioOutput.EventSink>()
    private var underrunNotified = false

    fun play() {
        isPlaying = true
        retired = false
        advancingReported = false
        engine.wake()
    }

    fun pause() {
        isPlaying = false
    }

    fun stop() {
        ended = true
    }

    /** Descarta lo pendiente (búsqueda): la posición vuelve a 0, como un `AudioTrack` tras `flush`. */
    fun flush() = synchronized(lock) {
        readIndex = writeIndex
        anchors.clear()
        records.clear()
        consumedSinceFlush = 0
        ended = false
        underrunNotified = false
        outputFlushRequested = true
    }

    fun release() {
        isPlaying = false
        engine.remove(this)
    }

    internal fun addListener(sink: BusAudioOutput.EventSink) { listeners += sink }
    internal fun removeListener(sink: BusAudioOutput.EventSink) { listeners -= sink }

    /** Escribe frames float estéreo intercalados. Devuelve los frames aceptados (0 si el búfer está lleno). */
    fun write(frames: FloatArray, count: Int, mediaTimeUs: Long?): Int = synchronized(lock) {
        val free = (RING_FRAMES - (writeIndex - readIndex)).toInt()
        val accepted = min(free, count)
        if (accepted <= 0) return 0
        if (mediaTimeUs != null) {
            anchors.addLast(longArrayOf(writeIndex, mediaTimeUs))
            while (anchors.size > MAX_ANCHORS) anchors.removeFirst()
        }
        for (i in 0 until accepted) {
            val slot = ((writeIndex + i) % RING_FRAMES).toInt()
            ring[2 * slot] = frames[2 * i]
            ring[2 * slot + 1] = frames[2 * i + 1]
        }
        writeIndex += accepted
        accepted
    }

    fun freeFrames(): Int = synchronized(lock) { (RING_FRAMES - (writeIndex - readIndex)).toInt() }

    fun availableFrames(): Int = synchronized(lock) { (writeIndex - readIndex).toInt() }

    /**
     * Frames escritos que aún no han sonado: los del búfer más los que el motor ya leyó sin reproducir. Es lo que
     * va por delante de la posición del player el punto donde Media3 aplica un cambio de velocidad (TempoReturn).
     */
    fun pendingFrames(): Long = synchronized(lock) {
        (writeIndex - readIndex) + (consumedSinceFlush - playedFrames()).coerceAtLeast(0L)
    }

    /** Posición de medio (µs) del siguiente frame que se leerá, o null sin datos. */
    fun nextMediaTimeUs(): Long? = synchronized(lock) {
        if (writeIndex == readIndex) return null
        val anchor = anchors.lastOrNull { it[0] <= readIndex } ?: return null
        anchor[1] + (readIndex - anchor[0]) * 1_000_000L / MixOutputEngine.SAMPLE_RATE
    }

    /** Lee hasta [frames] frames en [dst] (ceros si faltan) y anota en qué frame del motor se consumieron. */
    internal fun readInto(dst: FloatArray, frames: Int, engineFrame: Long): Int = synchronized(lock) {
        val available = min(frames.toLong(), writeIndex - readIndex).toInt()
        for (i in 0 until available) {
            val slot = ((readIndex + i) % RING_FRAMES).toInt()
            dst[2 * i] = ring[2 * slot]
            dst[2 * i + 1] = ring[2 * slot + 1]
        }
        dst.fill(0f, 2 * available, 2 * frames)
        readIndex += available
        if (available > 0) {
            consumedSinceFlush += available
            records.addLast(longArrayOf(engineFrame + available, consumedSinceFlush, available.toLong()))
            while (records.size > MAX_RECORDS) records.removeFirst()
            while (anchors.size > 1 && anchors[1][0] <= readIndex) anchors.removeFirst()
            if (underrunNotified && available == frames) underrunNotified = false
            notifyAdvancing()
        }
        available
    }

    /** Descarta hasta [frames] frames sin sonar (alinear B al empezar la mezcla); cuentan como consumidos. */
    internal fun skip(frames: Int, engineFrame: Long): Int = synchronized(lock) {
        val skipped = min(frames.toLong(), writeIndex - readIndex).toInt()
        if (skipped <= 0) return 0
        readIndex += skipped
        consumedSinceFlush += skipped
        records.addLast(longArrayOf(engineFrame, consumedSinceFlush, 0L))
        while (records.size > MAX_RECORDS) records.removeFirst()
        while (anchors.size > 1 && anchors[1][0] <= readIndex) anchors.removeFirst()
        skipped
    }

    /** Frames de esta fuente que ya han sonado según la cabeza del motor (posición del `AudioOutput`). */
    fun playedFrames(): Long = synchronized(lock) {
        val played = engine.playedFrames
        var result = 0L
        for (record in records) {
            val end = record[0]
            val count = record[2]
            val start = end - count
            result = when {
                played >= end -> record[1]
                played > start -> record[1] - count + (played - start)
                else -> return result
            }
        }
        result
    }

    internal fun dropUnplayedRecords(played: Long) = synchronized(lock) {
        while (records.isNotEmpty() && records.last()[0] > played) records.removeLast()
    }

    internal fun consumeOutputFlushRequest(): Boolean = synchronized(lock) {
        val requested = outputFlushRequested
        outputFlushRequested = false
        requested
    }

    /** Volumen por frame del bloque, suavizado hacia [volume] (sin escalones al cambiar el volumen del player). */
    internal fun nextVolume(frames: Int): FloatArray {
        val target = volume
        for (i in 0 until frames) {
            currentVolume += (target - currentVolume) * VOLUME_SMOOTHING
            volumes[i] = currentVolume
        }
        return volumes
    }

    internal fun notifyUnderrun() {
        if (underrunNotified) return
        underrunNotified = true
        listeners.forEach { it.onUnderrun() }
    }

    private fun notifyAdvancing() {
        if (advancingReported) return
        advancingReported = true
        val now = System.currentTimeMillis()
        listeners.forEach { it.onPositionAdvancing(now) }
    }

    companion object {
        /** ~0,5 s de audio por fuente. */
        const val RING_FRAMES = MixOutputEngine.SAMPLE_RATE / 2
        private const val MAX_ANCHORS = 256
        private const val MAX_RECORDS = 512
        private const val VOLUME_SMOOTHING = 0.0023f // ~10 ms a 44,1 kHz
    }
}
