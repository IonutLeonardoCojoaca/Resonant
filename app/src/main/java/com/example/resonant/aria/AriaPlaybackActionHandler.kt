package com.example.resonant.aria

import com.example.resonant.ui.viewmodels.AriaAction
import kotlinx.coroutines.CancellationException
import java.util.Locale
import java.util.UUID

const val ARIA_PENDING_CLIENT_STATUS = "pending_client"

fun AriaAction.isPendingClientAction(): Boolean =
    clientSide && executionStatus == ARIA_PENDING_CLIENT_STATUS

enum class AriaTransportControl {
    PLAY,
    PAUSE,
    NEXT,
    PREVIOUS,
    SHUFFLE,
    REPEAT
}

interface AriaPlaybackGateway {
    suspend fun control(command: AriaTransportControl)
    suspend fun playSong(songId: String, title: String?, artist: String?)
    suspend fun queueSong(songId: String)
    suspend fun playArtistEssentials(artistId: String, artistName: String?)
    suspend fun playArtistRadio(artistId: String, artistName: String?)
}

interface AriaFavoriteGateway {
    suspend fun addFavoriteSong(songId: String): Boolean
    suspend fun removeFavoriteSong(songId: String): Boolean
}

fun interface AriaActionIdempotency {
    /** Atomically returns true only for the first occurrence of a non-blank action_id. */
    fun tryClaim(actionId: String?): Boolean
}

fun interface AriaActionTelemetry {
    fun record(event: AriaActionTelemetryEvent)
}

data class AriaActionTelemetryEvent(
    val command: String,
    val outcome: String,
    val reason: String? = null,
    val songId: String? = null,
    val selectionMode: String? = null,
    val actionId: String? = null,
    val logId: String? = null
)

/**
 * Pure orchestration for Aria playback actions. The authoritative song id is passed directly to
 * Media3/QueueCommands; title and artist are display metadata only and are never used to search.
 */
