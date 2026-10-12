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

    const val ARG_SONG_ID = "song_id"
    const val ARG_RESOLVED_SONG = "resolved_song"
    const val ARG_FROM_INDEX = "from_index"
    const val ARG_TO_INDEX = "to_index"
    const val ARG_QUEUE_INDEX = "queue_index"
    const val ARG_EXPECTED_REVISION = "expected_revision"
    const val ARG_TARGET_TYPE = "target_type"
    const val ARG_TARGET_ID = "target_id"
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
        FLOW_FEEDBACK_UNDO
    )

    val sessionCommands: List<SessionCommand> = customActions.map {
        SessionCommand(it, Bundle.EMPTY)
    }
}
