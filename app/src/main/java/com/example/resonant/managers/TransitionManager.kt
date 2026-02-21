package com.example.resonant.managers

import android.content.Context
import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.resonant.playback.IntelligentEffectsState
import com.example.resonant.playback.MixStrategy
import com.example.resonant.playback.PlaybackStateRepository
import com.example.resonant.data.models.AudioAnalysis
import com.example.resonant.data.models.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class TransitionManager(
    private val context: Context,
    private val onTransitionComplete: (newPlayer: ExoPlayer, oldPlayer: ExoPlayer) -> Unit,
    private val onTransitionFailed: (oldPlayer: ExoPlayer) -> Unit,
    private val onTransitionProgress: ((position: Long, duration: Long) -> Unit)? = null
) {

    companion object {
        private const val BEATMATCH_BPM_TOLERANCE = 0.06
        private const val ANIMATION_FRAME_DELAY_MS = 16L
        private const val EQ_MAX_ATTENUATION: Short = -1500
        private const val EQ_MEDIUM_ATTENUATION: Short = -800
        private const val EQ_LIGHT_ATTENUATION: Short = -400
    }

    @Volatile
    var isTransitioning: Boolean = false
        private set
    private var nextPlayer: ExoPlayer? = null
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

    private fun handleMixCompletion(newPlayer: ExoPlayer, oldPlayer: ExoPlayer, nextSong: Song) {
        if (!isTransitioning) {
            Handler(Looper.getMainLooper()).post { newPlayer.release() }
            return
        }

        isTransitioning = false
        this.nextPlayer = null

        // Limpieza de EQs
        oldPlayerEq?.release()
        newPlayerEq?.release()
        oldPlayerEq = null
        newPlayerEq = null

        this.currentPlayer = null // 🔥
        this.currentSong = null   // 🔥
        transitionProgress = 0f // 🔥

        // Restaurar parámetros del nuevo reproductor
        try {
            if (abs(newPlayer.playbackParameters.speed - 1.0f) > 0.01f) {
                newPlayer.setPlaybackParameters(PlaybackParameters(1.0f))
            }
            // 🔥 CLAVE: En lugar de forzar volumen a 1.0f, lo dejamos en su nivel normalizado final.
            newPlayer.volume = this.targetNewPlayerVolume
        } catch (e: Exception) { Log.e("TransitionManager", "Error restaurando parámetros", e) }

        this.targetOldPlayerVolume = 1.0f
        this.targetNewPlayerVolume = 1.0f

        // Notifica al servicio que la transición ha terminado
        onTransitionComplete(newPlayer, oldPlayer)

        Log.d("TransitionManager", "🎉 Transición COMPLETADA: ${nextSong.title}")
    }

    private fun handleMixFailure(oldPlayer: ExoPlayer, newPlayer: ExoPlayer?) {
        Log.e("TransitionManager", "❌ Fallo crítico en transición, recuperando estado...")
        newPlayer?.release()
        isTransitioning = false
        nextPlayer = null
        transitionProgress = 0f
        this.currentPlayer = null
        this.currentSong = null
        this.targetOldPlayerVolume = 1.0f
        this.targetNewPlayerVolume = 1.0f
        onTransitionFailed(oldPlayer)
    }

    /**
     * Called when the transition player fails with a Source error (typically expired stream URL).
     * Releases the failed player, fetches a fresh URL via SongManager, updates the queue,
     * and retries the intelligent mix. Falls back to handleMixFailure if the retry also fails.
     */
    private fun retryWithFreshUrl(
        nextSong: Song,
        oldPlayer: ExoPlayer,
        failedPlayer: ExoPlayer,
        durationMs: Long,
        strategy: MixStrategy,
        optimalOutPoint: Int,
        optimalInPoint: Int,
        onMixComplete: (newPlayer: ExoPlayer) -> Unit
    ) {
        failedPlayer.release()
        if (failedPlayer === this.nextPlayer) {
            this.nextPlayer = null
        }

        Log.d("TransitionManager", "🔄 Fetching fresh URL for '${nextSong.title}'...")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val songManager = SongManager(context)
                songManager.invalidateCache(nextSong.id)
                val fresh = songManager.getSongById(nextSong.id)

                if (fresh != null && !fresh.url.isNullOrEmpty()) {
                    // Update the song in the queue so future accesses use the fresh URL.
                    val queue = PlaybackStateRepository.activeQueue
                    if (queue != null) {
                        val idx = queue.songs.indexOfFirst { it.id == nextSong.id }
                        if (idx >= 0) {
                            val mutableSongs = queue.songs.toMutableList()
                            mutableSongs[idx] = fresh
                            queue.songs = mutableSongs.toList()
                        }
                    }

                    withContext(Dispatchers.Main) {
                        Log.d("TransitionManager", "✅ Got fresh URL for '${fresh.title}'. Retrying transition.")
                        val nextIndex = queue?.songs?.indexOf(fresh) ?: -1
                        if (nextIndex >= 0) {
                            prepareAndExecuteFullyIntelligentMix(
                                oldPlayer, currentSong ?: fresh, fresh,
                                nextIndex, durationMs, strategy,
                                optimalOutPoint, optimalInPoint, onMixComplete
                            )
                        } else {
                            handleMixFailure(oldPlayer, null)
                        }
                    }
                } else {
                    Log.e("TransitionManager", "Retry failed — no URL returned for ${nextSong.id}")
                    withContext(Dispatchers.Main) {
                        handleMixFailure(oldPlayer, null)
                    }
                }
            } catch (e: Exception) {
                Log.e("TransitionManager", "Retry failed with exception", e)
                withContext(Dispatchers.Main) {
                    handleMixFailure(oldPlayer, null)
                }
            }
        }
    }



    fun startTransition(oldPlayer: ExoPlayer,
                        oldSong: Song,
                        nextSong: Song,
                        nextSongIndex: Int,
                        crossfadeMode: CrossfadeMode,
                        isAlbumMode: Boolean,
                        automixEnabled: Boolean,
                        crossfadeDurationMs: Long,
                        oldSongTargetVolume: Float = 1.0f,
                        nextSongTargetVolume: Float = 1.0f
    ) {
        Log.d("TransitionManager", "🎯 transitionToNextSong INICIADA - nextSong: ${nextSong.title}")

        // ✅ LÓGICA CORREGIDA: Verificar si debe hacer transición
        val shouldTransition = when {
            crossfadeMode == CrossfadeMode.INTELLIGENT_EQ -> {
                // El modo inteligente SIEMPRE hace transición (dura 0ms pero usa el motor inteligente)
                Log.d("TransitionManager", "🧠 Modo Inteligente - Usando motor de transiciones inteligentes")
                true
            }
            isAlbumMode -> {
                // En modo álbum, depende de automixEnabled
                val should = automixEnabled
                Log.d("TransitionManager", "📀 Modo Álbum - Automix: $automixEnabled -> Transición: $should")
                should
            }
            else -> {
                // En modos normales, depende de la duración
                val should = crossfadeDurationMs > 0
                Log.d("TransitionManager", "🎵 Modo Normal - Duración: ${crossfadeDurationMs}ms -> Transición: $should")
                should
            }
        }
        if (!shouldTransition) {
            Log.d("TransitionManager", "❌ Transición desactivada. Saltando directamente.")
            onTransitionFailed(oldPlayer)
            return
        }

        synchronized(this) {
            if (isTransitioning) {
                Log.w("TransitionManager", "Ignorando trigger, ya hay una transición en curso.")
                return
            }
            isTransitioning = true
            transitionProgress = 0f // 🔥 AÑADE ESTA LÍNEA
            this.currentPlayer = oldPlayer // 🔥 AÑADE ESTA LÍNEA
            this.currentSong = oldSong   // 🔥 AÑADE ESTA LÍNEA
            this.targetOldPlayerVolume = oldSongTargetVolume
            this.targetNewPlayerVolume = nextSongTargetVolume
        }

        val onComplete: (newPlayer: ExoPlayer) -> Unit = { newPlayer ->
            handleMixCompletion(newPlayer, oldPlayer, nextSong)
        }

        // ✅ LÓGICA DE SELECCIÓN DE TRANSICIÓN MEJORADA
        if (isAlbumMode) {
            performBasicAlbumMix(oldPlayer, oldSong, nextSong, nextSongIndex, onComplete)
        } else {
            when (crossfadeMode) {
                CrossfadeMode.INTELLIGENT_EQ -> {
                    // 🔥 CLAVE: El modo inteligente usa su propia duración calculada, NO crossfadeDurationMs
                    performFullyIntelligentMix(oldPlayer, oldSong, nextSong, nextSongIndex, onComplete)
                }
                else -> {
                    // Modos normales usan la duración del slider
                    performSimpleCrossfade(oldPlayer, oldSong, nextSong, nextSongIndex, onComplete, crossfadeMode, crossfadeDurationMs)
                }
            }
        }
    }

    fun cancelCurrentTransition() {
        if (!isTransitioning) return
        Log.w("TransitionManager", "🛑 CANCELANDO TRANSICIÓN EN CURSO...")
        isTransitioning = false
        transitionProgress = 0f // 🔥

        try { nextPlayer?.stop(); nextPlayer?.release() } catch (e: Exception) { Log.e("TransitionManager", "Error al liberar nextExoPlayer", e) }
        nextPlayer = null
        try { oldPlayerEq?.release(); newPlayerEq?.release() } catch (e: Exception) { Log.e("TransitionManager", "Error al liberar EQs", e) }
        oldPlayerEq = null
        newPlayerEq = null

        this.currentPlayer = null // 🔥
        this.currentSong = null   // 🔥
        this.targetOldPlayerVolume = 1.0f
        this.targetNewPlayerVolume = 1.0f
        Log.d("TransitionManager", "✅ Transición cancelada.")
    }

    fun forceCompleteTransition() {
        Log.w("TransitionManager", "🚀 FORZANDO COMPLETADO DE TRANSICIÓN...")
        if (!isTransitioning || this.nextPlayer == null || this.currentPlayer == null || this.currentSong == null) {
            Log.e("TransitionManager", "No se puede forzar, estado inválido.")
            if (isTransitioning) {
                cancelCurrentTransition()
            }
            return
        }
        handleMixCompletion(this.nextPlayer!!, this.currentPlayer!!, this.currentSong!!)
    }

    fun preloadNextSong(nextSong: Song?) {
        // Si ya hay un reproductor precargado, lo liberamos antes de crear uno nuevo.
        nextPlayer?.release()
        nextPlayer = null

        if (nextSong == null) {
            Log.d("TransitionManager", "No hay siguiente canción para precargar.")
            return
        }

        Log.i("TransitionManager", "⚡️ PRE-CARGANDO siguiente canción: ${nextSong.title}")

        try {
            val newPlayer = ExoPlayer.Builder(context).build()
            val mediaItem = MediaItem.fromUri(nextSong.url ?: "")

            // NO le damos la cola completa, solo la canción que necesita para prepararse.
            newPlayer.setMediaItem(mediaItem)
            newPlayer.volume = 0f // Lo dejamos en silencio
            newPlayer.prepare() // Iniciamos la preparación en segundo plano

            // Guardamos la referencia para usarla más tarde.
            this.nextPlayer = newPlayer
        } catch (e: Exception) {
            Log.e("TransitionManager", "Error durante la pre-carga de ${nextSong.title}", e)
            nextPlayer?.release()
            nextPlayer = null
        }
    }

    /**
     * Builds a filtered list of MediaItems for ExoPlayer, excluding songs without valid URLs.
     * Each MediaItem has the song's ID set as its mediaId so callers can look up the correct
     * song after playback events, even when indices shift due to filtered-out items.
     *
     * Returns a list of (originalQueueIndex, MediaItem) pairs so callers can map the adjusted
     * ExoPlayer index back to the original queue index when needed.
     */
    fun buildValidMediaItems(songs: List<Song>): List<Pair<Int, MediaItem>> {
        val result = mutableListOf<Pair<Int, MediaItem>>()
        songs.forEachIndexed { index, song ->
            val url = song.url
            if (!url.isNullOrEmpty()) {
                val uri = if (url.startsWith("http") || url.startsWith("https")) {
                    android.net.Uri.parse(url)
                } else {
                    android.net.Uri.fromFile(java.io.File(url))
                }
                val item = MediaItem.Builder()
                    .setUri(uri)
                    .setMediaId(song.id)
                    .build()
                result.add(index to item)
            } else {
                Log.v("TransitionManager", "Skipping song ${song.id} (no URL) from transition queue")
            }
        }
        return result
    }


    // =================================================================================================
    // ===                                  AUTOMIX BÁSICO (PARA ÁLBUMES)                            ===
    // =================================================================================================

    private fun performBasicAlbumMix(
        oldPlayer: ExoPlayer, oldSong: Song, newSong: Song,
        nextSongIndex: Int,
        onMixComplete: (newPlayer: ExoPlayer) -> Unit
    ) {
        Log.i("ResonantAutomix", "🤖 Iniciando Automix de Álbum (usando pre-carga)...")

        // 1. OBTENEMOS EL REPRODUCTOR YA PREPARADO
        val newPlayer = this.nextPlayer

        // 2. COMPROBACIÓN DE SEGURIDAD: ¿Y si no se pudo pre-cargar?
        if (newPlayer == null) {
            Log.e("ResonantAutomix", "ERROR FATAL: El reproductor para '${newSong.title}' no fue pre-cargado. Saltando.")
            // Si falla, notificamos al servicio que simplemente salte a la siguiente canción
            // sin una transición suave para evitar un error mayor.
            onTransitionFailed(oldPlayer)
            return
        }

        // 3. COMO EL REPRODUCTOR YA ESTÁ LISTO, LA LÓGICA ES INMEDIATA.
        try {
            // Le damos la cola completa AHORA, justo antes de empezar.
            // Only include songs with valid URLs to avoid ExoPlayer errors on empty URIs.
            val queue = PlaybackStateRepository.activeQueue!!
            val validPairs = buildValidMediaItems(queue.songs)
            val adjustedIndex = validPairs.indexOfFirst { it.first == nextSongIndex }
                .takeIf { it >= 0 } ?: 0
            newPlayer.setMediaItems(validPairs.map { it.second }, adjustedIndex, 0L)

            newPlayer.volume = this.targetNewPlayerVolume
            newPlayer.play()
            onMixComplete(newPlayer)

            // IMPORTANTE: Reseteamos la referencia para que no se reutilice por error.
            this.nextPlayer = null

        } catch (e: Exception) {
            Log.e("ResonantAutomix", "Error ejecutando el corte con el reproductor pre-cargado.", e)
            handleMixFailure(oldPlayer, newPlayer)
        }
    }

    // =================================================================================================
    // ===                                  MOTOR DE CROSSFADE SIMPLE                              ===
    // =================================================================================================

    private fun performSimpleCrossfade(
        oldPlayer: ExoPlayer, oldSong: Song, newSong: Song,
        nextSongIndex: Int,
        onMixComplete: (newPlayer: ExoPlayer) -> Unit,
        crossfadeMode: CrossfadeMode,
        crossfadeDurationMs: Long
    ) {
        Log.i("Crossfade", "🔄 Iniciando Crossfade Simple. Índice siguiente: $nextSongIndex")
        val newPlayer = ExoPlayer.Builder(context).build()
        this.nextPlayer = newPlayer

        try {
            val queue = PlaybackStateRepository.activeQueue

            if (queue == null || queue.songs.isEmpty()) {
                Log.e("Crossfade", "Error: No se encontró una cola activa para la transición.")
                handleMixFailure(oldPlayer, newPlayer)
                return
            }

            // Filter out songs without valid URLs; track the adjusted start index.
            val validPairs = buildValidMediaItems(queue.songs)
            val adjustedIndex = validPairs.indexOfFirst { it.first == nextSongIndex }
                .takeIf { it >= 0 } ?: run {
                    Log.e("Crossfade", "Next song at index $nextSongIndex has no valid URL. Aborting crossfade.")
                    handleMixFailure(oldPlayer, newPlayer)
                    return
                }

            val startPositionMs = newSong.audioAnalysis?.audioStartMs?.toLong() ?: 0L
            newPlayer.setMediaItems(validPairs.map { it.second }, adjustedIndex, startPositionMs)

            newPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        newPlayer.removeListener(this)
                        Log.d("Crossfade", "🎵 NewPlayer listo con la cola completa. Iniciando crossfade.")

                        try {
                            newPlayer.volume = 0.0f
                            newPlayer.play()
                            animateSimpleCrossfade(oldPlayer, newPlayer, oldSong, newSong, onMixComplete, crossfadeMode, crossfadeDurationMs)
                        } catch (e: Exception) {
                            Log.e("Crossfade", "Error al iniciar newPlayer listo.", e)
                            handleMixFailure(oldPlayer, newPlayer)
                        }
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    newPlayer.removeListener(this)
                    val isSourceError = error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

                    if (isSourceError) {
                        Log.w("Crossfade", "⚠️ Source error — refreshing URL for ${newSong.title}")
                        newPlayer.release()
                        if (newPlayer === nextPlayer) nextPlayer = null
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val songManager = SongManager(context)
                                songManager.invalidateCache(newSong.id)
                                val fresh = songManager.getSongById(newSong.id)
                                if (fresh != null && !fresh.url.isNullOrEmpty()) {
                                    val queue = PlaybackStateRepository.activeQueue
                                    if (queue != null) {
                                        val idx = queue.songs.indexOfFirst { it.id == newSong.id }
                                        if (idx >= 0) {
                                            val mutable = queue.songs.toMutableList()
                                            mutable[idx] = fresh
                                            queue.songs = mutable.toList()
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        val ni = queue?.songs?.indexOf(fresh) ?: -1
                                        if (ni >= 0) {
                                            performSimpleCrossfade(oldPlayer, oldSong, fresh, ni, onMixComplete, crossfadeMode, crossfadeDurationMs)
                                        } else {
                                            handleMixFailure(oldPlayer, null)
                                        }
                                    }
                                } else {
                                    withContext(Dispatchers.Main) { handleMixFailure(oldPlayer, null) }
                                }
                            } catch (e: Exception) {
                                Log.e("Crossfade", "Retry failed", e)
                                withContext(Dispatchers.Main) { handleMixFailure(oldPlayer, null) }
                            }
                        }
                    } else {
                        Log.e("Crossfade", "Non-recoverable error in newPlayer.", error)
                        handleMixFailure(oldPlayer, newPlayer)
                    }
                }
            })

            newPlayer.prepare()

        } catch (e: Exception) {
            Log.e("MusicService", "Error crítico preparando newPlayer para crossfade.", e)
            handleMixFailure(oldPlayer, newPlayer)
        }
    }

    private fun animateSimpleCrossfade(
        oldPlayer: ExoPlayer,
        newPlayer: ExoPlayer,
        oldSong: Song,
        newSong: Song,
        onMixComplete: (newPlayer: ExoPlayer) -> Unit,
        crossfadeMode: CrossfadeMode,
        duration: Long) {

        val handler = Handler(Looper.getMainLooper())
        val startTime = SystemClock.uptimeMillis()

        val animationRunnable = object : Runnable {
            override fun run() {
                if (!isTransitioning) {
                    Log.w("Crossfade", "🛑 Animación simple detenida porque la transición fue cancelada.")
                    handler.removeCallbacks(this)
                    return
                }

                val elapsed = SystemClock.uptimeMillis() - startTime
                val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                transitionProgress = progress // 🔥 AÑADE ESTA LÍNEA

                val (oldVol, newVol) = when (crossfadeMode) {
                    CrossfadeMode.SOFT_MIX -> Pair(sqrt(1 - progress), sqrt(progress))
                    CrossfadeMode.DIRECT_CUT -> {
                        val smooth = 0.5f * (1 - cos(progress * Math.PI.toFloat()))
                        Pair(1 - smooth, smooth)
                    }
                    else -> Pair(sqrt(1 - progress), sqrt(progress)) // Fallback
                }

                try {
                    // Animación del volumen usando los volúmenes absolutos reales como base máxima
                    oldPlayer.volume = oldVol * targetOldPlayerVolume
                    newPlayer.volume = newVol * targetNewPlayerVolume

                    if (elapsed % 500 < ANIMATION_FRAME_DELAY_MS) {
                        val position = newPlayer.currentPosition
                        val newDuration = newPlayer.duration
                        if (newDuration > 0) {
                            // Llama al callback si alguien nos lo proporcionó
                            onTransitionProgress?.invoke(position, newDuration)
                        }
                    }

                } catch (e: Exception) {
                    Log.e("Crossfade", "❌ Error durante la animación simple. Deteniendo bucle.", e)
                    handler.removeCallbacks(this)
                    handleMixFailure(oldPlayer, newPlayer)
                    return
                }

                if (progress < 1f) {
                    handler.postDelayed(this,
                        ANIMATION_FRAME_DELAY_MS
                    )
                } else {
                    Log.i("Crossfade", "✅ Animación completada.")
                    // SIN oldPlayer.stop(), SIN liberar EQs, SIN restaurar volumen.
                    onMixComplete(newPlayer)
                }
            }
        }
        handler.post(animationRunnable)
    }

    // =================================================================================================
    // ===                           CROSSFADE INTELIGENTE (MOTOR DE DJ)                             ===
    // =================================================================================================

    private fun performFullyIntelligentMix(oldPlayer: ExoPlayer, oldSong: Song, newSong: Song, nextSongIndex: Int, onMixComplete: (newPlayer: ExoPlayer) -> Unit) {
        val strategy = calculateAutomixStrategy(oldSong, newSong)
        val mixDuration = calculateFullyIntelligentDuration(oldSong, newSong, strategy)
        val optimalOutPoint = findOptimalOutPoint(oldSong.audioAnalysis)
        val optimalInPoint = findOptimalInPoint(newSong.audioAnalysis)

        Log.i("IntelligentCrossfade", "🧠 MEZCLA INTELIGENTE COMPLETA - IGNORANDO DURACIÓN DEL SLIDER")
        Log.i("IntelligentCrossfade", "🎯 Estrategia: ${strategy.name}")
        Log.i("IntelligentCrossfade", "⏱️ Duración calculada: ${mixDuration}ms")
        Log.i("IntelligentCrossfade", "📍 Índice Siguiente: $nextSongIndex")

        prepareAndExecuteFullyIntelligentMix(
            oldPlayer, oldSong, newSong,
            nextSongIndex, // <-- PÁSALE EL ÍNDICE
            mixDuration, strategy,
            optimalOutPoint, optimalInPoint,
            onMixComplete
        )
    }

    private fun prepareAndExecuteFullyIntelligentMix(
        oldPlayer: ExoPlayer,
        oldSong: Song,
        newSong: Song,
        nextSongIndex: Int,
        durationMs: Long,
        strategy: MixStrategy,
        optimalOutPoint: Int,
        optimalInPoint: Int,
        onMixComplete: (newPlayer: ExoPlayer) -> Unit
    ) {
        Log.i("IntelligentCrossfade", "🚀 Preparando mezcla inteligente. Índice Siguiente: $nextSongIndex")
        Log.i("IntelligentCrossfade", "📍 Puntos: Salida=${optimalOutPoint}ms, Entrada=${optimalInPoint}ms")

        val newPlayer = ExoPlayer.Builder(context).build()
        // Release any previously preloaded player before replacing it.
        // (It was prepared silently; releasing it prevents resource leaks.)
        val existingPreloaded = this.nextPlayer
        if (existingPreloaded != null && existingPreloaded !== newPlayer) {
            existingPreloaded.release()
        }
        this.nextPlayer = newPlayer

        try {

            val queue = PlaybackStateRepository.activeQueue
            if (queue == null || queue.songs.isEmpty()) {
                Log.e("IntelligentCrossfade", "Error: No se encontró una cola activa para la transición.")
                handleMixFailure(oldPlayer, newPlayer)
                return
            }

            // Filter out songs without valid URLs; track the adjusted start index.
            val validPairs = buildValidMediaItems(queue.songs)
            val adjustedIndex = validPairs.indexOfFirst { it.first == nextSongIndex }
                .takeIf { it >= 0 } ?: run {
                    Log.e("IntelligentCrossfade", "Next song at index $nextSongIndex has no valid URL. Aborting intelligent mix.")
                    handleMixFailure(oldPlayer, newPlayer)
                    return
                }

            newPlayer.setMediaItems(validPairs.map { it.second }, adjustedIndex, optimalInPoint.toLong())

            newPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        newPlayer.removeListener(this)
                        Log.d("IntelligentCrossfade", "✅ NewPlayer listo con la cola completa. Iniciando mezcla.")

                        try {
                            if (strategy == MixStrategy.BEATMATCH_MIX) {
                                oldSong.audioAnalysis?.let { oldA ->
                                    newSong.audioAnalysis?.let { newA ->
                                        syncTempo(oldPlayer, oldA, newA, newPlayer)
                                    }
                                }
                            }

                            newPlayer.volume = 0f
                            newPlayer.play()

                            Log.d("IntelligentCrossfade", "🎵 NewPlayer iniciado en la posición correcta.")

                            // 7. INICIAR ANIMACIÓN (se mantiene igual)
                            animateFullyIntelligentMix(
                                oldPlayer, newPlayer, oldSong, newSong,
                                durationMs, strategy,
                                optimalOutPoint, optimalInPoint,
                                onMixComplete
                            )
                        } catch (e: Exception) {
                            Log.e("IntelligentCrossfade", "Error al iniciar newPlayer listo.", e)
                            handleMixFailure(oldPlayer, newPlayer)
                        }
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    newPlayer.removeListener(this)
                    val isSourceError = error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

                    if (isSourceError) {
                        // The stream URL for the next song has likely expired. Fetch a fresh one
                        // and retry the transition instead of failing loudly.
                        Log.w("IntelligentCrossfade", "⚠️ Source error on transition player — refreshing URL for ${newSong.title}")
                        Handler(Looper.getMainLooper()).post {
                            retryWithFreshUrl(newSong, oldPlayer, newPlayer, durationMs, strategy, optimalOutPoint, optimalInPoint, onMixComplete)
                        }
                    } else {
                        Log.e("IntelligentCrossfade", "Non-recoverable error in newPlayer during preparation.", error)
                        handleMixFailure(oldPlayer, newPlayer)
                    }
                }
            })

            newPlayer.prepare()

        } catch (e: Exception) {
            Log.e("IntelligentCrossfade", "❌ Error crítico al preparar mezcla inteligente", e)
            handleMixFailure(oldPlayer, newPlayer)
        }
    }

    private fun animateFullyIntelligentMix(
        oldPlayer: ExoPlayer,
        newPlayer: ExoPlayer,
        oldSong: Song,
        newSong: Song,
        durationMs: Long,
        strategy: MixStrategy,
        optimalOutPoint: Int,
        optimalInPoint: Int,
        onMixComplete: (newPlayer: ExoPlayer) -> Unit
    ) {
        Log.i("Crossfade", "🎬 ANIMACIÓN INICIADA - Duración: ${durationMs}ms, Estrategia: ${strategy.name}")

        initializeIntelligentEQ(oldPlayer, newPlayer)
        val handler = Handler(Looper.getMainLooper())
        val startTime = SystemClock.uptimeMillis()
        var effectsState = IntelligentEffectsState()

        val animationRunnable = object : Runnable {
            override fun run() {
                if (!isTransitioning) {
                    Log.w("Crossfade", "🛑 Animación detenida porque la transición fue cancelada.")
                    handler.removeCallbacks(this)
                    return
                }

                val elapsed = SystemClock.uptimeMillis() - startTime
                val progress = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
                transitionProgress = progress // 🔥 AÑADE ESTA LÍNEA

                // ✅ LOG DE PROGRESO CADA 500ms
                if (elapsed % 500 < 20) {
                    Log.d("Crossfade", "📊 Progreso: ${(progress * 100).toInt()}% (${elapsed}ms/${durationMs}ms)")
                }

                try {
                    if (!verifyPlayersState(oldPlayer, newPlayer)) {
                        Log.e("Crossfade", "❌ Estado de players inválido")
                        handler.removeCallbacks(this)
                        handleMixFailure(oldPlayer, newPlayer)
                        return
                    }

                    val (oldVol, newVol) = getEnhancedIntelligentVolumeCurve(progress, strategy)
                    applyVolumeSmoothly(oldPlayer, newPlayer, oldVol, newVol)
                    applyEnhancedIntelligentEq(oldPlayerEq, newPlayerEq, progress, strategy, oldSong, newSong)
                    applyIntelligentEffects(progress, strategy, effectsState)

                    if (elapsed % 500 < ANIMATION_FRAME_DELAY_MS) {
                        val position = newPlayer.currentPosition
                        val newDuration = newPlayer.duration
                        if (newDuration > 0) {
                            // Llama al callback si alguien nos lo proporcionó
                            onTransitionProgress?.invoke(position, newDuration)
                        }
                    }

                } catch (e: Exception) {
                    Log.e("Crossfade", "❌ Error durante la animación inteligente", e)
                    handler.removeCallbacks(this)
                    handleMixFailure(oldPlayer, newPlayer)
                    return
                }

                if (progress < 1f) {
                    handler.postDelayed(this,
                        ANIMATION_FRAME_DELAY_MS
                    )
                } else {
                    Log.i("Crossfade", "✅ ANIMACIÓN COMPLETADA - Llamando onMixComplete")
                    onMixComplete(newPlayer)
                }
            }
        }
        handler.post(animationRunnable)
    }

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
                        setBandLevel(i.toShort(), 0)
                    }
                    Log.d("IntelligentCrossfade", "✅ OldPlayer EQ (ExoPlayer) inicializado en sesión $sessionId - Bandas: $numberOfBands")
                }
            } ?: Log.w("IntelligentCrossfade", "OldPlayer audio session ID no estaba lista para el EQ.")

            // Hacemos lo mismo para el nuevo reproductor.
            newPlayer.audioSessionId.takeIf { it != 0 }?.let { sessionId ->
                newPlayerEq = Equalizer(0, sessionId).apply {
                    enabled = true
                    for (i in 0 until numberOfBands) {
                        setBandLevel(i.toShort(), 0)
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
                    eq.setBandLevel(band, boostedLevel)
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
        oldEq.setBandLevel(bands[0], (maxAttenuation * bassProgress).toInt().toShort())
        newEq.setBandLevel(bands[0], (maxAttenuation * (1 - bassProgress)).toInt().toShort())

        // MIDS (Bandas 1, 2, 3)
        for (i in 1..3) {
            oldEq.setBandLevel(bands[i], (maxAttenuation * midProgress).toInt().toShort())
            newEq.setBandLevel(bands[i], (maxAttenuation * (1 - midProgress)).toInt().toShort())
        }

        // HIGHS (Banda 4)
        oldEq.setBandLevel(bands[4], (maxAttenuation * highProgress).toInt().toShort())
        newEq.setBandLevel(bands[4], (maxAttenuation * (1 - highProgress)).toInt().toShort())
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
        oldEq.setBandLevel(bands[0], (maxAttenuation * curve * 0.1f).toInt().toShort())
        oldEq.setBandLevel(bands[1], midScoop)
        oldEq.setBandLevel(bands[2], midScoop)
        oldEq.setBandLevel(bands[3], highReduction)
        oldEq.setBandLevel(bands[4], highReduction)

        // --- Lógica para la canción que ENTRA (newEq) ---
        // Empieza con un sonido "filtrado" y termina en plano (natural).
        val initialMidScoop = (maxAttenuation * (1 - curve) * 0.8f).toInt().toShort()
        val initialHighReduction = (maxAttenuation * (1 - curve) * 0.4f).toInt().toShort()

        newEq.setBandLevel(bands[0], (maxAttenuation * (1 - curve) * 0.1f).toInt().toShort())
        newEq.setBandLevel(bands[1], initialMidScoop)
        newEq.setBandLevel(bands[2], initialMidScoop)
        newEq.setBandLevel(bands[3], initialHighReduction)
        newEq.setBandLevel(bands[4], initialHighReduction)
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
            oldEq.setBandLevel(bassBand, 0)
            midBands.forEach { band -> oldEq.setBandLevel(band, (EQ_MEDIUM_ATTENUATION * stageProgress * 0.5f).toInt().toShort()) }
            highBands.forEach { band -> oldEq.setBandLevel(band, (EQ_MEDIUM_ATTENUATION * stageProgress).toInt().toShort()) }

            // Canción que ENTRA (newEq):
            // Mantenemos su bajo COMPLETAMENTE CORTADO e introducimos sus medios y agudos.
            newEq.setBandLevel(bassBand, EQ_MAX_ATTENUATION)
            midBands.forEach { band -> newEq.setBandLevel(band, (EQ_MAX_ATTENUATION * (1 - stageProgress)).toInt().toShort()) }
            highBands.forEach { band -> newEq.setBandLevel(band, (EQ_MAX_ATTENUATION * (1 - stageProgress)).toInt().toShort()) }

            // FASE 2: Intercambiar los bajos (Bass Swap) (progreso 50% -> 100%)
        } else {
            // Calculamos el progreso dentro de esta fase (de 0.0 a 1.0)
            val stageProgress = (progress - 0.5f) / 0.5f

            // Canción que SALE (oldEq):
            // Ahora sí, cortamos su bajo y terminamos de eliminar los medios/agudos.
            oldEq.setBandLevel(bassBand, (EQ_MAX_ATTENUATION * stageProgress).toInt().toShort())
            midBands.forEach { band -> oldEq.setBandLevel(band, ((EQ_MEDIUM_ATTENUATION * 0.5f) + (EQ_MEDIUM_ATTENUATION * 0.5f * stageProgress)).toInt().toShort()) }
            highBands.forEach { band -> oldEq.setBandLevel(band, EQ_MEDIUM_ATTENUATION) } // Ya cortados

            // Canción que ENTRA (newEq):
            // Sus medios y agudos ya están presentes (planos). AHORA introducimos su bajo.
            newEq.setBandLevel(bassBand, (EQ_MAX_ATTENUATION * (1 - stageProgress)).toInt().toShort())
            midBands.forEach { band -> newEq.setBandLevel(band, 0) }
            highBands.forEach { band -> newEq.setBandLevel(band, 0) }
        }
    }

    private fun applyEnhancedStandardEq(oldEq: Equalizer, newEq: Equalizer, progress: Float, bands: ShortArray) {
        val enhancedCurve = 0.5f * (1 - cos(progress * Math.PI.toFloat()))
        val superSmoothCurve = enhancedCurve.pow(0.8f)

        bands.forEach { band ->
            // ✅ CORRECCIÓN APLICADA
            oldEq.setBandLevel(band, (EQ_MAX_ATTENUATION * superSmoothCurve).toInt().toShort())
            newEq.setBandLevel(band, (EQ_MAX_ATTENUATION * (1 - superSmoothCurve)).toInt().toShort())
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
            bassBands.forEach { oldEq.setBandLevel(it, FLAT) }
            midBands.forEach { oldEq.setBandLevel(it, FLAT) }
            highBands.forEach { oldEq.setBandLevel(it, FLAT) }

            // newEq: Entra solo con agudos (Graves y Medios cortados)
            bassBands.forEach { newEq.setBandLevel(it, MAX_ATT) }
            midBands.forEach { newEq.setBandLevel(it, MAX_ATT) }
            highBands.forEach { band ->
                newEq.setBandLevel(band, (MAX_ATT * (1 - stageProgress)).toInt().toShort())
            }
        }
        // Fase 2 (25% -> 50%): Intercambiar los bajos (Bass Swap)
        else if (progress < 0.5f) {
            val stageProgress = ((progress - 0.25f) / 0.25f)

            // oldEq: Empieza a perder sus bajos
            bassBands.forEach { band ->
                oldEq.setBandLevel(band, (MAX_ATT * stageProgress).toInt().toShort())
            }
            midBands.forEach { oldEq.setBandLevel(it, FLAT) }
            highBands.forEach { oldEq.setBandLevel(it, FLAT) }

            // newEq: Introduce sus bajos, mantiene agudos, medios siguen cortados
            bassBands.forEach { band ->
                newEq.setBandLevel(band, (MAX_ATT * (1 - stageProgress)).toInt().toShort())
            }
            midBands.forEach { newEq.setBandLevel(it, MAX_ATT) }
            highBands.forEach { newEq.setBandLevel(it, FLAT) } // Agudos ya entraron
        }
        // Fase 3 (50% -> 75%): Intercambiar los medios (Mid Swap)
        else if (progress < 0.75f) {
            val stageProgress = ((progress - 0.5f) / 0.25f)

            // oldEq: Bajos cortados, empieza a perder medios
            bassBands.forEach { oldEq.setBandLevel(it, MAX_ATT) }
            midBands.forEach { band ->
                oldEq.setBandLevel(band, (MAX_ATT * stageProgress).toInt().toShort())
            }
            highBands.forEach { oldEq.setBandLevel(it, FLAT) }

            // newEq: Bajos y agudos presentes, introduce medios
            bassBands.forEach { newEq.setBandLevel(it, FLAT) }
            midBands.forEach { band ->
                newEq.setBandLevel(band, (MAX_ATT * (1 - stageProgress)).toInt().toShort())
            }
            highBands.forEach { newEq.setBandLevel(it, FLAT) }
        }
        // Fase 4 (75% -> 100%): Limpiar agudos de la canción antigua
        else {
            val stageProgress = ((progress - 0.75f) / 0.25f)

            // oldEq: Bajos y medios cortados, pierde agudos
            bassBands.forEach { oldEq.setBandLevel(it, MAX_ATT) }
            midBands.forEach { oldEq.setBandLevel(it, MAX_ATT) }
            highBands.forEach { band ->
                oldEq.setBandLevel(band, (MAX_ATT * stageProgress).toInt().toShort())
            }

            // newEq: Todo presente (plano)
            bassBands.forEach { newEq.setBandLevel(it, FLAT) }
            midBands.forEach { newEq.setBandLevel(it, FLAT) }
            highBands.forEach { newEq.setBandLevel(it, FLAT) }
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

    private fun verifyPlayersState(oldPlayer: ExoPlayer, newPlayer: ExoPlayer): Boolean {
        return try {
            // [CAMBIO] Usamos la propiedad .isPlaying de ExoPlayer. La lógica es idéntica.
            val oldPlayerOk = oldPlayer.isPlaying || (!oldPlayer.isPlaying && isTransitioning)
            val newPlayerOk = newPlayer.isPlaying

            if (!newPlayerOk) {
                Log.w("IntelligentCrossfade", "⚠️ NewPlayer (ExoPlayer) no está reproduciendo, intentando recuperar")
                try {
                    // [CAMBIO] El equivalente a .start() en ExoPlayer es .play()
                    newPlayer.play()
                    // [MANTENIDO] Dar un respiro mínimo es una buena práctica de robustez.
                    Thread.sleep(10)
                } catch (e: Exception) {
                    Log.e("IntelligentCrossfade", "❌ No se pudo recuperar newPlayer (ExoPlayer)", e)
                    return false
                }
            }

            if (!oldPlayerOk && isTransitioning) {
                Log.w("IntelligentCrossfade", "⚠️ OldPlayer (ExoPlayer) dejó de reproducir durante transición")
                // [MANTENIDO] Tu comentario sobre que esto puede ser normal es correcto.
            }

            true
        } catch (e: Exception) {
            // [SIMPLIFICADO] Un único bloque catch es suficiente, ya que IllegalStateException es muy raro
            // al simplemente consultar el estado de ExoPlayer.
            Log.e("IntelligentCrossfade", "❌ Error inesperado verificando estado de ExoPlayers", e)
            false
        }
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
            eq.setBandLevel(band, 0) // Nivel plano
        }
    }

    private fun resetEqToCut(eq: Equalizer, bands: ShortArray) {
        bands.forEach { band ->
            eq.setBandLevel(band, EQ_MAX_ATTENUATION) // Nivel cortado
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

}