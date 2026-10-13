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
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.example.resonant.playback.IntelligentEffectsState
import com.example.resonant.playback.MixStrategy
import com.example.resonant.playback.PlaybackUrlResolver
import com.example.resonant.playback.TransitionTiming
import com.example.resonant.data.models.AudioAnalysis
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.PlaymixTransitionDTO
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
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
 */
class TransitionManager(
    private val context: Context,
    private val mediaSourceFactoryProvider: () -> MediaSource.Factory,
    private val mediaItemFactory: (Song) -> MediaItem,
    private val onTransitionComplete: (newPlayer: ExoPlayer, oldPlayer: ExoPlayer) -> Unit,
    private val onTransitionFailed: (oldPlayer: ExoPlayer, oldSong: Song?, nextSong: Song?, wasAudible: Boolean) -> Unit,
    private val onTransitionAudible: ((nextSong: Song, nextIndex: Int) -> Unit)? = null,
    private val onTransitionProgress: ((position: Long, duration: Long) -> Unit)? = null
) {

    private val musicAudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    companion object {
        private const val TAG = "TransitionManager"
        private const val BEATMATCH_BPM_TOLERANCE = 0.06
        private const val ANIMATION_FRAME_DELAY_MS = 16L
        private const val GAP_FADE_OUT_MS = 300L
        private const val EQ_MAX_ATTENUATION: Short = -1500
        private const val EQ_MEDIUM_ATTENUATION: Short = -800
        private const val EQ_LIGHT_ATTENUATION: Short = -400
    }

    private fun createTransitionPlayer(): ExoPlayer {
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactoryProvider())
            .build()
            .apply {
                setAudioAttributes(musicAudioAttributes, false)
                setWakeMode(C.WAKE_MODE_NETWORK)
            }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var isTransitioning: Boolean = false
        private set

    /** Player precargado (silencioso) o player de la transición en curso. */
    private var nextPlayer: ExoPlayer? = null
    private var preloadedSongId: String? = null
    private var preloadListener: Player.Listener? = null

    private var oldPlayerEq: Equalizer? = null
    private var newPlayerEq: Equalizer? = null

    @Volatile
    var transitionProgress: Float = 0f
        private set
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

    fun startTransition(oldPlayer: ExoPlayer,
                        oldSong: Song,
                        nextSong: Song,
                        nextSongIndex: Int,
                        crossfadeMode: CrossfadeMode,
                        isAlbumMode: Boolean,
                        automixEnabled: Boolean,
                        crossfadeDurationMs: Long,
                        oldSongTargetVolume: Float = 1.0f,
                        nextSongTargetVolume: Float = 1.0f,
                        nextSongStartPositionMs: Long = 0L,
                        playmixTransition: PlaymixTransitionDTO? = null
    ) {
        Log.d(TAG, "🎯 startTransition - nextSong: ${nextSong.title}")

        if (playmixTransition != null) {
            startPlaymixTransition(oldPlayer, oldSong, nextSong, nextSongIndex, playmixTransition, oldSongTargetVolume, nextSongTargetVolume)
            return
        }

        val shouldTransition = when {
            crossfadeMode == CrossfadeMode.INTELLIGENT_EQ -> true
            isAlbumMode -> automixEnabled
            else -> crossfadeDurationMs > 0
        }
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

        when {
            isAlbumMode -> beginAlbumCut(oldPlayer, oldSong, nextSong, nextSongIndex)
            crossfadeMode == CrossfadeMode.INTELLIGENT_EQ -> beginIntelligentMix(oldPlayer, oldSong, nextSong, nextSongIndex)
            else -> beginSimpleCrossfade(oldPlayer, oldSong, nextSong, nextSongIndex, crossfadeMode, crossfadeDurationMs, nextSongStartPositionMs)
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

    private fun beginSimpleCrossfade(
        oldPlayer: ExoPlayer, oldSong: Song, nextSong: Song, nextSongIndex: Int,
        crossfadeMode: CrossfadeMode, crossfadeDurationMs: Long, nextSongStartPositionMs: Long
    ) {
        val entryMs = if (nextSongStartPositionMs > 0L) nextSongStartPositionMs else TransitionTiming.resolveEntryPointMs(
            TransitionTiming.EntryMode.SIMPLE, null, null, nextSong.audioAnalysis?.audioStartMs
        )
        val newPlayer = acquireTransitionPlayer(nextSong, entryMs)
        beginTransition(
            ActiveTransition(
                oldPlayer, oldSong, nextSong, nextSongIndex, newPlayer, entryMs,
                desiredFadeMs = crossfadeDurationMs, gapMs = 0L, label = "simple-${crossfadeMode.name}",
                onBeforePlayB = {},
                applyFrame = { progress ->
                    val (oldVol, newVol) = when (crossfadeMode) {
                        CrossfadeMode.DIRECT_CUT -> {
                            val smooth = 0.5f * (1 - cos(progress * Math.PI.toFloat()))
                            Pair(1 - smooth, smooth)
                        }
                        else -> Pair(sqrt(1 - progress), sqrt(progress)) // SOFT_MIX y fallback
                    }
                    oldPlayer.volume = oldVol * targetOldPlayerVolume
                    newPlayer.volume = newVol * targetNewPlayerVolume
                }
            )
        )
    }

    private fun beginIntelligentMix(oldPlayer: ExoPlayer, oldSong: Song, newSong: Song, nextSongIndex: Int) {
        val strategy = calculateAutomixStrategy(oldSong, newSong)
        val mixDuration = calculateFullyIntelligentDuration(oldSong, newSong, strategy)
        val optimalOutPoint = findOptimalOutPoint(oldSong.audioAnalysis)
        val entryMs = intelligentEntryPointMs(newSong).toLong()

        Log.i("IntelligentCrossfade", "🧠 Estrategia ${strategy.name}, duración ${mixDuration}ms, salida=${optimalOutPoint}ms, entrada=${entryMs}ms")

        val newPlayer = acquireTransitionPlayer(newSong, entryMs)
        val effectsState = IntelligentEffectsState()
        beginTransition(
            ActiveTransition(
                oldPlayer, oldSong, newSong, nextSongIndex, newPlayer, entryMs,
                desiredFadeMs = mixDuration, gapMs = 0L, label = "intelligent-${strategy.name}",
                onBeforePlayB = {
                    if (strategy == MixStrategy.BEATMATCH_MIX) {
                        oldSong.audioAnalysis?.let { oldA ->
                            newSong.audioAnalysis?.let { newA -> syncTempo(oldPlayer, oldA, newA, newPlayer) }
                        }
                    }
                    initializeIntelligentEQ(oldPlayer, newPlayer)
                },
                applyFrame = { progress ->
                    val (oldVol, newVol) = getEnhancedIntelligentVolumeCurve(progress, strategy)
                    applyVolumeSmoothly(oldPlayer, newPlayer, oldVol, newVol)
                    applyEnhancedIntelligentEq(oldPlayerEq, newPlayerEq, progress, strategy, oldSong, newSong)
                    applyIntelligentEffects(progress, strategy, effectsState)
                }
            )
        )
    }

    /** Punto de entrada del modo inteligente, ya acotado (design D9). */
    fun intelligentEntryPointMs(song: Song): Int =
        TransitionTiming.clampIntelligentEntry(findOptimalInPoint(song.audioAnalysis))

    fun cancelCurrentTransition() {
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

    /** Completa la transición ya, con B sonando a su volumen objetivo. */
    fun forceCompleteTransition() {
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
    // ===                           CROSSFADE INTELIGENTE (MOTOR DE DJ)                             ===
    // =================================================================================================

    private fun applyIntelligentEffects(progress: Float, strategy: MixStrategy, effectsState: IntelligentEffectsState) {
        when (strategy) {
            MixStrategy.BEATMATCH_MIX -> applyBeatmatchEffects(progress, effectsState)
            MixStrategy.HARMONIC_BLEND -> applyHarmonicEffects(progress, effectsState)
            MixStrategy.RHYTHMIC_FADE -> applyRhythmicEffects(progress, effectsState)
            MixStrategy.STEM_SWAP_MIX -> {
                if (progress >= 0.5f && !effectsState.bassSwapped) {
                    Log.i("IntelligentCrossfade", "👑 STEM SWAP - Fase de Bass Swap completada")
                    effectsState.bassSwapped = true
                }
            }
            else -> applyStandardEffects(progress, effectsState)
        }
    }

    @OptIn(UnstableApi::class)
    private fun initializeIntelligentEQ(oldPlayer: ExoPlayer, newPlayer: ExoPlayer) {
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

    private fun applyBeatmatchEffects(progress: Float, effectsState: IntelligentEffectsState) {
        // ✅ BASS SWAP MEJORADO
        if (progress >= 0.5f && !effectsState.bassSwapped) {
            Log.i("IntelligentCrossfade", "🎛️ BASS SWAP INTELIGENTE ACTIVADO!")
            effectsState.bassSwapped = true

            // Efecto adicional: pequeño ajuste de EQ para enfatizar el swap
            newPlayerEq?.let { eq ->
                val bands = shortArrayOf(0, 1)
                bands.forEach { band ->
                    val currentLevel = eq.getBandLevel(band)
                    val boostedLevel = (currentLevel * 1.2f).toInt().toShort().coerceAtMost(
                        EQ_MAX_ATTENUATION
                    )
                    setSafeBandLevel(eq, (band).toInt(), (boostedLevel).toFloat())
                }
            }
        }

        // ✅ EFECTO DE GROOVE SYNC
        if (progress >= 0.7f && !effectsState.rhythmicSync) {
            Log.i("IntelligentCrossfade", "🥁 GROOVE SYNC ACTIVADO!")
            effectsState.rhythmicSync = true
        }
    }

    private fun applyHarmonicEffects(progress: Float, effectsState: IntelligentEffectsState) {
        // ✅ TRANSICIÓN ARMÓNICA PROGRESIVA
        if (progress >= 0.3f && !effectsState.harmonicTransition) {
            Log.i("IntelligentCrossfade", "🎵 TRANSICIÓN ARMÓNICA INICIADA")
            effectsState.harmonicTransition = true
        }

        // ✅ EFECTO DE RESONANCIA ARMÓNICA
        if (progress >= 0.6f && !effectsState.rhythmicSync) {
            Log.i("IntelligentCrossfade", "🌈 RESONANCIA ARMÓNICA ACTIVADA")
            effectsState.rhythmicSync = true
        }
    }

    private fun applyRhythmicEffects(progress: Float, effectsState: IntelligentEffectsState) {
        // ✅ SINCRONIZACIÓN RÍTMICA
        if (progress >= 0.4f && !effectsState.rhythmicSync) {
            Log.i("IntelligentCrossfade", "🎯 SINCRONIZACIÓN RÍTMICA ACTIVADA")
            effectsState.rhythmicSync = true
        }
    }

    private fun getEnhancedIntelligentVolumeCurve(progress: Float, strategy: MixStrategy): Pair<Float, Float> {
        val p = progress.coerceIn(0f, 1f)

        return when (strategy) {
            MixStrategy.STEM_SWAP_MIX -> getStemSwapVolumeCurve(p)
            MixStrategy.BEATMATCH_MIX -> {
                // ✅ CURVA BEATMATCH MEJORADA - PRECISA PERO SUAVE
                val oldVol = when {
                    p < 0.3f -> 1f - (p / 0.3f).pow(1.8f) * 0.2f  // Bajada muy suave inicial
                    p < 0.7f -> 0.8f - ((p - 0.3f) / 0.4f).pow(1.2f) * 0.5f  // Bajada controlada
                    else -> 0.3f - ((p - 0.7f) / 0.3f).pow(0.8f) * 0.3f  // Bajada final suave
                }

                val newVol = when {
                    p < 0.2f -> (p / 0.2f).pow(2.2f) * 0.3f  // Entrada muy suave
                    p < 0.6f -> 0.3f + ((p - 0.2f) / 0.4f).pow(1.1f) * 0.5f  // Subida progresiva
                    else -> 0.8f + ((p - 0.6f) / 0.4f).pow(0.9f) * 0.2f  // Subida final
                }

                Pair(oldVol.coerceIn(0f, 1f), newVol.coerceIn(0f, 1f))
            }

            MixStrategy.HARMONIC_BLEND -> {
                // Curva de "potencia constante" (Equal Power Crossfade) para la máxima suavidad.
                // Garantiza que la energía total percibida se mantiene constante durante la mezcla.
                val smoothP = 0.5f * (1 - cos(p * Math.PI.toFloat())) // Curva sinusoidal para más suavidad
                val oldVol = sqrt(1.0f - smoothP)
                val newVol = sqrt(smoothP)
                Pair(oldVol, newVol)
            }

            MixStrategy.RHYTHMIC_FADE -> {
                // ✅ CURVA DE VOLUMEN MEJORADA PARA UNA MEZCLA MÁS LARGA
                // La canción que se va se mantiene casi al máximo volumen durante la primera
                // mitad de la mezcla y luego cae bruscamente al final.
                val oldVol = 1f - p.pow(3f)

                // La canción que entra sube de forma más constante desde el principio,
                // asegurando que ambas conviven a buen volumen.
                val newVol = p.pow(0.6f)

                Pair(oldVol.coerceIn(0f, 1f), newVol.coerceIn(0f, 1f))
            }

            else -> {
                // ✅ CURVA ESTÁNDAR MEJORADA
                val oldVol = (1 - p).pow(1.4f)
                val newVol = p.pow(0.9f)
                Pair(oldVol, newVol)
            }
        }
    }

    private fun applyStandardEffects(progress: Float, effectsState: IntelligentEffectsState) {
        // ✅ EFECTOS ESTÁNDAR MINIMALISTAS
        if (progress >= 0.8f && !effectsState.finalFadeStarted) {
            Log.i("IntelligentCrossfade", "🔚 FASE FINAL DE TRANSICIÓN")
            effectsState.finalFadeStarted = true
        }
    }

    private fun applyEnhancedIntelligentEq(oldEq: Equalizer?, newEq: Equalizer?, progress: Float, strategy: MixStrategy, oldSong: Song?, newSong: Song?) {
        if (oldEq == null || newEq == null) return

        val bands = shortArrayOf(0, 1, 2, 3, 4)

        // ✅ CORRECCIÓN: Estrategias con lógica de EQ por fases (Stem Swap, Rhythmic Fade)
        // deben ejecutarse desde el principio (progress 0.0) y usar el 'progress' principal,
        // no el 'musicalCurve' derivado.

        if (strategy == MixStrategy.STEM_SWAP_MIX) {
            applyStemSwapEq(oldEq, newEq, progress, bands)
            return // Salir
        }

        // 🔥 ESTA ES LA LÍNEA CLAVE AÑADIDA
        if (strategy == MixStrategy.RHYTHMIC_FADE) {
            applyEnhancedRhythmicEq(oldEq, newEq, progress, bands, oldSong, newSong)
            return // Salir
        }

        // --- Lógica de EQ estándar (para BEATMATCH, HARMONIC, STANDARD) ---

        // ✅ INICIO DINÁMICO BASADO EN ESTRATEGIA Y PROGRESO
        val eqStartPoint = calculateDynamicEqStartPoint(strategy, progress)

        if (progress < eqStartPoint) {
            // ✅ ¡AQUÍ ESTÁ LA CORRECCIÓN!
            // La canción antigua se mantiene plana.
            resetEqToFlat(oldEq, bands)
            // La canción nueva se mantiene CORTADA (en -1500) hasta que le toque entrar.
            resetEqToCut(newEq, bands)
            return
        }

        // ✅ CURVA MEJORADA CON CRITERIOS MUSICALES
        val eqProgress = ((progress - eqStartPoint) / (1 - eqStartPoint)).coerceIn(0f, 1f)
        val musicalCurve = calculateMusicalCurve(eqProgress, strategy, oldSong, newSong)

        // ✅ APLICAR ESTRATEGIA ESPECÍFICA CON CURVA MUSICAL
        when (strategy) {
            MixStrategy.BEATMATCH_MIX -> applyEnhancedBeatmatchEq(oldEq, newEq, musicalCurve, bands, oldSong, newSong)
            MixStrategy.HARMONIC_BLEND -> applyEnhancedHarmonicEq(oldEq, newEq, musicalCurve, bands, oldSong, newSong)
            // RHYTHMIC_FADE y STEM_SWAP ya se han manejado arriba
            else -> applyEnhancedStandardEq(oldEq, newEq, musicalCurve, bands)
        }

        Log.d("ProfessionalEQ", "🎚️ ${strategy.name} - Musical Curve: ${"%.2f".format(musicalCurve)}")
    }

    private fun applyEnhancedBeatmatchEq(oldEq: Equalizer, newEq: Equalizer, progress: Float, bands: ShortArray, oldSong: Song?, newSong: Song?, maxAttenuation: Short = EQ_MEDIUM_ATTENUATION) {
        // Calcula el progreso para cada rango de frecuencia
        val bassProgress = (progress / 0.5f).coerceIn(0f, 1f)
        val midProgress = ((progress - 0.3f) / 0.5f).coerceIn(0f, 1f)
        val highProgress = ((progress - 0.6f) / 0.4f).coerceIn(0f, 1f)

        // Aplica el EQ a cada banda individualmente
        // BASS (Banda 0)
        setSafeBandLevel(oldEq, (bands[0]).toInt(), (maxAttenuation * bassProgress))
        setSafeBandLevel(newEq, (bands[0]).toInt(), (maxAttenuation * (1 - bassProgress)))

        // MIDS (Bandas 1, 2, 3)
        for (i in 1..3) {
            setSafeBandLevel(oldEq, (bands[i]).toInt(), (maxAttenuation * midProgress))
            setSafeBandLevel(newEq, (bands[i]).toInt(), (maxAttenuation * (1 - midProgress)))
        }

        // HIGHS (Banda 4)
        setSafeBandLevel(oldEq, (bands[4]).toInt(), (maxAttenuation * highProgress))
        setSafeBandLevel(newEq, (bands[4]).toInt(), (maxAttenuation * (1 - highProgress)))
    }

    private fun applyEnhancedHarmonicEq(oldEq: Equalizer, newEq: Equalizer, progress: Float, bands: ShortArray, oldSong: Song?, newSong: Song?) {
        // Usamos una atenuación muy ligera, el objetivo es esculpir, no cortar.
        val maxAttenuation = EQ_LIGHT_ATTENUATION// -400, muy sutil

        // Curva de progreso extra suave, ideal para disolver sonidos.
        val curve = 0.5f * (1 - cos(progress * Math.PI.toFloat()))

        // --- Lógica para la canción que SALE (oldEq) ---
        // El objetivo es "ahuecar" suavemente el centro para hacer sitio a la nueva canción.
        val midScoop = (maxAttenuation * curve * 0.8f).toInt().toShort()      // Un "valle" más pronunciado en los medios
        val highReduction = (maxAttenuation * curve * 0.4f).toInt().toShort() // Reducción ligera de agudos para quitar "brillo"

        // Bajos casi intactos para mantener el "cuerpo" de la canción que se va.
        setSafeBandLevel(oldEq, (bands[0]).toInt(), (maxAttenuation * curve * 0.1f))
        setSafeBandLevel(oldEq, (bands[1]).toInt(), (midScoop).toFloat())
        setSafeBandLevel(oldEq, (bands[2]).toInt(), (midScoop).toFloat())
        setSafeBandLevel(oldEq, (bands[3]).toInt(), (highReduction).toFloat())
        setSafeBandLevel(oldEq, (bands[4]).toInt(), (highReduction).toFloat())

        // --- Lógica para la canción que ENTRA (newEq) ---
        // Empieza con un sonido "filtrado" y termina en plano (natural).
        val initialMidScoop = (maxAttenuation * (1 - curve) * 0.8f).toInt().toShort()
        val initialHighReduction = (maxAttenuation * (1 - curve) * 0.4f).toInt().toShort()

        setSafeBandLevel(newEq, (bands[0]).toInt(), (maxAttenuation * (1 - curve) * 0.1f))
        setSafeBandLevel(newEq, (bands[1]).toInt(), (initialMidScoop).toFloat())
        setSafeBandLevel(newEq, (bands[2]).toInt(), (initialMidScoop).toFloat())
        setSafeBandLevel(newEq, (bands[3]).toInt(), (initialHighReduction).toFloat())
        setSafeBandLevel(newEq, (bands[4]).toInt(), (initialHighReduction).toFloat())
    }

    private fun applyEnhancedRhythmicEq(oldEq: Equalizer, newEq: Equalizer, progress: Float, bands: ShortArray, oldSong: Song?, newSong: Song?) {
        // Definimos las bandas: graves (el beat), medios (voces, melodías) y agudos.
        val bassBand = bands[0]
        val midBands = bands.sliceArray(1..3)
        val highBands = bands.sliceArray(4..4) // O la última banda que tengas

        // FASE 1: Introducir la nueva canción sin su bajo (progreso 0% -> 50%)
        if (progress < 0.5f) {
            // Calculamos el progreso dentro de esta fase (de 0.0 a 1.0)
            val stageProgress = progress / 0.5f

            // Canción que SALE (oldEq):
            // Mantenemos su bajo intacto (plano) y empezamos a cortar suavemente los medios y agudos.
            setSafeBandLevel(oldEq, (bassBand).toInt(), (0).toFloat())
            midBands.forEach { band -> setSafeBandLevel(oldEq, (band).toInt(), (EQ_MEDIUM_ATTENUATION * stageProgress * 0.5f)) }
            highBands.forEach { band -> setSafeBandLevel(oldEq, (band).toInt(), (EQ_MEDIUM_ATTENUATION * stageProgress)) }

            // Canción que ENTRA (newEq):
            // Mantenemos su bajo COMPLETAMENTE CORTADO e introducimos sus medios y agudos.
            setSafeBandLevel(newEq, (bassBand).toInt(), (EQ_MAX_ATTENUATION).toFloat())
            midBands.forEach { band -> setSafeBandLevel(newEq, (band).toInt(), (EQ_MAX_ATTENUATION * (1 - stageProgress))) }
            highBands.forEach { band -> setSafeBandLevel(newEq, (band).toInt(), (EQ_MAX_ATTENUATION * (1 - stageProgress))) }

            // FASE 2: Intercambiar los bajos (Bass Swap) (progreso 50% -> 100%)
        } else {
            // Calculamos el progreso dentro de esta fase (de 0.0 a 1.0)
            val stageProgress = (progress - 0.5f) / 0.5f

            // Canción que SALE (oldEq):
            // Ahora sí, cortamos su bajo y terminamos de eliminar los medios/agudos.
            setSafeBandLevel(oldEq, (bassBand).toInt(), (EQ_MAX_ATTENUATION * stageProgress))
            midBands.forEach { band -> setSafeBandLevel(oldEq, (band).toInt(), ((EQ_MEDIUM_ATTENUATION * 0.5f) + (EQ_MEDIUM_ATTENUATION * 0.5f * stageProgress))) }
            highBands.forEach { band -> setSafeBandLevel(oldEq, (band).toInt(), (EQ_MEDIUM_ATTENUATION).toFloat()) } // Ya cortados

            // Canción que ENTRA (newEq):
            // Sus medios y agudos ya están presentes (planos). AHORA introducimos su bajo.
            setSafeBandLevel(newEq, (bassBand).toInt(), (EQ_MAX_ATTENUATION * (1 - stageProgress)))
            midBands.forEach { band -> setSafeBandLevel(newEq, (band).toInt(), (0).toFloat()) }
            highBands.forEach { band -> setSafeBandLevel(newEq, (band).toInt(), (0).toFloat()) }
        }
    }

    private fun applyEnhancedStandardEq(oldEq: Equalizer, newEq: Equalizer, progress: Float, bands: ShortArray) {
        val enhancedCurve = 0.5f * (1 - cos(progress * Math.PI.toFloat()))
        val superSmoothCurve = enhancedCurve.pow(0.8f)

        bands.forEach { band ->
            // ✅ CORRECCIÓN APLICADA
            setSafeBandLevel(oldEq, (band).toInt(), (EQ_MAX_ATTENUATION * superSmoothCurve))
            setSafeBandLevel(newEq, (band).toInt(), (EQ_MAX_ATTENUATION * (1 - superSmoothCurve)))
        }
    }

    private fun applyStemSwapEq(oldEq: Equalizer, newEq: Equalizer, progress: Float, bands: ShortArray) {
        val bassBands = shortArrayOf(0)
        val midBands = shortArrayOf(1, 2)
        val highBands = shortArrayOf(3, 4)

        val MAX_ATT = EQ_MAX_ATTENUATION
        val FLAT: Short = 0

        // Fase 1 (0% -> 25%): Introducir agudos de la nueva canción
        if (progress < 0.25f) {
            val stageProgress = (progress / 0.25f)

            // oldEq: Sigue sonando normal
            bassBands.forEach { setSafeBandLevel(oldEq, (it).toInt(), (FLAT).toFloat()) }
            midBands.forEach { setSafeBandLevel(oldEq, (it).toInt(), (FLAT).toFloat()) }
            highBands.forEach { setSafeBandLevel(oldEq, (it).toInt(), (FLAT).toFloat()) }

            // newEq: Entra solo con agudos (Graves y Medios cortados)
            bassBands.forEach { setSafeBandLevel(newEq, (it).toInt(), (MAX_ATT).toFloat()) }
            midBands.forEach { setSafeBandLevel(newEq, (it).toInt(), (MAX_ATT).toFloat()) }
            highBands.forEach { band ->
                setSafeBandLevel(newEq, (band).toInt(), (MAX_ATT * (1 - stageProgress)))
            }
        }
        // Fase 2 (25% -> 50%): Intercambiar los bajos (Bass Swap)
        else if (progress < 0.5f) {
            val stageProgress = ((progress - 0.25f) / 0.25f)

            // oldEq: Empieza a perder sus bajos
            bassBands.forEach { band ->
                setSafeBandLevel(oldEq, (band).toInt(), (MAX_ATT * stageProgress))
            }
            midBands.forEach { setSafeBandLevel(oldEq, (it).toInt(), (FLAT).toFloat()) }
            highBands.forEach { setSafeBandLevel(oldEq, (it).toInt(), (FLAT).toFloat()) }

            // newEq: Introduce sus bajos, mantiene agudos, medios siguen cortados
            bassBands.forEach { band ->
                setSafeBandLevel(newEq, (band).toInt(), (MAX_ATT * (1 - stageProgress)))
            }
            midBands.forEach { setSafeBandLevel(newEq, (it).toInt(), (MAX_ATT).toFloat()) }
            highBands.forEach { setSafeBandLevel(newEq, (it).toInt(), (FLAT).toFloat()) } // Agudos ya entraron
        }
        // Fase 3 (50% -> 75%): Intercambiar los medios (Mid Swap)
        else if (progress < 0.75f) {
            val stageProgress = ((progress - 0.5f) / 0.25f)

            // oldEq: Bajos cortados, empieza a perder medios
            bassBands.forEach { setSafeBandLevel(oldEq, (it).toInt(), (MAX_ATT).toFloat()) }
            midBands.forEach { band ->
                setSafeBandLevel(oldEq, (band).toInt(), (MAX_ATT * stageProgress))
            }
            highBands.forEach { setSafeBandLevel(oldEq, (it).toInt(), (FLAT).toFloat()) }

            // newEq: Bajos y agudos presentes, introduce medios
            bassBands.forEach { setSafeBandLevel(newEq, (it).toInt(), (FLAT).toFloat()) }
            midBands.forEach { band ->
                setSafeBandLevel(newEq, (band).toInt(), (MAX_ATT * (1 - stageProgress)))
            }
            highBands.forEach { setSafeBandLevel(newEq, (it).toInt(), (FLAT).toFloat()) }
        }
        // Fase 4 (75% -> 100%): Limpiar agudos de la canción antigua
        else {
            val stageProgress = ((progress - 0.75f) / 0.25f)

            // oldEq: Bajos y medios cortados, pierde agudos
            bassBands.forEach { setSafeBandLevel(oldEq, (it).toInt(), (MAX_ATT).toFloat()) }
            midBands.forEach { setSafeBandLevel(oldEq, (it).toInt(), (MAX_ATT).toFloat()) }
            highBands.forEach { band ->
                setSafeBandLevel(oldEq, (band).toInt(), (MAX_ATT * stageProgress))
            }

            // newEq: Todo presente (plano)
            bassBands.forEach { setSafeBandLevel(newEq, (it).toInt(), (FLAT).toFloat()) }
            midBands.forEach { setSafeBandLevel(newEq, (it).toInt(), (FLAT).toFloat()) }
            highBands.forEach { setSafeBandLevel(newEq, (it).toInt(), (FLAT).toFloat()) }
        }
    }

    private fun getStemSwapVolumeCurve(progress: Float): Pair<Float, Float> {
        val p = progress.coerceIn(0f, 1f)

        // La canción antigua se mantiene fuerte durante más tiempo para que su melodía se escuche sobre el nuevo beat
        val oldVol = 1.0f - p.pow(3) // Cae muy bruscamente al final

        // La nueva canción entra de forma más controlada y gradual
        val newVol = p.pow(0.8f)

        return Pair(oldVol.coerceIn(0f, 1f), newVol.coerceIn(0f, 1f))
    }

    private fun calculateDynamicEqStartPoint(strategy: MixStrategy, progress: Float): Float {
        // ✅ CORRECCIÓN: El punto de inicio del EQ ahora es un valor FIJO por estrategia.
        // Ya no depende de 'progress', eliminando el efecto "on-off-on".
        return when (strategy) {
            // Las mezclas armónicas deben empezar su EQ muy sutilmente y muy pronto.
            MixStrategy.HARMONIC_BLEND -> 0.10f // Empieza al 10% de la mezcla

            // Las mezclas por beat pueden esperar un poco más para que el beat se asiente.
            MixStrategy.BEATMATCH_MIX -> 0.20f // Empieza al 20%

            // Stem Swap tiene su propia lógica interna, este valor no le afecta.
            MixStrategy.STEM_SWAP_MIX -> 0.0f

            // El resto empieza un poco antes de la mitad.
            else -> 0.25f
        }
    }

    private fun calculateMusicalCurve(progress: Float, strategy: MixStrategy, oldSong: Song?, newSong: Song? ): Float {
        val baseCurve = 0.5f * (1 - cos(progress * Math.PI.toFloat()))

        // ✅ MODIFICAR CURVA SEGÚN CRITERIOS MUSICALES
        return when (strategy) {
            MixStrategy.BEATMATCH_MIX -> {
                val bpmFactor = calculateBpmFactor(oldSong, newSong)
                baseCurve.pow(0.8f + bpmFactor * 0.2f)
            }
            MixStrategy.HARMONIC_BLEND -> {
                val harmonicFactor = calculateHarmonicFactor(oldSong, newSong)
                baseCurve.pow(0.7f + harmonicFactor * 0.3f)
            }
            MixStrategy.RHYTHMIC_FADE -> {
                val rhythmicFactor = calculateRhythmicFactor(oldSong, newSong)
                baseCurve.pow(0.9f + rhythmicFactor * 0.1f)
            }
            else -> baseCurve.pow(0.85f)
        }
    }

    private fun calculateBpmFactor(oldSong: Song?, newSong: Song?): Float {
        val oldBpm = oldSong?.audioAnalysis?.bpmNormalized ?: 120.0
        val newBpm = newSong?.audioAnalysis?.bpmNormalized ?: 120.0

        // ✅ Comprobación de robustez
        if (oldBpm <= 0 || newBpm <= 0) return 0.5f // Devuelve un valor neutro

        val bpmDiff = abs(oldBpm - newBpm)
        return when {
            bpmDiff < 5 -> 1.0f
            bpmDiff < 15 -> 0.7f
            bpmDiff < 30 -> 0.4f
            else -> 0.2f
        }
    }

    private fun calculateHarmonicFactor(oldSong: Song?, newSong: Song?): Float {
        val oldKey = oldSong?.audioAnalysis?.musicalKey
        val newKey = newSong?.audioAnalysis?.musicalKey
        return calculateHarmonicCompatibility(oldKey, newKey)
    }

    private fun calculateRhythmicFactor(oldSong: Song?, newSong: Song?): Float {
        val oldBpm = oldSong?.audioAnalysis?.bpmNormalized ?: 120.0
        val newBpm = newSong?.audioAnalysis?.bpmNormalized ?: 120.0
        return calculateRhythmicCompatibility(oldBpm, newBpm)
    }

    private fun applyVolumeSmoothly(oldPlayer: ExoPlayer, newPlayer: ExoPlayer, oldVol: Float, newVol: Float) {
        try {
            // Utilizamos el volumen normalizado absoluto objetivo para multiplicar la curva suavizada
            val smoothedOldVol = smoothVolumeCurve(oldVol) * targetOldPlayerVolume
            val smoothedNewVol = smoothVolumeCurve(newVol) * targetNewPlayerVolume

            // [CAMBIO CLAVE] Usamos la propiedad `.volume` de ExoPlayer, que es más simple.
            oldPlayer.volume = smoothedOldVol
            newPlayer.volume = smoothedNewVol

            // [MANTENIDO] Tu log de depuración es útil y se queda.
            if ((oldVol * 10).toInt() % 2 == 0) {
                Log.d("IntelligentCrossfade", "🔊 Volumen - Old: ${"%.2f".format(smoothedOldVol)}, New: ${"%.2f".format(smoothedNewVol)}")
            }
        } catch (e: Exception) {
            // [MANTENIDO] El manejo de errores y el re-lanzamiento son cruciales para que
            // el bucle de animación principal se detenga y llame a `handleMixFailure`.
            Log.e("IntelligentCrossfade", "❌ Error al ajustar volumen de ExoPlayer", e)
            throw e // Re-lanzar para manejo superior
        }
    }

    private fun smoothVolumeCurve(volume: Float): Float {
        // Aplicar curva de ease-in-out adicional para mayor suavidad
        return if (volume <= 0.5f) {
            2 * volume * volume
        } else {
            1 - 2 * (1 - volume) * (1 - volume)
        }
    }

    private fun calculateFullyIntelligentDuration(oldSong: Song, newSong: Song, strategy: MixStrategy): Long {
        val oldAnalysis = oldSong.audioAnalysis
        val newAnalysis = newSong.audioAnalysis

        if (oldAnalysis == null || newAnalysis == null) {
            Log.w("IntelligentCrossfade", "⚠️ Sin análisis, usando duración inteligente predeterminada")
            return getDefaultDurationByStrategy(strategy)
        }

        val duration = when (strategy) {
            MixStrategy.BEATMATCH_MIX -> calculateBeatmatchDurationAdvanced(oldAnalysis, newAnalysis)
            MixStrategy.HARMONIC_BLEND -> calculateHarmonicDuration(oldAnalysis, newAnalysis)
            MixStrategy.RHYTHMIC_FADE -> calculateRhythmicDuration(oldAnalysis, newAnalysis)
            else -> calculateStandardDuration(oldAnalysis, newAnalysis)
        }

        // ✅ LOG DETALLADO
        logDurationCalculation(strategy, oldSong, newSong, duration)

        return duration
    }

    private fun calculateStandardDuration(oldAnalysis: AudioAnalysis, newAnalysis: AudioAnalysis): Long {
        Log.d("IntelligentCrossfade", "📏 Calculando duración estándar inteligente")

        val oldBpm = oldAnalysis.bpmNormalized
        val newBpm = newAnalysis.bpmNormalized
        val oldKey = oldAnalysis.musicalKey
        val newKey = newAnalysis.musicalKey

        // ✅ FACTOR DE COMPATIBILIDAD GENERAL
        val bpmCompatibility = calculateRhythmicCompatibility(oldBpm, newBpm)
        val harmonicCompatibility = calculateHarmonicCompatibility(oldKey, newKey)
        val overallCompatibility = (bpmCompatibility + harmonicCompatibility) / 2.0f

        Log.d("IntelligentCrossfade", "🎵 Compatibilidad - BPM: ${"%.2f".format(bpmCompatibility)}, Armónica: ${"%.2f".format(harmonicCompatibility)}, General: ${"%.2f".format(overallCompatibility)}")

        // ✅ DURACIÓN BASADA EN COMPATIBILIDAD
        val baseDuration = when {
            overallCompatibility >= 0.8 -> 10000L  // Alta compatibilidad - mezcla más larga
            overallCompatibility >= 0.6 -> 8000L   // Compatibilidad media - duración media
            overallCompatibility >= 0.4 -> 6000L   // Compatibilidad baja - mezcla más corta
            else -> 5000L                          // Muy baja compatibilidad - mezcla mínima
        }

        // ✅ AJUSTE POR BPM (canciones rápidas necesitan más tiempo)
        val bpmAdjustment = when {
            oldBpm > 140 || newBpm > 140 -> 1.3f   // Música rápida - +30%
            oldBpm > 120 || newBpm > 120 -> 1.15f  // Música media-rápida - +15%
            oldBpm < 80 || newBpm < 80 -> 0.9f     // Música lenta - -10%
            else -> 1.0f                           // Sin ajuste
        }

        // ✅ AJUSTE POR DURACIÓN DE LAS CANCIONES
        val oldDuration = oldAnalysis.durationMs
        val newDuration = newAnalysis.durationMs
        val avgDuration = (oldDuration + newDuration) / 2.0

        val durationAdjustment = when {
            avgDuration > 300000 -> 1.2f   // Canciones largas (>5min) - +20%
            avgDuration > 240000 -> 1.1f   // Canciones medias-largas (>4min) - +10%
            avgDuration < 120000 -> 0.8f   // Canciones cortas (<2min) - -20%
            else -> 1.0f                   // Sin ajuste
        }

        val calculatedDuration = (baseDuration * bpmAdjustment * durationAdjustment).toLong()
        val finalDuration = calculatedDuration.coerceIn(4000L, 15000L)

        Log.d("IntelligentCrossfade", "⏱️ Duración estándar calculada: ${finalDuration}ms (Base: ${baseDuration}ms, BPM x${"%.2f".format(bpmAdjustment)}, Duración x${"%.2f".format(durationAdjustment)})")

        return finalDuration
    }

    private fun calculateRhythmicDuration(oldAnalysis: AudioAnalysis, newAnalysis: AudioAnalysis): Long {
        Log.d("IntelligentCrossfade", "🥁 Calculando duración rítmica inteligente")

        val oldBpm = oldAnalysis.bpmNormalized
        val newBpm = newAnalysis.bpmNormalized
        val bpmDiff = abs(oldBpm - newBpm)
        val avgBpm = (oldBpm + newBpm) / 2.0

        // ✅ COMPATIBILIDAD RÍTMICA DETALLADA
        val rhythmicCompatibility = calculateRhythmicCompatibility(oldBpm, newBpm)

        Log.d("IntelligentCrossfade", "🎵 Análisis rítmico - BPM: $oldBpm->$newBpm, Diff: ${"%.1f".format(bpmDiff)}, Compat: ${"%.2f".format(rhythmicCompatibility)}")

        // ✅ DURACIÓN BASE SEGÚN COMPATIBILIDAD RÍTMICA
        val baseDuration = when {
            rhythmicCompatibility >= 0.9 -> 9000L   // Rítmicamente idénticas
            rhythmicCompatibility >= 0.8 -> 8000L   // Muy similares
            rhythmicCompatibility >= 0.7 -> 7000L   // Similares
            rhythmicCompatibility >= 0.6 -> 6000L   // Moderadamente similares
            else -> 5000L                           // Poco similares
        }

        // ✅ AJUSTE POR DIFERENCIA DE BPM ESPECÍFICA
        val bpmDiffAdjustment = when {
            bpmDiff < 2 -> 1.3f    // BPM casi idénticos - mezcla más larga
            bpmDiff < 5 -> 1.1f    // BPM muy similares - mezcla ligeramente más larga
            bpmDiff < 10 -> 1.0f   // BPM similares - duración estándar
            bpmDiff < 20 -> 0.9f   // BPM diferentes - mezcla más corta
            else -> 0.8f           // BPM muy diferentes - mezcla mínima
        }

        // ✅ AJUSTE POR VELOCIDAD ABSOLUTA (BPM promedio)
        val speedAdjustment = when {
            avgBpm > 160 -> 0.7f   // Muy rápido - mezcla más corta (Techno, Drum & Bass)
            avgBpm > 140 -> 0.8f   // Rápido - mezcla corta (House, EDM)
            avgBpm > 120 -> 0.9f   // Medio-rápido - mezcla media (Pop, Rock)
            avgBpm > 100 -> 1.0f   // Medio - duración estándar
            avgBpm > 80 -> 1.1f    // Medio-lento - mezcla más larga
            else -> 1.2f           // Lento - mezcla más larga (Baladas, Ambient)
        }

        val rhythmicComplexity = when {
            avgBpm > 170 -> 1.2f  // Drum & Bass / Hardcore (Mucha info rítmica)
            avgBpm > 135 -> 1.1f  // Dubstep / Techno rápido
            avgBpm > 115 -> 1.0f  // House / Pop estándar
            avgBpm > 90  -> 0.9f  // Hip Hop / Reggaeton (Beats más espaciados)
            else -> 0.85f         // R&B / Baladas (Poca complejidad rítmica)
        }

        // Calculamos duración final
        val calculatedDuration = (baseDuration * bpmDiffAdjustment * speedAdjustment * rhythmicComplexity).toLong()
        return calculatedDuration.coerceIn(4000L, 12000L)
    }

    private fun calculateBeatmatchDurationAdvanced(oldAnalysis: AudioAnalysis, newAnalysis: AudioAnalysis): Long {
        val bpm1 = oldAnalysis.bpmNormalized
        val bpm2 = newAnalysis.bpmNormalized

        // ✅ DURACIÓN BASADA EN COMPASES PARA BEATMATCHING
        val avgBpm = (bpm1 + bpm2) / 2.0
        val bpmDiff = abs(bpm1 - bpm2)

        val beatsNeeded = when {
            bpmDiff < 2 -> 32  // BPM muy similares - mezcla larga
            bpmDiff < 5 -> 24  // BPM similares - mezcla media
            bpmDiff < 10 -> 16 // BPM diferentes - mezcla más corta
            else -> 8          // BPM muy diferentes - mezcla corta
        }

        val durationFromBeats = ((60000.0 / avgBpm) * beatsNeeded).toLong()
        val structureDuration = calculateStructureBasedDuration(oldAnalysis, newAnalysis)

        // Usar la mayor duración entre estructura y beatmatching
        return max(durationFromBeats, structureDuration).coerceIn(8000L, 20000L)
    }

    private fun calculateHarmonicDuration(oldAnalysis: AudioAnalysis, newAnalysis: AudioAnalysis): Long {
        val compatibility = calculateHarmonicCompatibility(oldAnalysis.musicalKey, newAnalysis.musicalKey)

        // ✅ MEJORA: Las mezclas armónicas ahora son más largas y majestuosas.
        return when {
            compatibility >= 0.9 -> 16000L // Compatibilidad casi perfecta -> mezcla muy larga y cinematográfica
            compatibility >= 0.7 -> 12000L // Buena compatibilidad -> mezcla larga estándar
            else -> 9000L                  // Compatibilidad decente -> mezcla de duración media
        }.coerceIn(8000L, 20000L) // Aseguramos que NUNCA sea menor de 8 segundos.
    }

    private fun getDefaultDurationByStrategy(strategy: MixStrategy): Long {
        return when (strategy) {
            MixStrategy.BEATMATCH_MIX -> 12000L
            MixStrategy.HARMONIC_BLEND -> 10000L
            MixStrategy.RHYTHMIC_FADE -> 8000L
            else -> 8000L
        }
    }

    private fun calculateStructureBasedDuration(oldAnalysis: AudioAnalysis, newAnalysis: AudioAnalysis): Long {
        Log.d("IntelligentCrossfade", "🏗️ Calculando duración basada en estructura")

        // Usar puntos óptimos existentes en tu AudioAnalysis
        val oldOutPoint = oldAnalysis.optimalExitPointMs ?: (oldAnalysis.durationMs - 8000).coerceAtLeast(0)
        val newInPoint = newAnalysis.optimalStartPointMs ?: newAnalysis.audioStartMs

        // Calcular duración basada en la posición de los puntos óptimos
        val oldRemaining = oldAnalysis.durationMs - oldOutPoint
        val newIntro = newInPoint

        // La duración debe cubrir ambos segmentos
        val calculatedDuration = max(oldRemaining, newIntro).toLong()
        val finalDuration = calculatedDuration.coerceIn(6000L, 12000L)

        Log.d("IntelligentCrossfade", "📍 Puntos estructurales - Salida: ${oldOutPoint}ms, Entrada: ${newInPoint}ms")
        Log.d("IntelligentCrossfade", "⏱️ Duración estructural: ${finalDuration}ms")

        return finalDuration
    }

    private fun logDurationCalculation(strategy: MixStrategy, oldSong: Song, newSong: Song, finalDuration: Long) {
        val oldAnalysis = oldSong.audioAnalysis
        val newAnalysis = newSong.audioAnalysis

        if (oldAnalysis != null && newAnalysis != null) {
            Log.i("IntelligentCrossfade", "🎯 RESUMEN DE DURACIÓN - ${strategy.name}")
            Log.i("IntelligentCrossfade", "🎵 Canciones: ${oldSong.title} -> ${newSong.title}")
            Log.i("IntelligentCrossfade", "🥁 BPM: ${oldAnalysis.bpmNormalized} -> ${newAnalysis.bpmNormalized}")
            Log.i("IntelligentCrossfade", "🎼 Tonalidad: ${oldAnalysis.musicalKey} -> ${newAnalysis.musicalKey}")
            Log.i("IntelligentCrossfade", "⏱️ Duración final: ${finalDuration}ms")
            Log.i("IntelligentCrossfade", "📍 Puntos óptimos - Salida: ${oldAnalysis.optimalExitPointMs ?: "Auto"}ms, Entrada: ${newAnalysis.optimalStartPointMs ?: "Auto"}ms")
        }
    }

    private fun findOptimalOutPoint(analysis: AudioAnalysis?): Int {
        if (analysis == null) return 0

        analysis.optimalExitPointMs?.let { return it }

        val duration = analysis.durationMs
        val bpm = analysis.bpmNormalized

        // Para canciones rápidas, salir antes; para lentas, salir más tarde
        val baseOutPoint = when {
            bpm > 140 -> duration - 12000 // EDM, Techno - transiciones más largas
            bpm > 100 -> duration - 10000 // Pop, Rock - duración media
            else -> duration - 8000       // Baladas, Jazz - transiciones cortas
        }

        return baseOutPoint.coerceAtLeast(duration - 16000).coerceAtLeast(0)
    }

    private fun findOptimalInPoint(analysis: AudioAnalysis?): Int {
        if (analysis == null) return 0

        // ✅ PRIORIDAD: Punto de entrada óptimo del análisis
        analysis.optimalStartPointMs?.let { return it }

        // ✅ CALCULAR BASADO EN ESTRUCTURA MUSICAL
        val audioStart = analysis.audioStartMs
        val bpm = analysis.bpmNormalized
        val key = analysis.musicalKey

        // Para diferentes géneros y tonalidades, ajustar punto de entrada
        val calculatedInPoint = when {
            // Canciones con intros largas (ambient, classical)
            audioStart > 5000 -> audioStart
            // Canciones rápidas - entrar en el primer beat fuerte
            bpm > 120 -> max(audioStart, 2000)
            // Canciones con tonalidades complejas - buscar punto armónico
            key != null && key.contains("m") -> max(audioStart, 1500) // Menores - entrada más suave
            else -> max(audioStart, 1000) // Entrada estándar
        }

        return calculatedInPoint.coerceAtMost(analysis.durationMs - 5000)
    }

    private fun calculateRhythmicCompatibility(bpm1: Double, bpm2: Double): Float {
        val ratio = max(bpm1, bpm2) / min(bpm1, bpm2)

        return when {
            ratio < 1.1 -> 1.0f    // Muy similar
            ratio < 1.25 -> 0.8f   // Similar
            ratio < 1.5 -> 0.6f    // Moderado
            ratio < 2.0 -> 0.4f    // Diferente
            else -> 0.2f           // Muy diferente
        }
    }

    private fun areGenresSimilar(bpm1: Double, bpm2: Double): Boolean {
        val genre1 = detectGenreByBPM(bpm1)
        val genre2 = detectGenreByBPM(bpm2)

        return genre1 == genre2 || areGenresCompatible(genre1, genre2)
    }

    private fun areGenresCompatible(genre1: String, genre2: String): Boolean {
        val compatiblePairs = setOf(
            "HipHop" to "Pop",
            "Rock" to "Pop",
            "House" to "Techno",
            "Pop" to "House"
        )
        return (genre1 to genre2) in compatiblePairs || (genre2 to genre1) in compatiblePairs
    }

    private fun detectGenreByBPM(bpm: Double): String {
        return when {
            bpm < 60 -> "Ambient"
            bpm < 90 -> "HipHop"
            bpm < 110 -> "Rock"
            bpm < 130 -> "Pop"
            bpm < 150 -> "House"
            else -> "Techno"
        }
    }

    private fun calculateHarmonicCompatibility(key1: String?, key2: String?): Float {
        if (key1.isNullOrBlank() || key2.isNullOrBlank()) return 0f

        val numKey1 = parseKeyToNumeric(key1)
        val numKey2 = parseKeyToNumeric(key2)
        if (numKey1 == -1 || numKey2 == -1) return 0f

        val diff = abs(numKey1 - numKey2)

        return when (diff) {
            0 -> 1.0f    // Misma tonalidad
            3, 9 -> 0.9f // Relativo menor/mayor
            5, 7 -> 0.8f // 4ta/5ta justa
            1, 11 -> 0.7f // Semitono arriba/abajo
            2, 10 -> 0.6f // Tono arriba/abajo
            else -> 0.3f  // Baja compatibilidad
        }
    }

    private fun calculateAutomixStrategy(song1: Song, song2: Song): MixStrategy {
        val analysis1 = song1.audioAnalysis
        val analysis2 = song2.audioAnalysis
        if (analysis1 == null || analysis2 == null) return MixStrategy.STANDARD_CROSSFADE

        val bpm1 = analysis1.bpmNormalized
        val bpm2 = analysis2.bpmNormalized
        val key1 = analysis1.musicalKey
        val key2 = analysis2.musicalKey

        Log.d("IntelligentCrossfade", "🎵 Análisis - BPM: $bpm1->$bpm2, Key: $key1->$key2")

        // ✅ CRITERIO 1: COMPATIBILIDAD DE BPM (BEATMATCH)
        val bpmDiff = abs(bpm1 - bpm2)
        val bpmRatio = bpmDiff / min(bpm1, bpm2)
        val harmonicCompatibility = calculateHarmonicCompatibility(key1, key2)
        val isBpmCompatible = bpmRatio < BEATMATCH_BPM_TOLERANCE
        val isSimilarGenre = areGenresSimilar(bpm1, bpm2)

        // ✅ CRITERIO 0 (MÁXIMA PRIORIDAD): STEM SWAP MIX
        // Se activa solo si los BPM son casi idénticos Y la armonía es muy alta.
        val isBpmPerfect = bpmRatio < (BEATMATCH_BPM_TOLERANCE / 2) // Tolerancia más estricta
        if (isBpmPerfect && harmonicCompatibility >= 0.8f) {
            Log.d("IntelligentCrossfade", "👑 STEM_SWAP_MIX seleccionado - Condiciones perfectas!")
            return MixStrategy.STEM_SWAP_MIX
        }

        if (isBpmCompatible && isSimilarGenre) {
            Log.d("IntelligentCrossfade", "🎯 BEATMATCH_MIX seleccionado - BPM compatibles")
            return MixStrategy.BEATMATCH_MIX
        }

        // ✅ CRITERIO 2: COMPATIBILIDAD ARMÓNICA MEJORADA
        if (harmonicCompatibility >= 0.7) {
            Log.d("IntelligentCrossfade", "🎵 HARMONIC_BLEND seleccionado - Alta compatibilidad armónica: $harmonicCompatibility")
            return MixStrategy.HARMONIC_BLEND
        }

        // ✅ CRITERIO 3: COMPATIBILIDAD RÍTMICA
        val rhythmicCompatibility = calculateRhythmicCompatibility(bpm1, bpm2)
        if (rhythmicCompatibility >= 0.6) {
            Log.d("IntelligentCrossfade", "🥁 RHYTHMIC_FADE seleccionado - Compatibilidad rítmica: $rhythmicCompatibility")
            return MixStrategy.RHYTHMIC_FADE
        }

        Log.d("IntelligentCrossfade", "🔄 STANDARD_CROSSFADE seleccionado - Baja compatibilidad musical")
        return MixStrategy.STANDARD_CROSSFADE
    }

    private fun resetEqToFlat(eq: Equalizer, bands: ShortArray) {
        bands.forEach { band ->
            setSafeBandLevel(eq, (band).toInt(), (0).toFloat()) // Nivel plano
        }
    }

    private fun resetEqToCut(eq: Equalizer, bands: ShortArray) {
        bands.forEach { band ->
            setSafeBandLevel(eq, (band).toInt(), (EQ_MAX_ATTENUATION).toFloat()) // Nivel cortado
        }
    }

    private fun calculateBeatAlignment(oldBeats: List<Int>, newBeats: List<Int>, oldPlayerCurrentPosition: Long): Int {
        if (oldBeats.isEmpty() || newBeats.isEmpty()) return 0

        val currentTime = oldPlayerCurrentPosition.toInt()

        val nextOldBeat = oldBeats.firstOrNull { it > currentTime } ?: oldBeats.last()
        val closestNewBeat = newBeats.minByOrNull { abs(it - nextOldBeat) } ?: 0

        return (closestNewBeat - nextOldBeat).coerceIn(-2000, 2000)
    }

    private fun syncTempo(oldPlayer: ExoPlayer, oldAnalysis: AudioAnalysis, newAnalysis: AudioAnalysis, newPlayer: ExoPlayer) {
        try {
            // 1. Cálculo de velocidad base (Igual que antes)
            val speedAdjustment = (oldAnalysis.bpmNormalized / newAnalysis.bpmNormalized).toFloat()
            val clampedSpeed = speedAdjustment.coerceIn(0.92f, 1.08f)
            var finalSpeed = clampedSpeed

            // 2. 🔥 NUEVA LÓGICA DE ALINEACIÓN DE FASE (Sin beatGridJson) 🔥
            // Calculamos cuánto dura un beat en ms en la canción nueva
            val newBeatDurationMs = 60000.0 / newAnalysis.bpmNormalized

            // Obtenemos la posición actual
            val currentPos = oldPlayer.currentPosition

            // Calculamos dónde estamos dentro del "ciclo" del beat (0.0 a 1.0)
            // Esto asume que el primer beat es en 0ms (o consistente).
            // Para mayor precisión, necesitarías solo un valor: 'firstBeatOffset' del backend.
            val phaseOffset = (currentPos % newBeatDurationMs)

            // Si el desfase es grande (> 50% del beat), significa que estamos "adelantados" o "atrasados"
            // Intentamos empujar la velocidad ligeramente para alinear el downbeat matemáticamente.
            val phaseError = if (phaseOffset > (newBeatDurationMs / 2)) {
                phaseOffset - newBeatDurationMs // Estamos adelantados, frenar
            } else {
                phaseOffset // Estamos atrasados, acelerar
            }

            // Aplicamos corrección solo si el error es perceptible (> 20ms) pero corregible (< 200ms)
            if (abs(phaseError) > 20 && abs(phaseError) < 200) {
                // Factor de corrección suave (0.01x a 0.05x)
                val fineTune = (phaseError / 1000f).toFloat().coerceIn(-0.03f, 0.03f)
                // Invertimos el signo: si estamos atrasados (+), necesitamos ir más rápido
                finalSpeed -= fineTune
                Log.d("IntelligentCrossfade", "⏱️ Ajuste matemático: ${"%.3f".format(fineTune)}x, PhaseError: ${phaseError.toLong()}ms")
            }

            val playbackParameters = PlaybackParameters(finalSpeed)
            newPlayer.playbackParameters = playbackParameters
            Log.d("IntelligentCrossfade", "⏱️ Sincronizando tempo. Ajuste final: ${"%.3f".format(finalSpeed)}x")

        } catch (e: Exception) {
            Log.e("IntelligentCrossfade", "Error al ajustar playback rate", e)
        }
    }

    private fun parseKeyToNumeric(key: String): Int {
        val keyMap = mapOf(
            "C" to 0, "B#" to 0, "C#" to 1, "Db" to 1, "D" to 2, "D#" to 3, "Eb" to 3,
            "E" to 4, "Fb" to 4, "F" to 5, "E#" to 5, "F#" to 6, "Gb" to 6, "G" to 7,
            "G#" to 8, "Ab" to 8, "A" to 9, "A#" to 10, "Bb" to 10, "B" to 11, "Cb" to 11
        )
        val isMinor = key.endsWith("m")
        val root = if (isMinor) key.dropLast(1) else key
        val numericKey = keyMap[root] ?: return -1
        return if (isMinor) (numericKey + 3) % 12 else numericKey
    }

    fun predictIntelligentMixDuration(currentSong: Song, nextSong: Song): Long {
        // Reutiliza la misma lógica que el motor de mezcla para ser consistente.
        val strategy = calculateAutomixStrategy(currentSong, nextSong)
        return calculateFullyIntelligentDuration(currentSong, nextSong, strategy)
    }

    /**
     * Releases both active transition resources and silent preloads. Unlike
     * [cancelCurrentTransition], this is safe to call during service teardown.
     */
    fun release() {
        active?.let { detachTransition(it) }
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
