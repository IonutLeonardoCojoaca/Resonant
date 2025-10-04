package com.example.resonant

data class PlaybackQueue(
    val sourceId: String,
    val sourceType: QueueSource,
    val songs: List<Song>,
    var currentIndex: Int
)
