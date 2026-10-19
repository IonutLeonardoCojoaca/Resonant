package com.example.resonant.managers

import android.content.Context
import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.example.resonant.playback.MixLanes
import com.example.resonant.playback.PhaseAlignment
import com.example.resonant.playback.PlaybackUrlResolver
import com.example.resonant.playback.RenderedTransition
import com.example.resonant.playback.TempoReturn
import com.example.resonant.playback.OfflineClips
import com.example.resonant.playback.TransitionPlanner
import com.example.resonant.playback.TransitionTiming
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.MixDocumentDTO
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.playback.engine.MixOutputEngine
import com.example.resonant.playback.engine.MixSession
import com.example.resonant.playback.engine.buildPlayer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Motor de transiciones entre canciones (crossfade simple, inteligente, Playmix, hueco y álbum).
 *
 * Todas las transiciones pasan por la misma máquina de estados (ver design D2 de
 * `fix-crossfade-stability`):
 *
 * ```
 * IDLE -> PREPARING -> FADING -> COMPLETED
 *            |    \       |
 *            |     \      +--(error B)--> FAILED (audible: A recupera volumen y sigue)
 *            |      +--(A termina antes de B READY)--> HANDOFF_WAIT --(B READY)--> COMPLETED
 *            +--(error B / plazo)--> FAILED (silencioso: A sigue intacta)
 * ```
 *
 * Reglas:
 * - La canción entrante (B) siempre es un player de un único item preparado en su punto de entrada.
 * - El fundido sigue la posición de la saliente (A), no el reloj de pared.
 * - La UI cambia a B en el instante en que B empieza a sonar ([onTransitionAudible]).
 * - Todo se ejecuta en el hilo principal.
 *
 * Las transiciones de Playmix con clip renderizado en el servidor (Resonant Studio) siguen otro camino,
 * [RenderedRun]: A → clip → B con dos empalmes cortos sobre material idéntico (ver `RenderedTransition`).
 */
