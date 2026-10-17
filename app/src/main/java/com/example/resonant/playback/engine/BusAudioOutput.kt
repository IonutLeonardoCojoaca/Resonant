package com.example.resonant.playback.engine

import android.content.Context
import android.media.AudioDeviceInfo
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min

/**
 * `AudioOutput` de Media3 que entrega el PCM ya procesado (Sonic: velocidad de B y remuestreo a 44,1 kHz) a una
 * [BusSource] del [MixOutputEngine] en lugar de a un `AudioTrack` propio. La posición que devuelve es la de lo que
 * ya ha sonado por la salida única, así que el reloj del ExoPlayer sigue a ese `AudioTrack`.
 */
@OptIn(UnstableApi::class)
class BusAudioOutput(
    private val source: BusSource,
    private val config: AudioOutputProvider.OutputConfig,
    private val sessionId: Int
) : AudioOutput {

    /** Avisos de la fuente hacia los listeners de Media3. */
    interface EventSink {
        fun onPositionAdvancing(playoutStartSystemTimeMs: Long)
        fun onUnderrun()
    }

    private val channels = Integer.bitCount(config.channelMask).coerceIn(1, 2)
    private val bytesPerSample = if (config.encoding == C.ENCODING_PCM_FLOAT) 4 else 2
    private val listeners = CopyOnWriteArrayList<AudioOutput.Listener>()
    private var scratch = FloatArray(0)
    private var lastBuffer: ByteBuffer? = null
    private var lastPresentationUs = C.TIME_UNSET
    private var framesFromLastBuffer = 0L

    private val events = object : EventSink {
        override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) =
            listeners.forEach { it.onPositionAdvancing(playoutStartSystemTimeMs) }

        override fun onUnderrun() = listeners.forEach { it.onUnderrun() }
    }

    init {
        source.addListener(events)
    }

    override fun play() = source.play()

    override fun pause() = source.pause()

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        // El mismo buffer se reintenta hasta escribirlo entero: su posición de medio avanza con lo ya aceptado.
        if (buffer !== lastBuffer || presentationTimeUs != lastPresentationUs) {
            lastBuffer = buffer
            lastPresentationUs = presentationTimeUs
            framesFromLastBuffer = 0
        }
        val bytesPerFrame = channels * bytesPerSample
        val frames = min(buffer.remaining() / bytesPerFrame, source.freeFrames())
        if (frames <= 0) return buffer.remaining() < bytesPerFrame
        if (scratch.size < 2 * frames) scratch = FloatArray(2 * frames)

        val data = buffer.duplicate().order(ByteOrder.nativeOrder())
        var pos = buffer.position()
        for (i in 0 until frames) {
            val left = sample(data, pos)
            val right = if (channels == 2) sample(data, pos + bytesPerSample) else left
            scratch[2 * i] = left
            scratch[2 * i + 1] = right
            pos += bytesPerFrame
        }
        val mediaUs = if (presentationTimeUs >= 0) presentationTimeUs + framesFromLastBuffer * 1_000_000L / config.sampleRate else null
        val accepted = source.write(scratch, frames, mediaUs)
        framesFromLastBuffer += accepted
        buffer.position(buffer.position() + accepted * bytesPerFrame)
        return !buffer.hasRemaining()
    }

    private fun sample(data: ByteBuffer, pos: Int): Float =
        if (bytesPerSample == 4) data.getFloat(pos) else data.getShort(pos) / 32768f

    override fun flush() {
        lastBuffer = null
        source.flush()
    }

    override fun stop() = source.stop()

    override fun release() {
        source.removeListener(events)
        source.release()
        listeners.forEach { it.onReleased() }
    }

    override fun setVolume(volume: Float) {
        source.volume = volume
    }

    override fun isOffloadedPlayback(): Boolean = false

    override fun getAudioSessionId(): Int = sessionId

    override fun getSampleRate(): Int = config.sampleRate

    override fun getBufferSizeInFrames(): Long = BusSource.RING_FRAMES.toLong()

    override fun getPositionUs(): Long = source.playedFrames() * 1_000_000L / config.sampleRate

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

    override fun isStalled(): Boolean = false

    override fun addListener(listener: AudioOutput.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: AudioOutput.Listener) {
        listeners -= listener
    }

    override fun setPlaybackParameters(playbackParams: PlaybackParameters) = Unit

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit

    override fun setOffloadEndOfStream() = Unit

    override fun attachAuxEffect(effectId: Int) = Unit

    override fun setAuxEffectSendLevel(level: Float) = Unit

    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = Unit
}

