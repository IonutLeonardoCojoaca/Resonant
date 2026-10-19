package com.example.resonant.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

object QueueCommands {
    const val PLAY_NEXT = "com.resonant.queue.PLAY_NEXT"
    const val ADD = "com.resonant.queue.ADD"
    const val MOVE = "com.resonant.queue.MOVE"
    const val REMOVE = "com.resonant.queue.REMOVE"
    const val PLAY_INDEX = "com.resonant.queue.PLAY_INDEX"
    const val CLEAR_UPCOMING = "com.resonant.queue.CLEAR_UPCOMING"
    const val SHUFFLE_UPCOMING = "com.resonant.queue.SHUFFLE_UPCOMING"
    /** Quita de la cola las entradas Flow por delante afectadas por un feedback. */
    const val FLOW_FEEDBACK_APPLY = "com.resonant.queue.FLOW_FEEDBACK_APPLY"
    /** Devuelve a su sitio las entradas quitadas por el último FLOW_FEEDBACK_APPLY. */
    const val FLOW_FEEDBACK_UNDO = "com.resonant.queue.FLOW_FEEDBACK_UNDO"
    /** Añade una playlist o Playmix entera al final de la cola manual (collection-queue-offline). */
    const val ADD_COLLECTION = "com.resonant.queue.ADD_COLLECTION"
    /** Inserta una playlist o Playmix entera justo después de la canción actual. */
    const val PLAY_NEXT_COLLECTION = "com.resonant.queue.PLAY_NEXT_COLLECTION"

    const val ARG_SONG_ID = "song_id"
    const val ARG_RESOLVED_SONG = "resolved_song"
    const val ARG_FROM_INDEX = "from_index"
    const val ARG_TO_INDEX = "to_index"
    const val ARG_QUEUE_INDEX = "queue_index"
    const val ARG_EXPECTED_REVISION = "expected_revision"
    const val ARG_TARGET_TYPE = "target_type"
    const val ARG_TARGET_ID = "target_id"
    /** `playlist` o `playmix`. */
    const val ARG_COLLECTION_TYPE = "collection_type"
    const val ARG_COLLECTION_ID = "collection_id"
    const val COLLECTION_PLAYLIST = "playlist"
    const val COLLECTION_PLAYMIX = "playmix"
    /** Número de canciones añadidas (resultado de los comandos de colección). */
    const val RESULT_ADDED_COUNT = "added_count"
    const val RESULT_MESSAGE = "message"

    val customActions = setOf(
        PLAY_NEXT,
        ADD,
        MOVE,
        REMOVE,
        PLAY_INDEX,
        CLEAR_UPCOMING,
        SHUFFLE_UPCOMING,
        FLOW_FEEDBACK_APPLY,
        FLOW_FEEDBACK_UNDO,
        ADD_COLLECTION,
        PLAY_NEXT_COLLECTION
    )

    val sessionCommands: List<SessionCommand> = customActions.map {
        SessionCommand(it, Bundle.EMPTY)
    }
}
