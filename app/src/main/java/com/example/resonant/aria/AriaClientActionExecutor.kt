package com.example.resonant.aria

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.session.SessionResult
import com.example.resonant.managers.FavoriteManager
import com.example.resonant.managers.SongManager
import com.example.resonant.playback.PlaybackControllerConnection
import com.example.resonant.playback.QueueCommands
import com.example.resonant.ui.viewmodels.AriaAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Executes Aria effects at Activity level so they work from every screen. */
class AriaClientActionExecutor(
    context: Context,
    private val scope: CoroutineScope,
    private val onFeedback: (message: String, success: Boolean) -> Unit
) {
    private val appContext = context.applicationContext

    fun execute(action: AriaAction) {
        when (action.type) {
            "controlar_reproduccion" -> executePlaybackAction(action)
            "guardar_actual" -> addCurrentSongToFavorites(action)
        }
    }

    private fun executePlaybackAction(action: AriaAction) {
        when (action.playbackCommand) {
            "play", "pause", "next", "previous" -> scope.launch {
                runCatching {
                    PlaybackControllerConnection.withController(appContext) { controller ->
                        when (action.playbackCommand) {
                            "play" -> controller.play()
                            "pause" -> controller.pause()
                            "next" -> controller.seekToNextMediaItem()
                            "previous" -> controller.seekToPreviousMediaItem()
                        }
                    }
                }.onFailure {
                    Log.e(TAG, "Could not execute playback action", it)
                    onFeedback("No pude controlar la reproducción", false)
                }
            }

            "queue_song", "play_song" -> resolveAndPlaySong(action)
        }
    }

    private fun resolveAndPlaySong(action: AriaAction) {
        val title = action.clientSongTitle?.trim().takeIf { !it.isNullOrEmpty() }
        if (title == null) {
            onFeedback("Necesito el nombre de la canción", false)
            return
        }

        scope.launch {
            val query = listOfNotNull(
                title,
                action.clientSongArtist?.trim()?.takeIf { it.isNotEmpty() }
            ).joinToString(" ")
            val song = withContext(Dispatchers.IO) {
                SongManager(appContext).searchSongs(query, limit = 10).results.firstOrNull()
            }
            if (song == null) {
                onFeedback("No encontré esa canción en Resonant", false)
                return@launch
            }

            runCatching {
                if (action.playbackCommand == "queue_song") {
                    val result = PlaybackControllerConnection.sendQueueCommand(
                        context = appContext,
                        action = QueueCommands.ADD,
                        arguments = Bundle().apply {
                            putString(QueueCommands.ARG_SONG_ID, song.id)
                        }
                    )
                    check(result.resultCode == SessionResult.RESULT_SUCCESS) {
                        result.extras.getString(QueueCommands.RESULT_MESSAGE)
                            ?: "No se pudo añadir a la cola"
                    }
                } else {
                    PlaybackControllerConnection.withController(appContext) { controller ->
                        controller.setMediaItem(MediaItem.Builder().setMediaId(song.id).build())
                        controller.prepare()
                        controller.play()
                    }
                }
            }.onSuccess {
                if (action.playbackCommand == "queue_song") {
                    onFeedback("${song.title} añadida a la cola", true)
                }
            }.onFailure {
                Log.e(TAG, "Could not execute song playback action", it)
                onFeedback(
                    if (action.playbackCommand == "queue_song") {
                        "No pude añadir esa canción a la cola"
                    } else {
                        "No pude reproducir esa canción"
                    },
                    false
                )
            }
        }
    }

    private fun addCurrentSongToFavorites(action: AriaAction) {
        val songId = action.clientSongId?.trim().takeIf { !it.isNullOrEmpty() }
        if (songId == null) {
            onFeedback("No sé qué canción guardar", false)
            return
        }

        scope.launch {
            val success = withContext(Dispatchers.IO) {
                FavoriteManager(appContext).addFavoriteSong(songId)
            }
            if (success) {
                onFeedback(
                    action.clientSongTitle?.let { "$it añadida a favoritos" }
                        ?: "Canción añadida a favoritos",
                    true
                )
            } else {
                onFeedback("No pude añadir la canción a favoritos", false)
            }
        }
    }

    private companion object {
        const val TAG = "AriaActionExecutor"
    }
}
