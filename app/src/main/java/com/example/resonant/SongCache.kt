package com.example.resonant

object SongCache {
    var cachedSongs: List<Song> = emptyList()
    var hasMoreSongs: Boolean = true
    var currentOffset: Int = 0
}