class AriaPlaybackActionHandler(
    private val playbackGateway: AriaPlaybackGateway,
    private val idempotency: AriaActionIdempotency,
    private val telemetry: AriaActionTelemetry,
    private val onFeedback: (message: String, success: Boolean) -> Unit
) {
    suspend fun execute(action: AriaAction) {
        if (action.type != PLAYBACK_ACTION_TYPE || !action.isPendingClientAction()) return

        val command = action.playbackCommand
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
            ?: return

        if (!idempotency.tryClaim(action.actionId)) {
            record(action, command, OUTCOME_DUPLICATE)
            return
        }

        when (command) {
            "play" -> executeControl(action, command, AriaTransportControl.PLAY)
            "pause" -> executeControl(action, command, AriaTransportControl.PAUSE)
            "next" -> executeControl(action, command, AriaTransportControl.NEXT)
            "previous" -> executeControl(action, command, AriaTransportControl.PREVIOUS)
            "shuffle", "aleatorio" -> executeControl(action, command, AriaTransportControl.SHUFFLE)
            "repeat", "loop", "bucle" -> executeControl(action, command, AriaTransportControl.REPEAT)
            "play_song", "queue_song" -> executeSongAction(action, command)
            "play_artist_essentials", "play_artist_radio" -> executeArtistAction(action, command)
            else -> record(action, command, OUTCOME_IGNORED, REASON_UNSUPPORTED_COMMAND)
        }
    }

    private suspend fun executeControl(
        action: AriaAction,
        commandName: String,
        command: AriaTransportControl
    ) {
        try {
            playbackGateway.control(command)
            record(action, commandName, OUTCOME_SUCCESS)
            onFeedback(controlSuccessMessage(command), true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            record(action, commandName, OUTCOME_ERROR, REASON_CONTROL_FAILED)
            onFeedback(controlFailureMessage(command), false)
        }
    }

    private suspend fun executeSongAction(action: AriaAction, command: String) {
        val songId = action.clientSongId?.trim()?.takeIf { it.isNotEmpty() }
        if (songId == null) {
            fail(action, command, REASON_MISSING_SONG_ID, missingSongMessage(command))
            return
        }
        if (!isValidUuid(songId)) {
            fail(action, command, REASON_INVALID_SONG_ID, invalidSongMessage(command), songId)
            return
        }

        val title = action.clientSongTitle?.trim()?.takeIf { it.isNotEmpty() }
            ?: action.song?.title?.trim()?.takeIf { it.isNotEmpty() }
        val displayTitle = title ?: "la canción"

        try {
            if (command == "queue_song") {
                playbackGateway.queueSong(songId)
                onFeedback("${title ?: "Canción"} añadida a la cola", true)
            } else {
                playbackGateway.playSong(
                    songId = songId,
                    title = title,
                    artist = action.clientSongArtist
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: action.song?.artistNames
                )
                onFeedback("Reproduciendo $displayTitle", true)
            }
            record(action, command, OUTCOME_SUCCESS, songId = songId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(
                action = action,
                command = command,
                reason = if (command == "queue_song") {
                    REASON_QUEUE_COMMAND_FAILED
                } else {
                    REASON_PLAYBACK_START_FAILED
                },
                message = if (command == "queue_song") {
                    "No pude añadir $displayTitle a la cola"
                } else {
                    "No pude reproducir $displayTitle"
                },
                songId = songId
            )
        }
    }

    private suspend fun executeArtistAction(action: AriaAction, command: String) {
        val artistId = action.clientArtistId?.trim()?.takeIf { it.isNotEmpty() }
        if (artistId == null) {
            fail(action, command, REASON_MISSING_SONG_ID, "No pude identificar al artista")
            return
        }
        if (!isValidUuid(artistId)) {
            fail(action, command, REASON_INVALID_SONG_ID, "ID de artista inválido", artistId)
            return
        }

        val artistName = action.clientArtistName?.trim()?.takeIf { it.isNotEmpty() }
        val displayArtist = artistName ?: "el artista"

        try {
            if (command == "play_artist_essentials") {
                playbackGateway.playArtistEssentials(artistId, artistName)
                onFeedback("Reproduciendo imprescindibles de $displayArtist", true)
            } else {
                playbackGateway.playArtistRadio(artistId, artistName)
                onFeedback("Reproduciendo la radio de $displayArtist", true)
            }
            record(action, command, OUTCOME_SUCCESS, songId = artistId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(
                action = action,
                command = command,
                reason = REASON_PLAYBACK_START_FAILED,
                message = "No pude reproducir la música de $displayArtist",
                songId = artistId
            )
        }
    }

    private fun fail(
        action: AriaAction,
        command: String,
        reason: String,
        message: String,
        songId: String? = action.clientSongId
    ) {
        record(action, command, OUTCOME_ERROR, reason, songId)
        onFeedback(message, false)
    }

    private fun record(
        action: AriaAction,
        command: String,
        outcome: String,
        reason: String? = null,
        songId: String? = action.clientSongId
    ) {
        telemetry.record(
            AriaActionTelemetryEvent(
                command = command,
                outcome = outcome,
                reason = reason,
                songId = songId,
                selectionMode = action.selectionMode ?: action.song?.selectionMode,
                actionId = action.actionId,
                logId = action.logId
            )
        )
    }

    private fun isValidUuid(value: String): Boolean =
        UUID_PATTERN.matches(value) && runCatching { UUID.fromString(value) }.isSuccess

    private fun controlSuccessMessage(command: AriaTransportControl): String = when (command) {
        AriaTransportControl.PLAY -> "Reproducción reanudada"
        AriaTransportControl.PAUSE -> "Pausa aplicada"
        AriaTransportControl.NEXT -> "Reproduciendo la siguiente canción"
        AriaTransportControl.PREVIOUS -> "Reproduciendo la canción anterior"
        AriaTransportControl.SHUFFLE -> "Orden aleatorio alternado"
        AriaTransportControl.REPEAT -> "Repetición alternada"
    }

    private fun controlFailureMessage(command: AriaTransportControl): String = when (command) {
        AriaTransportControl.PLAY -> "No pude reanudar la reproducción"
        AriaTransportControl.PAUSE -> "No pude aplicar la pausa"
        AriaTransportControl.NEXT -> "No pude pasar a la siguiente canción"
        AriaTransportControl.PREVIOUS -> "No pude volver a la canción anterior"
        AriaTransportControl.SHUFFLE -> "No pude cambiar el orden aleatorio"
        AriaTransportControl.REPEAT -> "No pude cambiar la repetición"
    }

    private fun missingSongMessage(command: String): String =
        if (command == "queue_song") "Falta la canción que debo encolar"
        else "Falta la canción que debo reproducir"

    private fun invalidSongMessage(command: String): String =
        if (command == "queue_song") "No pude añadir esa canción a la cola"
        else "No pude reproducir esa canción"

    companion object {
        private const val PLAYBACK_ACTION_TYPE = "controlar_reproduccion"
        private const val OUTCOME_SUCCESS = "success"
        private const val OUTCOME_ERROR = "error"
        private const val OUTCOME_DUPLICATE = "duplicate"
        private const val OUTCOME_IGNORED = "ignored"

        const val REASON_MISSING_SONG_ID = "missing_song_id"
        const val REASON_INVALID_SONG_ID = "invalid_song_id"
        const val REASON_PLAYBACK_START_FAILED = "playback_start_failed"
        const val REASON_QUEUE_COMMAND_FAILED = "queue_command_failed"
        const val REASON_CONTROL_FAILED = "control_failed"
        const val REASON_UNSUPPORTED_COMMAND = "unsupported_command"

        private val UUID_PATTERN = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
                "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        )
    }
}

class AriaFavoriteActionHandler(
    private val favoriteGateway: AriaFavoriteGateway,
    private val idempotency: AriaActionIdempotency,
    private val onFeedback: (message: String, success: Boolean) -> Unit
) {
    suspend fun execute(action: AriaAction) {
        if (action.type != FAVORITE_ACTION_TYPE && action.type != REMOVE_FAVORITE_ACTION_TYPE) return
        if (!idempotency.tryClaim(action.actionId)) return

        val parsedId = (action.songId ?: action.clientSongId)?.trim()?.takeIf { it.isNotEmpty() }
        val songId = parsedId ?: com.example.resonant.playback.PlaybackStateRepository.currentSong?.id
        
        if (songId == null) {
            val actionName = if (action.type == FAVORITE_ACTION_TYPE) "guardar" else "quitar"
            onFeedback("No pude $actionName la canción (no hay canción sonando)", false)
            return
        }

        val success = try {
            if (action.type == FAVORITE_ACTION_TYPE) {
                favoriteGateway.addFavoriteSong(songId)
            } else {
                favoriteGateway.removeFavoriteSong(songId)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            false
        }

        val parsedTitle = (action.song?.title ?: action.clientSongTitle)?.trim()?.takeIf { it.isNotEmpty() }
        val title = parsedTitle ?: com.example.resonant.playback.PlaybackStateRepository.currentSong?.title

        if (success) {
            if (action.type == FAVORITE_ACTION_TYPE) {
                onFeedback(
                    title?.let { "$it añadida a favoritos" }
                        ?: "Canción añadida a favoritos",
                    true
                )
            } else {
                onFeedback(
                    title?.let { "$it quitada de favoritos" }
                        ?: "Canción quitada de favoritos",
                    true
                )
            }
        } else {
            if (action.type == FAVORITE_ACTION_TYPE) {
                onFeedback("No pude añadir la canción a favoritos", false)
            } else {
                onFeedback("No pude quitar la canción de favoritos", false)
            }
        }
    }

    private companion object {
        const val FAVORITE_ACTION_TYPE = "guardar_actual"
        const val REMOVE_FAVORITE_ACTION_TYPE = "quitar_actual"
    }
}