/** Fuente actual de un ExoPlayer (Media3 puede crear otra salida al cambiar de formato). */
class BusHandle(val name: String) {
    @Volatile var source: BusSource? = null
}

/**
 * Proveedor de salidas: usa la configuración del `AudioTrackAudioOutputProvider` normal y sustituye la salida por
 * una [BusAudioOutput]. Formatos que el motor no mezcla (otra frecuencia, multicanal, offload) van por la salida
 * normal.
 */
@OptIn(UnstableApi::class)
class BusAudioOutputProvider(
    context: Context,
    private val engine: MixOutputEngine,
    private val handle: BusHandle
) : ForwardingAudioOutputProvider(AudioTrackAudioOutputProvider.Builder(context).build()) {

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val channels = Integer.bitCount(config.channelMask)
        val supported = config.sampleRate == MixOutputEngine.SAMPLE_RATE &&
            (config.encoding == C.ENCODING_PCM_16BIT || config.encoding == C.ENCODING_PCM_FLOAT) &&
            channels in 1..2 && !config.isOffload && !config.isTunneling
        if (!supported) {
            Log.w(TAG, "${handle.name}: formato no mezclable (${config.sampleRate} Hz, $channels canales); salida normal")
            return super.getAudioOutput(config)
        }
        val source = engine.newSource(handle.name)
        handle.source = source
        return BusAudioOutput(source, config, engine.sessionId)
    }

    companion object {
        private const val TAG = "BusAudioOutput"
    }
}

/**
 * Renderers con la salida del motor: `DefaultAudioSink` en 16 bits (en float no aplicaría la cadena) con Sonic a
 * 44,1 kHz, que hace el cambio de velocidad de B y el remuestreo, y [BusAudioOutputProvider].
 */
@OptIn(UnstableApi::class)
class BusRenderersFactory(
    context: Context,
    private val engine: MixOutputEngine,
    private val handle: BusHandle
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean
    ): AudioSink {
        val sonic = SonicAudioProcessor().apply { setOutputSampleRateHz(MixOutputEngine.SAMPLE_RATE) }
        return DefaultAudioSink.Builder(context)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(arrayOf<AudioProcessor>(), SilenceSkippingAudioProcessor(), sonic)
            )
            .setAudioOutputProvider(BusAudioOutputProvider(context, engine, handle))
            .build()
    }
}

private const val STUCK_PLAYING_TIMEOUT_MS = 60_000

/** ExoPlayer → su fuente en el motor. */
class BusPlayers {
    private val handles = WeakHashMap<Player, BusHandle>()

    @Synchronized
    fun register(player: Player, handle: BusHandle) {
        handles[player] = handle
    }

    @Synchronized
    fun sourceOf(player: Player): BusSource? = handles[player]?.source
}

/**
 * Construye un ExoPlayer que suena por el motor: renderers con [BusRenderersFactory], la sesión de audio del motor
 * (para el ecualizador del usuario) y registro en [MixOutputEngine.players]. B espera en `play()` sin avanzar
 * hasta que empieza la mezcla (hasta ~11 s), así que la detección de "atascado" de Media3 (10 s) se amplía.
 */
@OptIn(UnstableApi::class)
fun MixOutputEngine.buildPlayer(context: Context, name: String, configure: ExoPlayer.Builder.() -> Unit): ExoPlayer {
    val handle = BusHandle(name)
    val player = ExoPlayer.Builder(context, BusRenderersFactory(context, this, handle))
        .setStuckPlayingDetectionTimeoutMs(STUCK_PLAYING_TIMEOUT_MS)
        .apply(configure)
        .build()
    player.setAudioSessionId(sessionId)
    players.register(player, handle)
    return player
}
