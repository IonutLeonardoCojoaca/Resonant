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

    const val ARG_SONG_ID = "song_id"
    const val ARG_RESOLVED_SONG = "resolved_song"
    const val ARG_FROM_INDEX = "from_index"
    const val ARG_TO_INDEX = "to_index"
    const val ARG_QUEUE_INDEX = "queue_index"
    const val ARG_EXPECTED_REVISION = "expected_revision"
    const val RESULT_MESSAGE = "message"

    val customActions = setOf(
        PLAY_NEXT,
        ADD,
        MOVE,
        REMOVE,
        PLAY_INDEX,
        CLEAR_UPCOMING,
        SHUFFLE_UPCOMING
    )

    val sessionCommands: List<SessionCommand> = customActions.map {
        SessionCommand(it, Bundle.EMPTY)
    }
}
