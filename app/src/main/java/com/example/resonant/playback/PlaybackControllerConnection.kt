package com.example.resonant.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.example.resonant.services.MusicPlaybackService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Canal interno autenticado por Media3. Evita añadir nuevas acciones Intent a
 * un MediaLibraryService exportado para Android Auto.
 */
object PlaybackControllerConnection {
    private val lock = Any()

    @Volatile
    private var controllerFuture: ListenableFuture<MediaController>? = null

    suspend fun sendQueueCommand(
        context: Context,
        action: String,
        arguments: Bundle = Bundle.EMPTY
    ): SessionResult {
        require(action in QueueCommands.customActions) {
            "Comando de cola desconocido: $action"
        }
        val appContext = context.applicationContext
        val controller = getOrCreate(appContext).await(
            context = appContext,
            cancelFutureWithCaller = false
        )
        return controller.sendCustomCommand(
            SessionCommand(action, Bundle.EMPTY),
            arguments
        ).await(
            context = appContext,
            cancelFutureWithCaller = true
        )
    }

    fun release() {
        synchronized(lock) {
            controllerFuture?.let(MediaController::releaseFuture)
            controllerFuture = null
        }
    }

    private fun getOrCreate(context: Context): ListenableFuture<MediaController> {
        return controllerFuture ?: synchronized(lock) {
            controllerFuture ?: MediaController.Builder(
                context,
                SessionToken(
                    context,
                    ComponentName(context, MusicPlaybackService::class.java)
                )
            ).buildAsync().also { controllerFuture = it }
        }
    }

    private suspend fun <T> ListenableFuture<T>.await(
        context: Context,
        cancelFutureWithCaller: Boolean
    ): T {
        return suspendCancellableCoroutine { continuation ->
            addListener(
                {
                    runCatching(::get)
                        .onSuccess { continuation.resume(it) }
                        .onFailure { continuation.resumeWithException(it) }
                },
                ContextCompat.getMainExecutor(context)
            )
            if (cancelFutureWithCaller) {
                continuation.invokeOnCancellation { cancel(false) }
            }
        }
    }
}
