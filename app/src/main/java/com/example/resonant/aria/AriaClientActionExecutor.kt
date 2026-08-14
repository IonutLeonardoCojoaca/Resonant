package com.example.resonant.aria

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.SessionResult
import com.example.resonant.playback.PlaybackControllerConnection
import com.example.resonant.playback.QueueCommands
import com.example.resonant.ui.viewmodels.AriaAction
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Executes pending Aria effects at Activity level so they work from every screen. */
class AriaClientActionExecutor(
    context: Context,
    private val scope: CoroutineScope,
    private val onFeedback: (message: String, success: Boolean) -> Unit
) {
    private val appContext = context.applicationContext
    private val idempotency = PersistentAriaActionIdempotency(appContext)
    private val playbackHandler = AriaPlaybackActionHandler(
        playbackGateway = AndroidAriaPlaybackGateway(appContext),
        idempotency = idempotency,
        telemetry = FirebaseAriaActionTelemetry(appContext),
        onFeedback = onFeedback
    )
    fun execute(action: AriaAction) {
        if (action.type != "controlar_reproduccion" || !action.isPendingClientAction()) return
        scope.launch { playbackHandler.execute(action) }
    }
}

/** Bridges Aria to Media3 using only the authoritative song_id supplied by the backend. */
private class AndroidAriaPlaybackGateway(context: Context) : AriaPlaybackGateway {
    private val appContext = context.applicationContext

    override suspend fun control(command: AriaTransportControl) {
        PlaybackControllerConnection.withController(appContext) { controller ->
            val requiredCommand = when (command) {
                AriaTransportControl.PLAY,
                AriaTransportControl.PAUSE -> Player.COMMAND_PLAY_PAUSE
                AriaTransportControl.NEXT -> Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
                AriaTransportControl.PREVIOUS -> Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
            }
            check(controller.isCommandAvailable(requiredCommand)) {
                "MediaSession no permite ejecutar el control solicitado"
            }
            when (command) {
                AriaTransportControl.PLAY -> controller.play()
                AriaTransportControl.PAUSE -> controller.pause()
                AriaTransportControl.NEXT -> controller.seekToNextMediaItem()
                AriaTransportControl.PREVIOUS -> controller.seekToPreviousMediaItem()
            }
        }
    }

    override suspend fun playSong(songId: String, title: String?, artist: String?) {
        PlaybackControllerConnection.withController(appContext) { controller ->
            check(controller.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM)) {
                "MediaSession no permite cambiar la canción"
            }
            check(controller.isCommandAvailable(Player.COMMAND_PREPARE)) {
                "MediaSession no permite preparar la canción"
            }
            check(controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
                "MediaSession no permite iniciar la reproducción"
            }
            val mediaItem = MediaItem.Builder()
                .setMediaId(songId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setIsPlayable(true)
                        .build()
                )
                .build()
            controller.setMediaItem(mediaItem)
            controller.prepare()
            controller.play()
        }
    }

    override suspend fun queueSong(songId: String) {
        val result = PlaybackControllerConnection.sendQueueCommand(
            context = appContext,
            action = QueueCommands.ADD,
            arguments = Bundle().apply {
                putString(QueueCommands.ARG_SONG_ID, songId)
            }
        )
        check(result.resultCode == SessionResult.RESULT_SUCCESS) {
            result.extras.getString(QueueCommands.RESULT_MESSAGE)
                ?: "No se pudo añadir a la cola"
        }
    }
}

/** Persists recent action_id values across SSE reconnects and Activity recreation. */
private class PersistentAriaActionIdempotency(context: Context) : AriaActionIdempotency {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun tryClaim(actionId: String?): Boolean {
        val normalized = actionId?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        val key = CLAIM_PREFIX + normalized
        synchronized(lock) {
            if (preferences.contains(key)) return false

            val previousClaims = preferences.all
                .asSequence()
                .filter { (storedKey, value) ->
                    storedKey.startsWith(CLAIM_PREFIX) && value is Long
                }
                .sortedBy { (_, value) -> value as Long }
                .toList()
            val editor = preferences.edit()
            if (previousClaims.size >= MAX_PERSISTED_IDS) {
                previousClaims
                    .take(previousClaims.size - MAX_PERSISTED_IDS + 1)
                    .forEach { (storedKey, _) -> editor.remove(storedKey) }
            }
            editor.putLong(key, System.currentTimeMillis()).apply()
            return true
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "aria_action_idempotency"
        const val CLAIM_PREFIX = "action_id_"
        const val MAX_PERSISTED_IDS = 200
        val lock = Any()
    }
}

private class FirebaseAriaActionTelemetry(context: Context) : AriaActionTelemetry {
    private val analytics = FirebaseAnalytics.getInstance(context.applicationContext)

    override fun record(event: AriaActionTelemetryEvent) {
        if (event.outcome == "error") {
            Log.w(
                TAG,
                "Aria action failed command=${event.command} reason=${event.reason} " +
                    "songId=${event.songId} actionId=${event.actionId}"
            )
        }
        analytics.logEvent(EVENT_NAME, Bundle().apply {
            putString("command", event.command.take(MAX_VALUE_LENGTH))
            putString("outcome", event.outcome.take(MAX_VALUE_LENGTH))
            event.reason?.let { putString("reason", it.take(MAX_VALUE_LENGTH)) }
            event.songId?.let { putString("song_id", it.take(MAX_VALUE_LENGTH)) }
            event.selectionMode?.let {
                putString("selection_mode", it.take(MAX_VALUE_LENGTH))
            }
            event.actionId?.let { putString("action_id", it.take(MAX_VALUE_LENGTH)) }
            event.logId?.let { putString("log_id", it.take(MAX_VALUE_LENGTH)) }
        })
    }

    private companion object {
        const val TAG = "AriaActionExecutor"
        const val EVENT_NAME = "aria_playback_action"
        const val MAX_VALUE_LENGTH = 100
    }
}