class TransitionManager(
    private val context: Context,
    private val mediaSourceFactoryProvider: () -> MediaSource.Factory,
    private val mediaItemFactory: (Song) -> MediaItem,
    private val onTransitionComplete: (newPlayer: ExoPlayer, oldPlayer: ExoPlayer) -> Unit,
    private val onTransitionFailed: (oldPlayer: ExoPlayer, oldSong: Song?, nextSong: Song?, wasAudible: Boolean) -> Unit,
    private val onTransitionAudible: ((nextSong: Song, nextIndex: Int) -> Unit)? = null,
    private val onTransitionProgress: ((position: Long, duration: Long) -> Unit)? = null,
    /** Motor de salida único (spotify-style-transition-engine D5); null = camino anterior de dos players. */
    private val mixEngine: MixOutputEngine? = null
) {

    private val musicAudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    companion object {
        private const val TAG = "TransitionManager"
        private const val ANIMATION_FRAME_DELAY_MS = 16L
        /** Refresco de la interfaz durante una mezcla del motor (el audio no depende de él). */
        private const val ENGINE_TICK_MS = 50L
        private const val GAP_FADE_OUT_MS = 300L
        private const val EQ_MAX_ATTENUATION: Short = -1500
        private const val EQ_MEDIUM_ATTENUATION: Short = -800
        private const val EQ_LIGHT_ATTENUATION: Short = -400
        private const val RENDERED_TICK_MS = 10L
        private const val SPLICE_SEEK_TOLERANCE_MS = 20L
        private const val PHASE_TOLERANCE_MS = 15L
        private const val PHASE_CHECK_MS = 250L
        private const val PHASE_CORRECTION_WINDOW_MS = 1_000.0
        private const val MAX_PHASE_NUDGE = 0.03
        private const val TEMPO_RIDE_FROM = 0.75
        private const val LATE_TOLERANCE_MS = 30.0
    }

    private fun createTransitionPlayer(): ExoPlayer {
        val engine = mixEngine
        val player = if (engine != null) {
            engine.buildPlayer(context, "transition") { setMediaSourceFactory(mediaSourceFactoryProvider()) }
        } else {
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactoryProvider())
                .build()
        }
        return player.apply {
            setAudioAttributes(musicAudioAttributes, false)
            setWakeMode(C.WAKE_MODE_NETWORK)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * playmix-extended-beatmatch: al terminar una mezcla ampliada (B a más de ±6 %), B no salta a 1,0 sino que se
     * entrega aquí con su velocidad para que el servicio haga la rampa de vuelta ([TempoReturn]). Null = 1,0 directo.
     */
    var onTempoReturn: ((player: ExoPlayer, fromRate: Double) -> Unit)? = null

    @Volatile
    var isTransitioning: Boolean = false
        private set

    /** `true` si en la transición en curso B ya se oye (la interfaz ya muestra B), sea cual sea la vía. */
    val isAudible: Boolean
        get() = rendered?.audible ?: engineRun?.audible ?: planned?.audible ?: active?.audible ?: false

    /** Player precargado (silencioso) o player de la transición en curso. */
    private var nextPlayer: ExoPlayer? = null
    private var preloadedSongId: String? = null
    private var preloadListener: Player.Listener? = null

    private var oldPlayerEq: Equalizer? = null
    private var newPlayerEq: Equalizer? = null

    @Volatile
    var transitionProgress: Float = 0f
        private set

    /** Sesión de audio del player entrante durante una transición (0 si no hay o aún no tiene). */
    val transitionPlayerSessionId: Int
        @OptIn(UnstableApi::class)
        get() = if (isTransitioning) runCatching { nextPlayer?.audioSessionId ?: 0 }.getOrDefault(0) else 0
    private var currentPlayer: ExoPlayer? = null // El 'oldPlayer'
    private var currentSong: Song? = null

    // Volúmenes normalizados absolutos para la transición
    private var targetOldPlayerVolume: Float = 1.0f
    private var targetNewPlayerVolume: Float = 1.0f

    // =================================================================================================
    // ===                                  MÁQUINA DE ESTADOS                                       ===
    // =================================================================================================

    private enum class Phase { PREPARING, FADING, HANDOFF_WAIT, GAP_WAIT }

    /**
     * Estado de la transición en curso. Hay como máximo una instancia viva ([active]).
     *
     * @param desiredFadeMs duración deseada del fundido; 0 es un corte.
     * @param gapMs silencio entre A y B (solo Playmix con hueco); 0 si no hay.
     * @param onBeforePlayB se ejecuta justo antes de que B empiece a sonar (EQ, tempo).
     * @param applyFrame aplica volúmenes y EQ para un progreso del fundido en [0, 1].
     */
    private inner class ActiveTransition(
        val oldPlayer: ExoPlayer,
        val oldSong: Song,
        val nextSong: Song,
        val nextIndex: Int,
        val newPlayer: ExoPlayer,
        val entryMs: Long,
        val desiredFadeMs: Long,
        val gapMs: Long,
        val label: String,
        val onBeforePlayB: () -> Unit,
        val applyFrame: (progress: Float) -> Unit
    ) {
        var phase = Phase.PREPARING
        var aEnded = false
        var audible = false
        var fadeStartPositionA = 0L
        var fadeDurationMs = 0L
        var deadlineUptime = 0L
        var gapStartedUptime = -1L
        var aListener: Player.Listener? = null
        var bListener: Player.Listener? = null
        var lastProgressReportUptime = 0L
    }

    private var active: ActiveTransition? = null
    private var supervisionRunnable: Runnable? = null
    private var animationRunnable: Runnable? = null

    /** Audio útil que le queda a A. `Long.MAX_VALUE` si no se puede saber. */
    private fun remainingAudioA(t: ActiveTransition): Long {
        val end = TransitionTiming.audioEndMs(
            exoDurationMs = t.oldPlayer.duration,
            analysisDurationMs = (t.oldSong.audioAnalysis?.durationMs ?: 0).toLong(),
            audioEndMs = (t.oldSong.audioAnalysis?.audioEndMs ?: 0).toLong()
        )
        if (end <= 0) return Long.MAX_VALUE
        return end - t.oldPlayer.currentPosition
    }

    private fun beginTransition(t: ActiveTransition) {
        active = t
        isTransitioning = true
        transitionProgress = 0f
        currentPlayer = t.oldPlayer
        currentSong = t.oldSong
        nextPlayer = t.newPlayer

        val aListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onOutgoingEnded(t)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "A falló durante la transición (${error.errorCodeName}); se trata como final de A.")
                onOutgoingEnded(t)
            }
        }
        val bListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) supervise(t)
            }

            override fun onPlayerError(error: PlaybackException) {
                failTransition(t, "B falló: ${error.errorCodeName}")
            }
        }
        t.aListener = aListener
        t.bListener = bListener
        t.oldPlayer.addListener(aListener)
        t.newPlayer.addListener(bListener)

        if (t.oldPlayer.playbackState == Player.STATE_ENDED) t.aEnded = true
        if (t.newPlayer.playbackState == Player.STATE_IDLE) {
            try { t.newPlayer.prepare() } catch (e: Exception) {
                failTransition(t, "no se pudo preparar B: ${e.message}")
                return
            }
        }

        val remaining = remainingAudioA(t)
        t.deadlineUptime = SystemClock.uptimeMillis() +
            TransitionTiming.transitionDeadlineMs(if (remaining == Long.MAX_VALUE) t.desiredFadeMs else remaining)

        Log.i(TAG, "▶ ${t.label}: ${t.oldSong.title} → ${t.nextSong.title} (entrada=${t.entryMs}ms, fundido=${t.desiredFadeMs}ms, hueco=${t.gapMs}ms, restanteA=${remaining}ms)")

        if (t.gapMs > 0) {
            startGapFade(t)
        } else {
            startSupervision(t)
        }
    }

    private fun startSupervision(t: ActiveTransition) {
        supervisionRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                if (active !== t) return
                supervise(t)
                if (active === t && t.phase != Phase.FADING) {
                    mainHandler.postDelayed(this, TransitionTiming.SUPERVISION_TICK_MS)
                }
            }
        }
        supervisionRunnable = runnable
        mainHandler.post(runnable)
    }

    /** Un paso de supervisión. Idempotente: puede llamarse desde el tick o desde el listener de B. */
    private fun supervise(t: ActiveTransition) {
        if (active !== t) return
        val now = SystemClock.uptimeMillis()
        val bReady = t.newPlayer.playbackState == Player.STATE_READY

        try {
            when (t.phase) {
                Phase.PREPARING -> {
                    // El seekbar sigue a A mientras se aplaza el fundido.
                    if (now - t.lastProgressReportUptime >= 200) {
                        t.lastProgressReportUptime = now
                        val duration = t.oldPlayer.duration
                        if (duration > 0) onTransitionProgress?.invoke(t.oldPlayer.currentPosition, duration)
                    }
                    if (t.aEnded) {
                        t.phase = Phase.HANDOFF_WAIT
                        t.deadlineUptime = now + TransitionTiming.HANDOFF_TIMEOUT_MS
                        Log.w(TAG, "A terminó antes de que B estuviera lista. Esperando relevo.")
                        if (bReady) handoffNow(t)
                    } else if (bReady) {
                        when (val decision = TransitionTiming.decideFadeStart(remainingAudioA(t), t.desiredFadeMs)) {
                            is TransitionTiming.FadeDecision.Start -> startFade(t, decision.durationMs)
                            TransitionTiming.FadeDecision.WaitForEnd -> { /* A termina en breve: relevo al final */ }
                        }
                    } else if (now > t.deadlineUptime) {
                        failTransition(t, "B no estuvo lista en el plazo")
                    }
                }
                Phase.HANDOFF_WAIT -> {
                    if (bReady) handoffNow(t)
                    else if (now > t.deadlineUptime) failTransition(t, "B no estuvo lista tras el final de A")
                }
                Phase.GAP_WAIT -> {
                    val gapElapsed = now - t.gapStartedUptime >= t.gapMs
                    if (gapElapsed && bReady) handoffNow(t)
                    else if (now > t.gapStartedUptime + t.gapMs + TransitionTiming.HANDOFF_TIMEOUT_MS) {
                        failTransition(t, "B no estuvo lista tras el hueco")
                    }
                }
                Phase.FADING -> { /* lo lleva la animación */ }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error supervisando la transición", e)
            failTransition(t, "excepción en supervisión: ${e.message}")
        }
    }

    private fun onOutgoingEnded(t: ActiveTransition) {
        if (active !== t) return
        t.aEnded = true
        when (t.phase) {
            Phase.PREPARING, Phase.HANDOFF_WAIT, Phase.GAP_WAIT -> supervise(t)
            Phase.FADING -> { /* la animación fuerza progreso 1 en el siguiente frame */ }
        }
    }

    private fun markAudible(t: ActiveTransition) {
        if (t.audible) return
        t.audible = true
        try {
            onTransitionAudible?.invoke(t.nextSong, t.nextIndex)
        } catch (e: Exception) {
            Log.e(TAG, "Error notificando onTransitionAudible", e)
        }
    }

    /** Arranca B y el fundido. Con `durationMs == 0` es un corte: se completa en el primer frame. */
    private fun startFade(t: ActiveTransition, durationMs: Long) {
        t.phase = Phase.FADING
        t.fadeDurationMs = durationMs
        t.fadeStartPositionA = t.oldPlayer.currentPosition
        supervisionRunnable?.let { mainHandler.removeCallbacks(it) }

        try {
            t.onBeforePlayB()
        } catch (e: Exception) {
            Log.e(TAG, "Error preparando efectos de B; se continúa sin ellos", e)
        }
        try {
            t.newPlayer.volume = if (durationMs == 0L) targetNewPlayerVolume else 0f
            t.newPlayer.play()
        } catch (e: Exception) {
            failTransition(t, "no se pudo arrancar B: ${e.message}")
            return
        }
        markAudible(t)
        Log.i(TAG, "🎬 Fundido iniciado: ${durationMs}ms desde posA=${t.fadeStartPositionA}ms")
        startAnimation(t)
    }

    private fun startAnimation(t: ActiveTransition) {
        animationRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                if (active !== t || t.phase != Phase.FADING) return

                val progress = if (t.aEnded) 1f else TransitionTiming.fadeProgress(
                    positionAMs = t.oldPlayer.currentPosition,
                    fadeStartPositionAMs = t.fadeStartPositionA,
                    fadeDurationMs = t.fadeDurationMs
                )
                transitionProgress = progress

                try {
                    if (!t.newPlayer.isPlaying && t.newPlayer.playbackState == Player.STATE_READY) {
                        // play() es asíncrono; si B aún no refleja isPlaying, insistimos sin bloquear.
                        t.newPlayer.play()
                    }
                    t.applyFrame(progress)

                    val now = SystemClock.uptimeMillis()
                    if (now - t.lastProgressReportUptime >= 500) {
                        t.lastProgressReportUptime = now
                        val duration = t.newPlayer.duration
                        if (duration > 0) onTransitionProgress?.invoke(t.newPlayer.currentPosition, duration)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error aplicando el frame del fundido", e)
                    failTransition(t, "excepción en animación: ${e.message}")
                    return
                }

                if (progress < 1f) {
                    mainHandler.postDelayed(this, ANIMATION_FRAME_DELAY_MS)
                } else {
                    completeTransition(t)
                }
            }
        }
        animationRunnable = runnable
        mainHandler.post(runnable)
    }

    /** Playmix con hueco: A se apaga en [GAP_FADE_OUT_MS] siguiendo su posición, se pausa y se espera. */
    private fun startGapFade(t: ActiveTransition) {
        t.phase = Phase.FADING
        t.fadeDurationMs = GAP_FADE_OUT_MS
        t.fadeStartPositionA = t.oldPlayer.currentPosition
        val startVolume = targetOldPlayerVolume

        animationRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                if (active !== t || t.phase != Phase.FADING) return
                val progress = if (t.aEnded) 1f else TransitionTiming.fadeProgress(
                    t.oldPlayer.currentPosition, t.fadeStartPositionA, t.fadeDurationMs
                )
                try { t.oldPlayer.volume = startVolume * (1f - progress) } catch (_: Exception) {}
                if (progress < 1f) {
                    mainHandler.postDelayed(this, ANIMATION_FRAME_DELAY_MS)
                } else {
                    try { if (!t.aEnded) t.oldPlayer.pause() } catch (_: Exception) {}
                    t.phase = Phase.GAP_WAIT
                    t.gapStartedUptime = SystemClock.uptimeMillis()
                    Log.i(TAG, "⏸ Hueco de ${t.gapMs}ms iniciado")
                    startSupervision(t)
                }
            }
        }
        animationRunnable = runnable
        mainHandler.post(runnable)
    }

    /** Relevo directo (A ya terminó o el hueco acabó): B suena a su volumen objetivo y se completa. */
    private fun handoffNow(t: ActiveTransition) {
        try {
            t.onBeforePlayB()
        } catch (e: Exception) {
            Log.e(TAG, "Error preparando efectos de B en el relevo; se continúa sin ellos", e)
        }
        try {
            t.newPlayer.volume = targetNewPlayerVolume
            t.newPlayer.play()
        } catch (e: Exception) {
            failTransition(t, "no se pudo arrancar B en el relevo: ${e.message}")
            return
        }
        markAudible(t)
        transitionProgress = 1f
        completeTransition(t)
    }

    private fun detachTransition(t: ActiveTransition) {
        supervisionRunnable?.let { mainHandler.removeCallbacks(it) }
        animationRunnable?.let { mainHandler.removeCallbacks(it) }
        supervisionRunnable = null
        animationRunnable = null
        t.aListener?.let { runCatching { t.oldPlayer.removeListener(it) } }
        t.bListener?.let { runCatching { t.newPlayer.removeListener(it) } }
        t.aListener = null
        t.bListener = null
        if (active === t) active = null
    }

    private fun releaseTransitionEqs() {
        try {
            oldPlayerEq?.enabled = false
            oldPlayerEq?.release()
        } catch (e: Exception) {}
        try {
            newPlayerEq?.enabled = false
            newPlayerEq?.release()
        } catch (e: Exception) {}
        oldPlayerEq = null
        newPlayerEq = null
    }

    private fun resetState() {
        isTransitioning = false
        nextPlayer = null
        preloadedSongId = null
        preloadListener = null
        transitionProgress = 0f
        currentPlayer = null
        currentSong = null
        targetOldPlayerVolume = 1.0f
        targetNewPlayerVolume = 1.0f
    }

    private fun completeTransition(t: ActiveTransition) {
        if (active !== t) return
        detachTransition(t)
        releaseTransitionEqs()

        val newPlayer = t.newPlayer
        val oldPlayer = t.oldPlayer
        val targetVolume = targetNewPlayerVolume
        resetState()

        // Restaurar parámetros del nuevo reproductor
        try {
            if (abs(newPlayer.playbackParameters.speed - 1.0f) > 0.01f) {
                newPlayer.setPlaybackParameters(PlaybackParameters(1.0f))
            }
            newPlayer.volume = targetVolume
        } catch (e: Exception) { Log.e(TAG, "Error restaurando parámetros", e) }

        onTransitionComplete(newPlayer, oldPlayer)
        Log.d(TAG, "🎉 Transición COMPLETADA: ${t.nextSong.title}")
    }

    private fun failTransition(t: ActiveTransition, reason: String) {
        if (active !== t) return
        Log.e(TAG, "❌ Transición fallida (${t.label}): $reason. A recupera su estado.")
        detachTransition(t)
        releaseTransitionEqs()

        val oldPlayer = t.oldPlayer
        val wasAudible = t.audible
        try {
            oldPlayer.volume = targetOldPlayerVolume
            if (t.gapMs > 0 && !t.aEnded && !oldPlayer.isPlaying) oldPlayer.play()
        } catch (e: Exception) { Log.w(TAG, "No se pudo restaurar A", e) }
        try {
            t.newPlayer.stop()
            t.newPlayer.release()
        } catch (e: Exception) { Log.w(TAG, "No se pudo liberar B", e) }

        resetState()
        onTransitionFailed(oldPlayer, t.oldSong, t.nextSong, wasAudible)
    }

    // =================================================================================================
    // ===                                  API PÚBLICA                                              ===
    // =================================================================================================

    /**
     * Transición automática (fuera de Playmix). [plan] lo calcula el servicio con
     * [com.example.resonant.playback.TransitionPlanner] o es la receta del servidor; sin plan,
     * A termina de forma natural. En modo álbum se hace un corte sobre la precarga.
     */
    fun startTransition(oldPlayer: ExoPlayer,
                        oldSong: Song,
                        nextSong: Song,
                        nextSongIndex: Int,
                        plan: TransitionPlanner.Plan?,
                        isAlbumMode: Boolean,
                        automixEnabled: Boolean,
                        oldSongTargetVolume: Float = 1.0f,
                        nextSongTargetVolume: Float = 1.0f,
                        playmixTransition: PlaymixTransitionDTO? = null
    ) {
        Log.d(TAG, "🎯 startTransition - nextSong: ${nextSong.title}")

        if (playmixTransition != null) {
            startPlaymixTransition(oldPlayer, oldSong, nextSong, nextSongIndex, playmixTransition, oldSongTargetVolume, nextSongTargetVolume)
            return
        }

        val shouldTransition = if (isAlbumMode) automixEnabled else plan != null
        if (!shouldTransition) {
            Log.d(TAG, "❌ Transición desactivada. A termina de forma natural.")
            onTransitionFailed(oldPlayer, oldSong, nextSong, false)
            return
        }

        if (isTransitioning) {
            Log.w(TAG, "Ignorando trigger, ya hay una transición en curso.")
            return
        }
        targetOldPlayerVolume = oldSongTargetVolume
        targetNewPlayerVolume = nextSongTargetVolume

        if (isAlbumMode || plan == null) {
            beginAlbumCut(oldPlayer, oldSong, nextSong, nextSongIndex)
        } else if (mixEngine != null) {
            beginEngineRun(oldPlayer, oldSong, nextSong, nextSongIndex, plan)
        } else {
            beginPlanned(oldPlayer, oldSong, nextSong, nextSongIndex, plan)
        }
    }

    /**
     * Transición de Playmix: respeta mixMode, fadeCurveType, bandFadeTypes, gapMs y entryPointMs
     * configurados en el editor. La llama MusicPlaybackService cuando sourceType == PLAYMIX.
     */
    fun startPlaymixTransition(
        oldPlayer: ExoPlayer,
        oldSong: Song,
        nextSong: Song,
        nextSongIndex: Int,
        transition: PlaymixTransitionDTO,
        oldSongTargetVolume: Float = 1.0f,
        nextSongTargetVolume: Float = 1.0f
    ) {
        Log.d(TAG, "🎹 PlayMix: ${oldSong.title} → ${nextSong.title} mixMode=${transition.mixMode}, curve=${transition.fadeCurveType}, crossfade=${transition.crossfadeDurationMs}ms, gap=${transition.gapMs}ms, entry=${transition.entryPointMs}ms")

        if (isTransitioning) {
            Log.w(TAG, "Ignorando trigger, ya hay una transición en curso.")
            return
        }
        targetOldPlayerVolume = oldSongTargetVolume
        targetNewPlayerVolume = nextSongTargetVolume

        val plan = RenderedTransition.planFor(transition, System.currentTimeMillis())
        if (plan != null) {
            beginRendered(oldPlayer, oldSong, nextSong, nextSongIndex, transition, plan)
            return
        }
        beginLegacyPlaymix(oldPlayer, oldSong, nextSong, nextSongIndex, transition)
    }

    /** Transición de Playmix con el motor de volúmenes y EQ del dispositivo (sin clip renderizado). */
    private fun beginLegacyPlaymix(
        oldPlayer: ExoPlayer,
        oldSong: Song,
        nextSong: Song,
        nextSongIndex: Int,
        transition: PlaymixTransitionDTO
    ) {
        val entryMs = TransitionTiming.resolveEntryPointMs(
            TransitionTiming.EntryMode.PLAYMIX, transition.entryPointMs, null, null
        )
        val newPlayer = acquireTransitionPlayer(nextSong, entryMs)
        val isCut = transition.mixMode == "hard_edit" || transition.crossfadeDurationMs <= 0

        if (isCut) {
            beginTransition(
                ActiveTransition(
                    oldPlayer, oldSong, nextSong, nextSongIndex, newPlayer, entryMs,
                    desiredFadeMs = 0L,
                    gapMs = transition.gapMs.toLong().coerceAtLeast(0L),
                    label = if (transition.gapMs > 0) "playmix-gap" else "playmix-cut",
                    onBeforePlayB = {},
                    applyFrame = { progress ->
                        oldPlayer.volume = (1f - progress) * targetOldPlayerVolume
                    }
                )
            )
            return
        }

        val mixMode = transition.mixMode ?: "crossfade"
        val bandFade = transition.bandFadeTypes
        beginTransition(
            ActiveTransition(
                oldPlayer, oldSong, nextSong, nextSongIndex, newPlayer, entryMs,
                desiredFadeMs = transition.crossfadeDurationMs.toLong().coerceAtLeast(100L),
                gapMs = 0L,
                label = "playmix-$mixMode",
                onBeforePlayB = {
                    if (mixMode == "freq_split" || mixMode == "club_drop" || mixMode == "crossfade" || bandFade != null) {
                        initializeIntelligentEQ(oldPlayer, newPlayer)
                    }
                },
                applyFrame = { rawProgress ->
                    when (mixMode) {
                        "overlap" -> {
                            oldPlayer.volume = targetOldPlayerVolume
                            newPlayer.volume = targetNewPlayerVolume
                        }
                        "freq_split" -> {
                            val p = applyFadeCurve(rawProgress, transition.fadeCurveType)
                            oldPlayer.volume = sqrt(1f - p) * targetOldPlayerVolume
                            newPlayer.volume = sqrt(p) * targetNewPlayerVolume
                            applyFreqSplitEq(oldPlayerEq, newPlayerEq, p)
                        }
                        "club_drop" -> {
                            val p = applyFadeCurve(rawProgress, transition.fadeCurveType)
                            oldPlayer.volume = (1f - p.pow(1.5f)).coerceIn(0f, 1f) * targetOldPlayerVolume
                            newPlayer.volume = p.pow(0.7f).coerceIn(0f, 1f) * targetNewPlayerVolume
                            applyClubDropEq(oldPlayerEq, newPlayerEq, p)
                        }
                        else -> {
                            val (oldVol, newVol) = computePlaymixVolumes(rawProgress, transition)
                            oldPlayer.volume = oldVol * targetOldPlayerVolume
                            newPlayer.volume = newVol * targetNewPlayerVolume
                            applyCrossfadeBandEq(oldPlayerEq, newPlayerEq, rawProgress, transition)
                        }
                    }
                }
            )
        )
    }

    // =================================================================================================
    // ===                    RESONANT STUDIO: TRANSICIÓN CON CLIP RENDERIZADO                       ===
    // =================================================================================================

    private enum class RenderPhase { PREPARING, SPLICE_IN, CLIP, SPLICE_OUT }

    /**
     * Transición A → clip → B. A sigue sonando en silencio durante el clip para que una cancelación
     * la devuelva en su posición real. B se precarga en la posición equivalente al cambio.
     */
    private inner class RenderedRun(
        val oldPlayer: ExoPlayer,
        val oldSong: Song,
        val nextSong: Song,
        val nextIndex: Int,
        val transition: PlaymixTransitionDTO,
        val plan: RenderedTransition.Plan,
        val clipPlayer: ExoPlayer,
        val bPlayer: ExoPlayer,
        val volumeA: Float,
        val volumeB: Float
    ) {
        var phase = RenderPhase.PREPARING
        var spliceStartUptime = 0L
        var audible = false
        var aEnded = false
        var clipEndedUptime = -1L
        var lastProgressReportUptime = 0L
        val listeners = mutableListOf<Pair<ExoPlayer, Player.Listener>>()
    }

    private var rendered: RenderedRun? = null

    /**
     * Clip descargado con la Playmix (collection-queue-offline D5): la URI estable con la misma clave de caché con
     * la que se descargó, para que la fábrica de medios lo lea de la caché permanente. Si no, la URL firmada.
     */
    private fun clipMediaItem(plan: RenderedTransition.Plan): MediaItem {
        val local = plan.localUri
        val builder = MediaItem.Builder().setMimeType(MimeTypes.AUDIO_FLAC)
        val clip = local?.let { OfflineClips.parseUri(it) }
        return if (clip != null) {
            Log.d(TAG, "🎚 Studio: clip local ${clip.first} r${clip.second}")
            builder.setUri(local).setCustomCacheKey(OfflineClips.keyFor(clip.first, clip.second)).build()
        } else {
            builder.setUri(plan.url).build()
        }
    }
    private var renderedRunnable: Runnable? = null

    private fun beginRendered(
        oldPlayer: ExoPlayer,
        oldSong: Song,
        nextSong: Song,
        nextIndex: Int,
        transition: PlaymixTransitionDTO,
        plan: RenderedTransition.Plan
    ) {
        Log.i(TAG, "🎚 Studio: ${oldSong.title} → ${nextSong.title} con clip (A=${plan.aStartMs}ms, clip=${plan.clipDurationMs}ms, B=${plan.bEndMs}ms)")
        val clipPlayer = try {
            createTransitionPlayer().apply {
                setMediaItem(clipMediaItem(plan))
                volume = 0f
                prepare()
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo preparar el clip; motor actual", e)
            beginClipFallback(oldPlayer, oldSong, nextSong, nextIndex, transition)
            return
        }
        val bPlayer = acquireTransitionPlayer(nextSong, plan.bPreparePositionMs)

        val job = RenderedRun(
            oldPlayer, oldSong, nextSong, nextIndex, transition, plan, clipPlayer, bPlayer,
            volumeA = targetOldPlayerVolume, volumeB = targetNewPlayerVolume
        )
        rendered = job
        isTransitioning = true
        transitionProgress = 0f
        currentPlayer = oldPlayer
        currentSong = oldSong
        nextPlayer = bPlayer

        listen(job, oldPlayer, object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) job.aEnded = true
            }
        })
        listen(job, clipPlayer, object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (job.phase == RenderPhase.PREPARING) fallbackToLegacy(job, "clip: ${error.errorCodeName}")
                else failRendered(job, "clip: ${error.errorCodeName}")
            }
        })
        listen(job, bPlayer, object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failRendered(job, "B: ${error.errorCodeName}")
            }
        })

        val runnable = object : Runnable {
            override fun run() {
                if (rendered !== job) return
                try {
                    tickRendered(job)
                } catch (e: Exception) {
                    Log.e(TAG, "Error en la transición con clip", e)
                    failRendered(job, "excepción: ${e.message}")
                    return
                }
                if (rendered === job) mainHandler.postDelayed(this, RENDERED_TICK_MS)
            }
        }
        renderedRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun listen(run: RenderedRun, player: ExoPlayer, listener: Player.Listener) {
        player.addListener(listener)
        run.listeners += player to listener
    }

    private fun tickRendered(run: RenderedRun) {
        val plan = run.plan
        val now = SystemClock.uptimeMillis()
        when (run.phase) {
            RenderPhase.PREPARING -> {
                val positionA = run.oldPlayer.currentPosition
                reportProgress(run, now, positionA, run.oldPlayer.duration)
                if (run.aEnded || RenderedTransition.missedPreroll(positionA, plan)) {
                    fallbackToLegacy(run, "el clip no estuvo listo dentro del preroll")
                    return
                }
                val clipPosition = RenderedTransition.clipPositionForA(positionA, plan) ?: return
                if (run.clipPlayer.playbackState != Player.STATE_READY) return
                if (abs(run.clipPlayer.currentPosition - clipPosition) > SPLICE_SEEK_TOLERANCE_MS) {
                    run.clipPlayer.seekTo(clipPosition)
                }
                run.clipPlayer.volume = 0f
                run.clipPlayer.play()
                run.phase = RenderPhase.SPLICE_IN
                run.spliceStartUptime = now
            }
            RenderPhase.SPLICE_IN -> {
                val p = RenderedTransition.spliceProgress(now, run.spliceStartUptime)
                val clipVolume = RenderedTransition.clipVolume(run.clipPlayer.currentPosition, plan, run.volumeA, run.volumeB)
                run.clipPlayer.volume = clipVolume * p
                run.oldPlayer.volume = run.volumeA * (1f - p)
                if (p >= 1f) run.phase = RenderPhase.CLIP
            }
            RenderPhase.CLIP -> {
                val clipPosition = run.clipPlayer.currentPosition
                run.clipPlayer.volume = RenderedTransition.clipVolume(clipPosition, plan, run.volumeA, run.volumeB)
                transitionProgress = (clipPosition.toFloat() / plan.clipDurationMs).coerceIn(0f, 1f)
                if (!run.audible && RenderedTransition.isAudible(clipPosition, plan)) markRenderedAudible(run)
                if (run.audible) {
                    reportProgress(run, now, RenderedTransition.bPositionForClip(plan, clipPosition), run.bPlayer.duration)
                } else {
                    reportProgress(run, now, run.oldPlayer.currentPosition, run.oldPlayer.duration)
                }

                val bReady = run.bPlayer.playbackState == Player.STATE_READY
                if (RenderedTransition.shouldSwitchToB(clipPosition, plan) && bReady &&
                    run.clipPlayer.playbackState != Player.STATE_ENDED
                ) {
                    val target = RenderedTransition.bPositionForClip(plan, clipPosition)
                    if (abs(run.bPlayer.currentPosition - target) > SPLICE_SEEK_TOLERANCE_MS) run.bPlayer.seekTo(target)
                    run.bPlayer.volume = 0f
                    applyClipRate(run)
                    run.bPlayer.play()
                    run.phase = RenderPhase.SPLICE_OUT
                    run.spliceStartUptime = now
                } else if (run.clipPlayer.playbackState == Player.STATE_ENDED) {
                    // El clip terminó sin B lista: B entra en su posición de final de clip en cuanto pueda.
                    if (run.clipEndedUptime < 0) run.clipEndedUptime = now
                    if (bReady) {
                        if (abs(run.bPlayer.currentPosition - plan.bEndMs) > SPLICE_SEEK_TOLERANCE_MS) run.bPlayer.seekTo(plan.bEndMs)
                        run.bPlayer.volume = run.volumeB
                        applyClipRate(run)
                        run.bPlayer.play()
                        completeRendered(run)
                    } else if (now - run.clipEndedUptime > TransitionTiming.HANDOFF_TIMEOUT_MS) {
                        failRendered(run, "B no estuvo lista al terminar el clip")
                    }
                }
            }
            RenderPhase.SPLICE_OUT -> {
                val p = RenderedTransition.spliceProgress(now, run.spliceStartUptime)
                run.bPlayer.volume = run.volumeB * p
                run.clipPlayer.volume = RenderedTransition.clipVolume(run.clipPlayer.currentPosition, plan, run.volumeA, run.volumeB) * (1f - p)
                if (p >= 1f) completeRendered(run)
            }
        }
    }

    private fun reportProgress(run: RenderedRun, now: Long, position: Long, duration: Long) {
        if (now - run.lastProgressReportUptime < 250 || duration <= 0) return
        run.lastProgressReportUptime = now
        onTransitionProgress?.invoke(position, duration)
    }

    private fun markRenderedAudible(run: RenderedRun) {
        if (run.audible) return
        run.audible = true
        try {
            onTransitionAudible?.invoke(run.nextSong, run.nextIndex)
        } catch (e: Exception) {
            Log.e(TAG, "Error notificando onTransitionAudible", e)
        }
    }

    private fun detachRendered(run: RenderedRun) {
        renderedRunnable?.let { mainHandler.removeCallbacks(it) }
        renderedRunnable = null
        run.listeners.forEach { (player, listener) -> runCatching { player.removeListener(listener) } }
        run.listeners.clear()
        if (rendered === run) rendered = null
    }

    private fun releaseClip(run: RenderedRun) {
        runCatching { run.clipPlayer.stop(); run.clipPlayer.release() }
            .onFailure { Log.w(TAG, "No se pudo liberar el clip", it) }
    }

    private fun completeRendered(run: RenderedRun) {
        if (rendered !== run) return
        markRenderedAudible(run)
        detachRendered(run)
        releaseClip(run)
        resetState()
        runCatching { run.bPlayer.volume = run.volumeB }
        onTransitionComplete(run.bPlayer, run.oldPlayer)
        if (run.plan.extendedRate) handOffTempo(run.bPlayer, run.plan.bRate, "clip")
        Log.d(TAG, "🎉 Transición con clip COMPLETADA: ${run.nextSong.title}")
    }

    /**
     * Clip de sincronía ampliada ([RenderedTransition.Plan.extendedRate]): B sigue tras el clip a `bRate`, como en el
     * clip, para que los dos lados del fundido sean el mismo material. Los clips del rango estándar no cambian.
     */
    private fun applyClipRate(run: RenderedRun) {
        if (!run.plan.extendedRate) return
        runCatching { run.bPlayer.playbackParameters = PlaybackParameters(run.plan.bRate.toFloat()) }
            .onFailure { Log.w(TAG, "No se pudo poner B a bRate", it) }
    }

    /** Fin de una mezcla ampliada: B pasa a [TempoReturn] desde [rate] (sin el gancho, 1,0 directo como antes). */
    private fun handOffTempo(player: ExoPlayer, rate: Double, source: String) {
        Log.i(TAG, "⏱️ Mezcla ampliada ($source): extended=true, bRate=${"%.4f".format(rate)}, rampa=${TempoReturn.TEMPO_RETURN_MS} ms")
        val hook = onTempoReturn
        if (hook == null) {
            runCatching { player.playbackParameters = PlaybackParameters(1f) }
            return
        }
        try {
            hook(player, rate)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo iniciar la vuelta al tempo", e)
            runCatching { player.playbackParameters = PlaybackParameters(1f) }
        }
    }

    /** El clip no sirvió antes de empalmar: B vuelve a ser la precarga y sigue el motor actual. */
    private fun fallbackToLegacy(run: RenderedRun, reason: String) {
        if (rendered !== run) return
        Log.w(TAG, "Studio: se usa el motor actual ($reason)")
        detachRendered(run)
        releaseClip(run)
        runCatching { run.oldPlayer.volume = run.volumeA }
        isTransitioning = false
        nextPlayer = run.bPlayer
        preloadedSongId = run.nextSong.id
        targetOldPlayerVolume = run.volumeA
        targetNewPlayerVolume = run.volumeB
        beginClipFallback(run.oldPlayer, run.oldSong, run.nextSong, run.nextIndex, run.transition)
    }

    /**
     * Sin clip (no se pudo preparar o no llegó a tiempo): un documento v2 se mezcla en el motor, como la escucha
     * del editor; el crossfade antiguo ignora sus carriles y el beatmatching. Sin motor o sin documento v2, el antiguo.
     */
    private fun beginClipFallback(oldPlayer: ExoPlayer, oldSong: Song, nextSong: Song, nextIndex: Int, transition: PlaymixTransitionDTO) {
        val doc = transition.mixDocument?.takeIf { it.isV2 && mixEngine != null }
        if (doc == null) {
            beginLegacyPlaymix(oldPlayer, oldSong, nextSong, nextIndex, transition)
            return
        }
        val a = TransitionPlanner.trackOf(oldSong.audioAnalysis, -1L)
        val b = TransitionPlanner.trackOf(nextSong.audioAnalysis, -1L)
        // playmix-extended-beatmatch: con la capacidad y `extendedSync`, también el rango ampliado (si no, como siempre).
        val plan = TransitionPlanner.playmixPlan(
            doc, "studio-${doc.preset?.code ?: "custom"}", a, b, CapabilitiesManager.isPlaymixExtendedSyncEnabled()
        )
        beginEngineRun(oldPlayer, oldSong, nextSong, nextIndex, plan)
    }

    /** Fallo con el clip ya sonando: A recupera su volumen en su posición real y sigue. */
    private fun failRendered(run: RenderedRun, reason: String) {
        if (rendered !== run) return
        Log.e(TAG, "❌ Transición con clip fallida: $reason. A recupera su estado.")
        detachRendered(run)
        releaseClip(run)
        runCatching { run.bPlayer.stop(); run.bPlayer.release() }
        runCatching { run.oldPlayer.volume = run.volumeA }
        val wasAudible = run.audible
        resetState()
        onTransitionFailed(run.oldPlayer, run.oldSong, run.nextSong, wasAudible)
    }

    private fun cancelRendered(run: RenderedRun, reason: String) {
        Log.w(TAG, "🛑 Transición con clip $reason")
        detachRendered(run)
        releaseClip(run)
        runCatching { run.bPlayer.stop(); run.bPlayer.release() }
        runCatching { run.oldPlayer.volume = run.volumeA }
        resetState()
    }

    /**
     * Buscar o forzar durante el clip: si B ya es la canción actual, B suena desde su posición equivalente;
     * si no, se vuelve a A, que sigue en su posición real.
     */
    private fun forceCompleteRendered(run: RenderedRun) {
        if (!run.audible) {
            cancelRendered(run, "abandonada antes de entrar B")
            return
        }
        try {
            val target = RenderedTransition.bPositionForClip(run.plan, run.clipPlayer.currentPosition)
            if (abs(run.bPlayer.currentPosition - target) > SPLICE_SEEK_TOLERANCE_MS) run.bPlayer.seekTo(target)
            run.bPlayer.volume = run.volumeB
            applyClipRate(run)
            if (!run.bPlayer.isPlaying) run.bPlayer.play()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo arrancar B al forzar", e)
        }
        completeRendered(run)
    }

    // =================================================================================================
    // ===        TRANSICIÓN AUTOMÁTICA: DOCUMENTO DE MEZCLA (SUAVE, CORTE, INTELIGENTE, RECETA)      ===
    // =================================================================================================

    /**
     * Ejecuta un plan de [TransitionPlanner] o una receta del servidor (unify-transition-engine).
     * La línea de tiempo sigue a la posición de A desde `a.startMs`; B entra en `b.offsetMs` desde
     * `b.startMs`; los volúmenes siguen los carriles de ganancia y el EQ del dispositivo aproxima los
     * carriles de EQ. Un plan sin carriles de EQ no crea ecualizadores.
     *
     * Si B tarda en estar lista, A se queda en su volumen de entrada y la línea de tiempo se desplaza lo
     * que tarde B ([PlannedRun.lagMs]): la mezcla nunca avanza con B en silencio.
     *
     * Con `beatSync`, B suena al tempo de A, corrige la fase con pequeños ajustes de velocidad mientras
     * entra y vuelve a su tempo en el último cuarto, cuando A ya se está yendo.
     */
    private inner class PlannedRun(
        val oldPlayer: ExoPlayer,
        val oldSong: Song,
        val nextSong: Song,
        val nextIndex: Int,
        val plan: TransitionPlanner.Plan,
        val bPlayer: ExoPlayer,
        val volumeA: Float,
        val volumeB: Float
    ) {
        val doc: MixDocumentDTO get() = plan.doc
        val gridA: PhaseAlignment.Grid? = oldSong.audioAnalysis?.let { PhaseAlignment.gridOf(it.bpmPrecise, it.firstDownbeatMs, it.beatsPerBar) }
        val gridB: PhaseAlignment.Grid? = nextSong.audioAnalysis?.let { PhaseAlignment.gridOf(it.bpmPrecise, it.firstDownbeatMs, it.beatsPerBar) }
        var started = false
        var audible = false
        var aEndedUptime = -1L
        var timelineAtAEnd = 0.0
        var lastProgressReportUptime = 0L
        /** Retraso acumulado de B: la línea de tiempo se desplaza para que B entre donde marca el documento. */
        var lagMs = 0.0
        var waitStartUptime = -1L
        var lastPhaseCheckUptime = 0L
        var speed = 1f
        val listeners = mutableListOf<Pair<ExoPlayer, Player.Listener>>()
    }

    private var planned: PlannedRun? = null
    private var plannedRunnable: Runnable? = null

    private fun beginPlanned(oldPlayer: ExoPlayer, oldSong: Song, newSong: Song, nextIndex: Int, plan: TransitionPlanner.Plan) {
        val doc = plan.doc
        Log.i(TAG, "🎚️ Mezcla ${plan.label}: ${oldSong.title} → ${newSong.title} (A=${doc.a.startMs}ms, B=${doc.b.startMs}ms, ${doc.durationMs}ms, sync=${plan.beatSync}, extended=${plan.extendedSync})")
        val bPlayer = acquireTransitionPlayer(newSong, doc.b.startMs.toLong())
        val job = PlannedRun(oldPlayer, oldSong, newSong, nextIndex, plan, bPlayer, targetOldPlayerVolume, targetNewPlayerVolume)
        planned = job
        isTransitioning = true
        transitionProgress = 0f
        currentPlayer = oldPlayer
        currentSong = oldSong
        nextPlayer = bPlayer

        val aListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) markAEnded(job)
            }

            // Un error de A (red) congela su posición: se trata como el final de A y la mezcla sigue con el reloj.
            override fun onPlayerError(error: PlaybackException) = markAEnded(job)
        }
        val bListener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = failPlanned(job, "B: ${error.errorCodeName}")
        }
        oldPlayer.addListener(aListener); job.listeners += oldPlayer to aListener
        bPlayer.addListener(bListener); job.listeners += bPlayer to bListener

        val runnable = object : Runnable {
            override fun run() {
                if (planned !== job) return
                try {
                    tickPlanned(job)
                } catch (e: Exception) {
                    Log.e(TAG, "Error en la mezcla", e)
                    failPlanned(job, "excepción: ${e.message}")
                    return
                }
                if (planned === job) mainHandler.postDelayed(this, ANIMATION_FRAME_DELAY_MS)
            }
        }
        plannedRunnable = runnable
        mainHandler.post(runnable)
    }

    /** A terminó (o falló): la línea de tiempo sigue desde su última posición con el reloj. */
    private fun markAEnded(job: PlannedRun) {
        if (job.aEndedUptime >= 0) return
        job.timelineAtAEnd = (job.oldPlayer.currentPosition - job.doc.a.startMs).toDouble()
        job.aEndedUptime = SystemClock.uptimeMillis()
    }

    /** Posición de A menos `a.startMs`; si A ya terminó, sigue con el reloj. Sin descontar el retraso de B. */
    private fun rawTimeline(job: PlannedRun): Double =
        if (job.aEndedUptime >= 0) job.timelineAtAEnd + (SystemClock.uptimeMillis() - job.aEndedUptime)
        else (job.oldPlayer.currentPosition - job.doc.a.startMs).toDouble()

    private fun tickPlanned(job: PlannedRun) {
        val doc = job.doc
        var t = rawTimeline(job) - job.lagMs
        val now = SystemClock.uptimeMillis()
        if (!job.started) {
            if (now - job.lastProgressReportUptime >= 250 && job.oldPlayer.duration > 0) {
                job.lastProgressReportUptime = now
                onTransitionProgress?.invoke(job.oldPlayer.currentPosition, job.oldPlayer.duration)
            }
            if (t < 0) return
            job.started = true
            if (job.plan.hasEq) initializeIntelligentEQ(job.oldPlayer, job.bPlayer)
        }

        if (!job.audible && t >= doc.b.offsetMs) {
            if (job.bPlayer.playbackState != Player.STATE_READY) {
                // El plazo cuenta desde que se empieza a esperar a B, no desde la posición de A: si la
                // transición arranca tarde (seek, disparo tardío) B tiene su margen completo.
                if (job.waitStartUptime < 0) job.waitStartUptime = now
                if (now - job.waitStartUptime > TransitionTiming.HANDOFF_TIMEOUT_MS) {
                    failPlanned(job, "B no estuvo lista")
                    return
                }
                // Mientras B no suena, A se queda en el volumen del instante de entrada (sin bajadas).
                job.oldPlayer.volume = (job.volumeA * MixLanes.gain(doc, 'a', doc.b.offsetMs.toDouble())).toFloat().coerceIn(0f, 1f)
                return
            }
            val late = t - doc.b.offsetMs
            val barA = job.gridA?.let { PhaseAlignment.barMs(it) }
            if (job.plan.beatSync && barA != null && late > LATE_TOLERANCE_MS) {
                // Con beatmatch B no puede entrar a destiempo: espera al siguiente compás de A.
                job.lagMs += kotlin.math.ceil(late / barA) * barA
                job.oldPlayer.volume = (job.volumeA * MixLanes.gain(doc, 'a', doc.b.offsetMs.toDouble())).toFloat().coerceIn(0f, 1f)
                return
            }
            // B entra ahora: lo que se haya retrasado desplaza la línea de tiempo.
            job.lagMs += late
            t = doc.b.offsetMs.toDouble()
            job.bPlayer.volume = 0f
            if (job.plan.beatSync) setSpeed(job, job.plan.bpmRatio.toFloat())
            job.bPlayer.play()
            job.audible = true
            try { onTransitionAudible?.invoke(job.nextSong, job.nextIndex) } catch (e: Exception) { Log.e(TAG, "onTransitionAudible", e) }
        }

        job.oldPlayer.volume = (job.volumeA * MixLanes.gain(doc, 'a', t)).toFloat().coerceIn(0f, 1f)
        if (job.audible) job.bPlayer.volume = (job.volumeB * MixLanes.gain(doc, 'b', t)).toFloat().coerceIn(0f, 1f)
        if (job.plan.hasEq) {
            applyDocumentEq(oldPlayerEq, doc, 'a', t)
            if (job.audible) applyDocumentEq(newPlayerEq, doc, 'b', t)
        }
        if (job.audible && job.plan.beatSync) adjustTempo(job, t, now)
        transitionProgress = if (doc.durationMs > 0) (t / doc.durationMs).toFloat().coerceIn(0f, 1f) else 1f

        if (job.audible && now - job.lastProgressReportUptime >= 500 && job.bPlayer.duration > 0) {
            job.lastProgressReportUptime = now
            onTransitionProgress?.invoke(job.bPlayer.currentPosition, job.bPlayer.duration)
        }
        if (job.audible && t >= doc.durationMs) completePlanned(job)
    }

    /**
     * Tempo de B durante la mezcla: el de A con un ajuste fino de fase (±[MAX_PHASE_NUDGE]) mientras
     * entra, y vuelta lineal a su tempo propio en el último cuarto.
     */
    private fun adjustTempo(job: PlannedRun, t: Double, now: Long) {
        val ratio = job.plan.bpmRatio
        val duration = job.doc.durationMs.toDouble()
        val rideFrom = duration * TEMPO_RIDE_FROM
        // playmix-extended-beatmatch: en una mezcla ampliada B sigue a `ratio` hasta el final (sin la vuelta del 75 %);
        // la vuelta a su tempo la hace TempoReturn después de la mezcla.
        if (t >= rideFrom && !job.plan.extendedSync) {
            val p = if (duration > rideFrom) ((t - rideFrom) / (duration - rideFrom)).coerceIn(0.0, 1.0) else 1.0
            setSpeed(job, (ratio + (1.0 - ratio) * p).toFloat())
            return
        }
        val gridA = job.gridA ?: return
        val gridB = job.gridB ?: return
        if (now - job.lastPhaseCheckUptime < PHASE_CHECK_MS) return
        job.lastPhaseCheckUptime = now
        val correction = PhaseAlignment.entryCorrectionMs(job.oldPlayer.currentPosition, gridA, job.bPlayer.currentPosition, gridB)
        val nudge = if (abs(correction) <= PHASE_TOLERANCE_MS) 0.0
        else (correction / PHASE_CORRECTION_WINDOW_MS).coerceIn(-MAX_PHASE_NUDGE, MAX_PHASE_NUDGE)
        setSpeed(job, (ratio * (1.0 + nudge)).toFloat())
    }

    private fun setSpeed(job: PlannedRun, speed: Float) {
        if (abs(speed - job.speed) < 0.001f) return
        job.speed = speed
        try {
            job.bPlayer.playbackParameters = PlaybackParameters(speed)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo ajustar el tempo de B", e)
        }
    }

    private fun applyDocumentEq(eq: Equalizer?, doc: MixDocumentDTO, side: Char, t: Double) {
        eq ?: return
        try {
            for (i in 0 until eq.numberOfBands) {
                val centerHz = eq.getCenterFreq(i.toShort()) / 1000
                val db = MixLanes.eqDb(doc, side, MixLanes.bandForFrequency(centerHz), t)
                setSafeBandLevel(eq, i, (db * 100).toFloat())
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo aplicar el EQ de la mezcla", e)
        }
    }

    private fun detachPlanned(job: PlannedRun) {
        plannedRunnable?.let { mainHandler.removeCallbacks(it) }
        plannedRunnable = null
        job.listeners.forEach { (player, listener) -> runCatching { player.removeListener(listener) } }
        job.listeners.clear()
        if (planned === job) planned = null
    }

    private fun completePlanned(job: PlannedRun) {
        if (planned !== job) return
        detachPlanned(job)
        releaseTransitionEqs()
        resetState()
        val extended = job.plan.extendedSync
        runCatching {
            if (!extended && abs(job.bPlayer.playbackParameters.speed - 1f) > 0.001f) job.bPlayer.playbackParameters = PlaybackParameters(1f)
            job.bPlayer.volume = job.volumeB
        }
        onTransitionComplete(job.bPlayer, job.oldPlayer)
        if (extended) handOffTempo(job.bPlayer, job.plan.bpmRatio, job.plan.label)
        Log.d(TAG, "🎉 Mezcla completada (${job.plan.label}): ${job.nextSong.title}")
    }

    private fun failPlanned(job: PlannedRun, reason: String) {
        if (planned !== job) return
        Log.e(TAG, "❌ Mezcla fallida (${job.plan.label}): $reason. A recupera su estado.")
        detachPlanned(job)
        releaseTransitionEqs()
        runCatching { job.bPlayer.stop(); job.bPlayer.release() }
        runCatching { job.oldPlayer.volume = job.volumeA }
        val wasAudible = job.audible
        resetState()
        onTransitionFailed(job.oldPlayer, job.oldSong, job.nextSong, wasAudible)
    }

    private fun cancelPlanned(job: PlannedRun, reason: String) {
        Log.w(TAG, "🛑 Mezcla $reason")
        detachPlanned(job)
        releaseTransitionEqs()
        runCatching { job.bPlayer.stop(); job.bPlayer.release() }
        runCatching { job.oldPlayer.volume = job.volumeA }
        resetState()
    }

    /** Buscar o forzar durante la mezcla: si B ya suena se completa; si no, se vuelve a A. */
    private fun forceCompletePlanned(job: PlannedRun) {
        if (!job.audible) {
            // Por el camino de fallo: el servicio restaura EQ, loudness y seekbar y A sigue sonando.
            failPlanned(job, "abandonada antes de entrar B")
            return
        }
        runCatching { job.bPlayer.volume = job.volumeB; if (!job.bPlayer.isPlaying) job.bPlayer.play() }
        completePlanned(job)
    }

    // =================================================================================================
    // ===     MOTOR DE SALIDA ÚNICO: EL DOCUMENTO SE MEZCLA EN EL MOTOR A LA MUESTRA (D5)            ===
    // =================================================================================================

    /**
     * Transición sobre [MixOutputEngine]: el motor empieza la mezcla en el frame exacto en que A llega a
     * `a.startMs`, aplica volumen, EQ, filtro, ruido, bucle y eco con [com.example.resonant.playback.dsp.MixDsp]
     * y termina solo. Aquí solo se arma, se informa a la interfaz y se devuelve B a su tempo al terminar:
     * no hay corrección de fase (un solo reloj) ni ecualizadores del sistema.
     */
    private inner class EngineRun(
        val oldPlayer: ExoPlayer,
        val oldSong: Song,
        val nextSong: Song,
        val nextIndex: Int,
        val plan: TransitionPlanner.Plan,
        val bPlayer: ExoPlayer,
        val volumeA: Float,
        val volumeB: Float,
        val barMs: Double?
    ) {
        var session: MixSession? = null
        var audible = false
        var lastProgressReportUptime = 0L
    }

    private var engineRun: EngineRun? = null
    private var engineRunnable: Runnable? = null

    private fun beginEngineRun(oldPlayer: ExoPlayer, oldSong: Song, newSong: Song, nextIndex: Int, plan: TransitionPlanner.Plan) {
        val engine = mixEngine ?: return beginPlanned(oldPlayer, oldSong, newSong, nextIndex, plan)
        val doc = plan.doc
        val bPlayer = acquireTransitionPlayer(newSong, doc.b.startMs.toLong())
        val sourceA = engine.players.sourceOf(oldPlayer)
        val sourceB = engine.players.sourceOf(bPlayer)
        if (sourceA == null || sourceB == null) {
            // Algún player no suena por el motor (formato no mezclable): camino anterior.
            Log.w(TAG, "Fuentes del motor no disponibles; mezcla con dos players")
            return beginPlanned(oldPlayer, oldSong, newSong, nextIndex, plan)
        }
        val gridA = oldSong.audioAnalysis?.let { PhaseAlignment.gridOf(it.bpmPrecise, it.firstDownbeatMs, it.beatsPerBar) }
        val barMs = gridA?.let { PhaseAlignment.barMs(it) }
        val job = EngineRun(oldPlayer, oldSong, newSong, nextIndex, plan, bPlayer, targetOldPlayerVolume, targetNewPlayerVolume, barMs)
        engineRun = job
        isTransitioning = true
        transitionProgress = 0f
        currentPlayer = oldPlayer
        currentSong = oldSong
        nextPlayer = bPlayer
        Log.i(TAG, "🎚️ Mezcla en el motor ${plan.label}: ${oldSong.title} → ${newSong.title} (A=${doc.a.startMs}ms, B=${doc.b.startMs}ms, ${doc.durationMs}ms, v${doc.version}, sync=${plan.beatSync}, velocidad B=${"%.4f".format(plan.bpmRatio)}, extended=${plan.extendedSync})")

        oldPlayer.volume = job.volumeA
        bPlayer.volume = job.volumeB
        // B a tempo de A durante toda la ventana (Sonic en su salida); vuelve a 1 al terminar la mezcla.
        if (plan.beatSync) runCatching { bPlayer.playbackParameters = PlaybackParameters(plan.bpmRatio.toFloat()) }
        // B llena su búfer; el motor no lo consume hasta que empiece la mezcla.
        bPlayer.play()

        val barFrames = barMs?.let { (it * MixOutputEngine.SAMPLE_RATE / 1000.0).toInt() }
        job.session = engine.arm(doc, sourceA, sourceB, barFrames, object : MixSession.Listener {
            override fun onAudible() {
                if (engineRun !== job) return
                job.audible = true
                try { onTransitionAudible?.invoke(job.nextSong, job.nextIndex) } catch (e: Exception) { Log.e(TAG, "onTransitionAudible", e) }
            }

            override fun onCompleted() = completeEngineRun(job)

            override fun onFailed(reason: String) = failEngineRun(job, reason)
        }, bSpeed = if (plan.beatSync) plan.bpmRatio else 1.0)

        val runnable = object : Runnable {
            override fun run() {
                if (engineRun !== job) return
                tickEngineRun(job)
                if (engineRun === job) mainHandler.postDelayed(this, ENGINE_TICK_MS)
            }
        }
        engineRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun tickEngineRun(job: EngineRun) {
        val session = job.session ?: return
        val doc = job.plan.doc
        val t = session.timelineMs
        transitionProgress = if (doc.durationMs > 0) (t / doc.durationMs).toFloat().coerceIn(0f, 1f) else 1f
        val now = SystemClock.uptimeMillis()
        if (now - job.lastProgressReportUptime >= 250) {
            job.lastProgressReportUptime = now
            val player = if (job.audible) job.bPlayer else job.oldPlayer
            if (player.duration > 0) onTransitionProgress?.invoke(player.currentPosition, player.duration)
        }
    }

    private fun detachEngineRun(job: EngineRun) {
        engineRunnable?.let { mainHandler.removeCallbacks(it) }
        engineRunnable = null
        if (engineRun === job) engineRun = null
    }

    private fun completeEngineRun(job: EngineRun) {
        if (engineRun !== job) return
        detachEngineRun(job)
        resetState()
        val extended = job.plan.extendedSync
        runCatching {
            // B vuelve a su tempo cuando ya suena sola: dentro de la ventana A sigue sonando y se desfasarían.
            // Mezcla ampliada (playmix-extended-beatmatch): la vuelta es una rampa de TempoReturn, no un salto.
            if (job.plan.beatSync && !extended) job.bPlayer.playbackParameters = PlaybackParameters(1f)
            job.bPlayer.volume = job.volumeB
        }
        onTransitionComplete(job.bPlayer, job.oldPlayer)
        if (extended) handOffTempo(job.bPlayer, job.plan.bpmRatio, "motor ${job.plan.label}")
        Log.d(TAG, "🎉 Mezcla completada en el motor (${job.plan.label}): ${job.nextSong.title}")
    }

    private fun failEngineRun(job: EngineRun, reason: String) {
        if (engineRun !== job) return
        Log.e(TAG, "❌ Mezcla fallida en el motor (${job.plan.label}): $reason. A recupera su estado.")
        detachEngineRun(job)
        job.session?.let { mixEngine?.cancel(it) }
        runCatching { job.bPlayer.stop(); job.bPlayer.release() }
        runCatching { job.oldPlayer.volume = job.volumeA }
        val wasAudible = job.audible
        resetState()
        onTransitionFailed(job.oldPlayer, job.oldSong, job.nextSong, wasAudible)
    }

    private fun cancelEngineRun(job: EngineRun, reason: String) {
        Log.w(TAG, "🛑 Mezcla del motor $reason")
        detachEngineRun(job)
        job.session?.let { mixEngine?.cancel(it) }
        runCatching { job.bPlayer.stop(); job.bPlayer.release() }
        runCatching { job.oldPlayer.volume = job.volumeA }
        resetState()
    }

    /** Pausa, búsqueda o foco perdido durante la mezcla: si B ya suena se completa; si no, se vuelve a A. */
    private fun forceCompleteEngineRun(job: EngineRun) {
        if (!job.audible) {
            failEngineRun(job, "abandonada antes de entrar B")
            return
        }
        job.session?.let { mixEngine?.completeNow(it) }
        completeEngineRun(job)
    }

    private fun beginAlbumCut(oldPlayer: ExoPlayer, oldSong: Song, nextSong: Song, nextSongIndex: Int) {
        Log.i(TAG, "🤖 Automix de álbum (corte sobre la precarga)")
        val newPlayer = acquireTransitionPlayer(nextSong, 0L)
        beginTransition(
            ActiveTransition(
                oldPlayer, oldSong, nextSong, nextSongIndex, newPlayer, 0L,
                desiredFadeMs = 0L, gapMs = 0L, label = "album-cut",
                onBeforePlayB = {},
                applyFrame = { progress -> oldPlayer.volume = (1f - progress) * targetOldPlayerVolume }
            )
        )
    }

    fun cancelCurrentTransition() {
        rendered?.let { cancelRendered(it, "cancelada"); return }
        engineRun?.let { cancelEngineRun(it, "cancelada"); return }
        planned?.let { cancelPlanned(it, "cancelada"); return }
        val t = active
        if (t == null) {
            releasePreload()
            return
        }
        Log.w(TAG, "🛑 Cancelando transición en curso (${t.label})")
        detachTransition(t)
        releaseTransitionEqs()
        try { t.newPlayer.stop(); t.newPlayer.release() } catch (e: Exception) { Log.e(TAG, "Error al liberar B", e) }
        resetState()
        Log.d(TAG, "✅ Transición cancelada.")
    }

    /**
     * Seek sobre A mientras su mezcla se prepara (playmix-mix-bounded-seekbar D6): se cancela sin
     * `onTransitionFailed`, así que no se suprime y vuelve a armarse al llegar otra vez al disparo.
     * B se libera y A recupera su volumen; el EQ y la cola de A los restaura el servicio.
     * Devuelve `false` (sin tocar nada) si no hay transición o si B ya se oye.
     */
    fun cancelPreparation(): Boolean {
        if (!isTransitioning || isAudible) return false
        rendered?.let { cancelRendered(it, "cancelada por seek durante la preparación"); return true }
        engineRun?.let { cancelEngineRun(it, "cancelada por seek durante la preparación"); return true }
        planned?.let { cancelPlanned(it, "cancelada por seek durante la preparación"); return true }
        val t = active ?: return false
        // Clásica antes de que entre B (preparando o apagando A hacia el hueco): cancelar sin forzar a B.
        Log.w(TAG, "🛑 Transición (${t.label}) cancelada por seek durante la preparación")
        detachTransition(t)
        releaseTransitionEqs()
        try {
            t.oldPlayer.volume = targetOldPlayerVolume
            if (t.gapMs > 0 && !t.aEnded && !t.oldPlayer.isPlaying) t.oldPlayer.play()
        } catch (e: Exception) { Log.w(TAG, "No se pudo restaurar A", e) }
        try { t.newPlayer.stop(); t.newPlayer.release() } catch (e: Exception) { Log.e(TAG, "Error al liberar B", e) }
        resetState()
        return true
    }

    /** Completa la transición ya, con B sonando a su volumen objetivo. */
    fun forceCompleteTransition() {
        rendered?.let { forceCompleteRendered(it); return }
        engineRun?.let { forceCompleteEngineRun(it); return }
        planned?.let { forceCompletePlanned(it); return }
        val t = active
        if (t == null) {
            if (isTransitioning) cancelCurrentTransition()
            return
        }
        Log.w(TAG, "🚀 Forzando completado de la transición (${t.label})")
        try {
            t.newPlayer.volume = targetNewPlayerVolume
            if (!t.newPlayer.isPlaying) t.newPlayer.play()
        } catch (e: Exception) { Log.w(TAG, "No se pudo arrancar B al forzar", e) }
        markAudible(t)
        completeTransition(t)
    }

    private fun releasePreload() {
        preloadListener?.let { l -> nextPlayer?.let { runCatching { it.removeListener(l) } } }
        try {
            nextPlayer?.stop()
            nextPlayer?.release()
        } catch (error: Exception) {
            Log.e(TAG, "Error al liberar la precarga", error)
        } finally {
            nextPlayer = null
            preloadedSongId = null
            preloadListener = null
        }
    }

    /**
     * Precarga la siguiente canción en un player de un único item, en silencio y en su punto
     * de entrada, para que la transición pueda usarlo tal cual.
     */
    fun preloadNextSong(nextSong: Song?, entryMs: Long = 0L) {
        if (isTransitioning) {
            Log.d(TAG, "No se precarga durante una transición.")
            return
        }
        releasePreload()

        if (nextSong == null) {
            Log.d(TAG, "No hay siguiente canción para precargar.")
            return
        }

        Log.i(TAG, "⚡️ Precargando: ${nextSong.title} en ${entryMs}ms")

        try {
            val newPlayer = createTransitionPlayer()
            val mediaItem = mediaItemFactory(nextSong)
            var progressiveFallbackAttempted = false

            val listener = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    val isRecoverable = error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED

                    if (isRecoverable && !progressiveFallbackAttempted) {
                        progressiveFallbackAttempted = true
                        Log.w(TAG, "Precarga falló para ${nextSong.title}; reintentando en progressive")
                        newPlayer.setMediaItem(
                            mediaItemFactory(
                                nextSong.copy(
                                    url = null,
                                    playbackMimeType = null,
                                    playbackExpiresAtUtc = null,
                                    playbackDeliveryMode = PlaybackUrlResolver.DELIVERY_MODE_PROGRESSIVE
                                )
                            ),
                            entryMs
                        )
                        newPlayer.prepare()
                        return
                    }

                    Log.w(TAG, "Descartando precarga fallida de ${nextSong.title}: ${error.errorCodeName}")
                    newPlayer.removeListener(this)
                    newPlayer.release()
                    if (this@TransitionManager.nextPlayer === newPlayer) {
                        this@TransitionManager.nextPlayer = null
                        preloadedSongId = null
                        preloadListener = null
                    }
                }
            }
            newPlayer.addListener(listener)
            newPlayer.setMediaItem(mediaItem, entryMs)
            newPlayer.volume = 0f
            newPlayer.prepare()

            this.nextPlayer = newPlayer
            this.preloadedSongId = nextSong.id
            this.preloadListener = listener
        } catch (e: Exception) {
            Log.e(TAG, "Error durante la precarga de ${nextSong.title}", e)
            releasePreload()
        }
    }

    /**
     * Devuelve el player con el que se hará la transición: la precarga si es de esta canción
     * (con `seekTo` si está lejos del punto de entrada) o uno nuevo de un único item.
     * Nunca se llama a `setMediaItems` con la cola completa sobre un player de transición.
     */
    private fun acquireTransitionPlayer(nextSong: Song, entryMs: Long): ExoPlayer {
        val preloaded = nextPlayer
        if (preloaded != null && preloadedSongId == nextSong.id) {
            preloadListener?.let { runCatching { preloaded.removeListener(it) } }
            preloadListener = null
            try {
                if (preloaded.playbackState == Player.STATE_IDLE) preloaded.prepare()
                if (TransitionTiming.needsSeek(preloaded.currentPosition, entryMs)) {
                    Log.d(TAG, "Precarga en ${preloaded.currentPosition}ms; seek a ${entryMs}ms")
                    preloaded.seekTo(entryMs)
                }
                preloaded.volume = 0f
                Log.d(TAG, "✅ Usando player precargado para ${nextSong.title}")
                return preloaded
            } catch (e: Exception) {
                Log.w(TAG, "La precarga no es utilizable; se crea un player nuevo", e)
            }
        }
        releasePreload()
        Log.d(TAG, "⚠️ Sin precarga válida para ${nextSong.title}; creando player de un item")
        return createTransitionPlayer().apply {
            setMediaItem(mediaItemFactory(nextSong), entryMs)
            volume = 0f
            prepare()
        }
    }

    /**
     * Builds a filtered list of MediaItems for ExoPlayer. Each MediaItem has the song's ID set
     * as its mediaId so callers can look up the correct song after playback events.
     */
    fun buildValidMediaItems(songs: List<Song>): List<Pair<Int, MediaItem>> {
        return songs.mapIndexed { index, song ->
            index to mediaItemFactory(song)
        }
    }

    // =================================================================================================
    // ===                         CURVAS Y EQ DE PLAYMIX (ESTILO SPOTIFY)                           ===
    // =================================================================================================

    /**
     * Computes (oldVol, newVol) for crossfade mixMode using fadeCurveType/bandFadeTypes.
     * Follows the per-band approach: bass, mid, treble can each have different curves.
     * The volume output represents the "average" weighting across bands.
     * (Full per-band EQ is a future enhancement; for now we blend the curves.)
     */
    private fun computePlaymixVolumes(progress: Float, transition: PlaymixTransitionDTO): Pair<Float, Float> {
        val bandFade = transition.bandFadeTypes
        val bassCurve = applyFadeCurve(progress, bandFade?.bass ?: transition.fadeCurveType)
        val midCurve  = applyFadeCurve(progress, bandFade?.mid  ?: transition.fadeCurveType)
        val trebleCurve = applyFadeCurve(progress, bandFade?.treble ?: transition.fadeCurveType)
        val avgCurve = (bassCurve + midCurve + trebleCurve) / 3f

        // Equal-power fade using averaged per-band curve
        val oldVol = sqrt((1f - avgCurve).coerceIn(0f, 1f))
        val newVol = sqrt(avgCurve.coerceIn(0f, 1f))
        return Pair(oldVol, newVol)
    }

    /**
     * Applies a named fade curve to raw progress ∈ [0,1] → curve output ∈ [0,1].
     *  - linear: unchanged
     *  - logarithmic (Early): fast start, slow end
     *  - exponential (Late): slow start, fast end
     *  - hold: 0 until 80%, then snap to 1
     *  - cut: snap at 50%
     */
    private fun applyFadeCurve(p: Float, curveType: String?): Float = when (curveType?.lowercase()) {
        "logarithmic" -> p.pow(0.4f)      // Early: rushes to 1 quickly
        "exponential" -> p.pow(2.5f)      // Late: stays low then rises sharply
        "scurve", "s_curve" -> (3f * p.pow(2f)) - (2f * p.pow(3f))
        "constantpower", "constant_power" -> (1f - cos(p * Math.PI.toFloat())) / 2f
        "hold"        -> if (p < 0.8f) 0f else (p - 0.8f) / 0.2f
        "cut"         -> if (p < 0.5f) 0f else 1f
        else          -> p                // linear (default)
    }

    /**
     * Applies the editor's bass/mid/treble fade curves to the actual EQ.
     * Volume still uses equal-power blending; this shapes the spectrum so DJ-style
     * transitions can swap lows, mids or highs independently.
     */
    private fun applyCrossfadeBandEq(
        oldEq: Equalizer?,
        newEq: Equalizer?,
        progress: Float,
        transition: PlaymixTransitionDTO
    ) {
        if (oldEq == null || newEq == null) return
        val bandFade = transition.bandFadeTypes
        val bass = applyFadeCurve(progress, bandFade?.bass ?: transition.fadeCurveType)
        val mid = applyFadeCurve(progress, bandFade?.mid ?: transition.fadeCurveType)
        val treble = applyFadeCurve(progress, bandFade?.treble ?: transition.fadeCurveType)

        setSafeBandLevel(oldEq, 0, EQ_MAX_ATTENUATION * bass)
        setSafeBandLevel(oldEq, 1, EQ_MEDIUM_ATTENUATION * bass)
        setSafeBandLevel(oldEq, 2, EQ_MEDIUM_ATTENUATION * mid)
        setSafeBandLevel(oldEq, 3, EQ_LIGHT_ATTENUATION * treble)
        setSafeBandLevel(oldEq, 4, EQ_MEDIUM_ATTENUATION * treble)

        setSafeBandLevel(newEq, 0, EQ_MAX_ATTENUATION * (1f - bass))
        setSafeBandLevel(newEq, 1, EQ_MEDIUM_ATTENUATION * (1f - bass))
        setSafeBandLevel(newEq, 2, EQ_MEDIUM_ATTENUATION * (1f - mid))
        setSafeBandLevel(newEq, 3, EQ_LIGHT_ATTENUATION * (1f - treble))
        setSafeBandLevel(newEq, 4, EQ_MEDIUM_ATTENUATION * (1f - treble))
    }

    private fun setSafeBandLevel(eq: Equalizer, bandIndex: Int, rawLevel: Float) {
        try {
            if (bandIndex >= eq.numberOfBands) return
            val range = eq.bandLevelRange
            val minLevel = range.getOrNull(0) ?: EQ_MAX_ATTENUATION
            val maxLevel = range.getOrNull(1) ?: 0
            val level = rawLevel.toInt().coerceIn(minLevel.toInt(), maxLevel.toInt()).toShort()
            eq.setBandLevel(bandIndex.toShort(), level)
        } catch (e: Exception) {
            Log.w("PlaymixEngine", "Unable to set EQ band $bandIndex", e)
        }
    }

    /** Freq Split: bass of old song fades, treble of new song fades in */
    private fun applyFreqSplitEq(oldEq: Equalizer?, newEq: Equalizer?, progress: Float) {
        if (oldEq == null || newEq == null) return
        val bands = shortArrayOf(0, 1, 2, 3, 4)
        val bassProgress = (progress / 0.5f).coerceIn(0f, 1f)
        val trebleProgress = ((progress - 0.3f) / 0.7f).coerceIn(0f, 1f)
        // Bass of old fades out
        setSafeBandLevel(oldEq, (bands[0]).toInt(), (EQ_MAX_ATTENUATION * bassProgress))
        setSafeBandLevel(oldEq, (bands[1]).toInt(), (EQ_MAX_ATTENUATION * bassProgress * 0.7f))
        // Treble of old stays longer
        setSafeBandLevel(oldEq, (bands[3]).toInt(), (EQ_MAX_ATTENUATION * trebleProgress))
        setSafeBandLevel(oldEq, (bands[4]).toInt(), (EQ_MAX_ATTENUATION * trebleProgress))
        // New song: start with only treble, bass fades in later
        setSafeBandLevel(newEq, (bands[0]).toInt(), (EQ_MAX_ATTENUATION * (1f - bassProgress)))
        setSafeBandLevel(newEq, (bands[1]).toInt(), (EQ_MAX_ATTENUATION * (1f - bassProgress) * 0.5f))
        setSafeBandLevel(newEq, (bands[3]).toInt(), (EQ_MAX_ATTENUATION * (1f - trebleProgress)))
        setSafeBandLevel(newEq, (bands[4]).toInt(), (EQ_MAX_ATTENUATION * (1f - trebleProgress)))
    }

    /** Club Drop: bass of old drops hard at midpoint, new enters from top frequencies down */
    private fun applyClubDropEq(oldEq: Equalizer?, newEq: Equalizer?, progress: Float) {
        if (oldEq == null || newEq == null) return
        val bands = shortArrayOf(0, 1, 2, 3, 4)
        // Old: bass drops very fast (like a DJ "low cut" filter closing)
        val oldBassProgress = (progress / 0.4f).coerceIn(0f, 1f)
        setSafeBandLevel(oldEq, (bands[0]).toInt(), (EQ_MAX_ATTENUATION * oldBassProgress))
        setSafeBandLevel(oldEq, (bands[1]).toInt(), (EQ_MAX_ATTENUATION * oldBassProgress * 0.8f))
        setSafeBandLevel(oldEq, (bands[2]).toInt(), (EQ_MAX_ATTENUATION * (progress / 0.7f).coerceIn(0f, 1f) * 0.5f))
        // New: enters top-first (treble → mid → bass)
        val newTrebleProgress = progress.pow(0.5f)
        val newMidProgress = (progress / 0.6f).coerceIn(0f, 1f).pow(1.5f)
        val newBassProgress = (progress / 0.8f - 0.25f).coerceIn(0f, 1f)
        setSafeBandLevel(newEq, (bands[4]).toInt(), (EQ_MAX_ATTENUATION * (1f - newTrebleProgress)))
        setSafeBandLevel(newEq, (bands[3]).toInt(), (EQ_MAX_ATTENUATION * (1f - newTrebleProgress * 0.9f)))
        setSafeBandLevel(newEq, (bands[2]).toInt(), (EQ_MAX_ATTENUATION * (1f - newMidProgress)))
        setSafeBandLevel(newEq, (bands[1]).toInt(), (EQ_MAX_ATTENUATION * (1f - newBassProgress)))
        setSafeBandLevel(newEq, (bands[0]).toInt(), (EQ_MAX_ATTENUATION * (1f - newBassProgress)))
    }

    // =================================================================================================
    // ===                       EQ TEMPORAL DE LA TRANSICIÓN (DOS PLAYERS)                          ===
    // =================================================================================================

    @OptIn(UnstableApi::class)
    private fun initializeIntelligentEQ(oldPlayer: ExoPlayer, newPlayer: ExoPlayer) {
        // Con el motor único todos los players comparten sesión: un Equalizer afectaría a toda la salida.
        if (mixEngine != null) return
        try {
            oldPlayer.audioSessionId.takeIf { it != 0 }?.let { sessionId ->
                oldPlayerEq = Equalizer(0, sessionId).apply {
                    enabled = true // ✅ ¡CORRECTO!
                    for (i in 0 until numberOfBands) {
                        setSafeBandLevel(this, (i.toShort()).toInt(), (0).toFloat())
                    }
                    Log.d("IntelligentCrossfade", "✅ OldPlayer EQ (ExoPlayer) inicializado en sesión $sessionId - Bandas: $numberOfBands")
                }
            } ?: Log.w("IntelligentCrossfade", "OldPlayer audio session ID no estaba lista para el EQ.")

            // Hacemos lo mismo para el nuevo reproductor.
            newPlayer.audioSessionId.takeIf { it != 0 }?.let { sessionId ->
                newPlayerEq = Equalizer(0, sessionId).apply {
                    enabled = true
                    for (i in 0 until numberOfBands) {
                        setSafeBandLevel(this, (i.toShort()).toInt(), (0).toFloat())
                    }
                    Log.d("IntelligentCrossfade", "✅ NewPlayer EQ (ExoPlayer) inicializado en sesión $sessionId - Bandas: $numberOfBands")
                }
            } ?: Log.w("IntelligentCrossfade", "NewPlayer audio session ID no estaba lista para el EQ.")

        } catch (e: Exception) {
            // [MANTENIDO] El bloque catch es perfecto para manejar dispositivos sin soporte de EQ.
            Log.e("IntelligentCrossfade", "⚠️ El dispositivo no soporta Equalizer o la sesión de audio no está lista.", e)
            oldPlayerEq = null
            newPlayerEq = null
        }
    }

    /**
     * Releases both active transition resources and silent preloads. Unlike
     * [cancelCurrentTransition], this is safe to call during service teardown.
     */
    fun release() {
        active?.let { detachTransition(it) }
        planned?.let { detachPlanned(it) }
        engineRun?.let { detachEngineRun(it) }
        rendered?.let { run ->
            detachRendered(run)
            runCatching { run.clipPlayer.stop(); run.clipPlayer.release() }
        }
        mainHandler.removeCallbacksAndMessages(null)
        isTransitioning = false
        transitionProgress = 0f

        runCatching {
            nextPlayer?.stop()
            nextPlayer?.release()
        }.onFailure {
            Log.w(TAG, "No se pudo liberar el player de transición", it)
        }
        nextPlayer = null
        preloadedSongId = null
        preloadListener = null

        runCatching {
            oldPlayerEq?.enabled = false
            oldPlayerEq?.release()
            newPlayerEq?.enabled = false
            newPlayerEq?.release()
        }.onFailure {
            Log.w(TAG, "No se pudieron liberar los ecualizadores", it)
        }
        oldPlayerEq = null
        newPlayerEq = null
        currentPlayer = null
        currentSong = null
    }

}
