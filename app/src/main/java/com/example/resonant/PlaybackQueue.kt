package com.example.resonant

data class PlaybackQueue(
    val sourceId: String,
    val sourceType: QueueSource,
    var songs: List<Song>,
    var currentIndex: Int
)
