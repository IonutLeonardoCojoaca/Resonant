package com.example.spomusicapp

interface PlaybackUIListener {
    fun onSongChanged(song: Song, isPlaying: Boolean)
    fun onPlaybackStateChanged(isPlaying: Boolean)
}