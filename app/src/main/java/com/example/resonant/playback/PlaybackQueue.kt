package com.example.resonant.playback

import com.example.resonant.data.models.Song

data class PlaybackQueue(
    val sourceId: String,
    val sourceType: QueueSource,
    var songs: List<Song>,
    var currentIndex: Int
)