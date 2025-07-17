package com.example.resonant

interface PlaybackUIListener {
    fun onSongChanged(song: Song, isPlaying: Boolean)
    fun onPlaybackStateChanged(isPlaying: Boolean)
}