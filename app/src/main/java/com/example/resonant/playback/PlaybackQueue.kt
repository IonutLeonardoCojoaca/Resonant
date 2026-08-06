package com.example.resonant.playback

import com.example.resonant.data.models.Song
import java.util.UUID

data class PlaybackQueue(
    var sourceId: String,
    var sourceType: QueueSource,
    var songs: List<Song>,
    var currentIndex: Int,
    var entryIds: List<String> = songs.map { UUID.randomUUID().toString() }
) {
    fun resetEntriesForSongs() {
        entryIds = songs.map { UUID.randomUUID().toString() }
    }

    fun ensureEntryIds() {
        if (entryIds.size != songs.size || entryIds.any(String::isBlank)) {
            resetEntriesForSongs()
        }
    }
}
