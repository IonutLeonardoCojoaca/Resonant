package com.example.spomusicapp

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer

object MediaPlayerManager {
    private var mediaPlayer: MediaPlayer? = null
    private var currentSongUrl: String? = null
    private var currentSongIndex: Int = -1

    fun play(context: Context, url: String, index: Int) {
        stop()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(url)
            setOnPreparedListener {
                it.start()

                // 🚀 Actualizar SeekBar y tiempo total
                if (context is ActivitySongList) {
                    context.seekBar.max = it.duration
                    context.totalTimeText.text = context.formatTime(it.duration)
                }
            }
            prepareAsync()
        }
        currentSongUrl = url
        currentSongIndex = index
    }

    fun stop() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    fun getCurrentPosition(): Int {
        return mediaPlayer?.currentPosition ?: 0
    }

    fun getDuration(): Int {
        return mediaPlayer?.duration ?: 0
    }

    fun seekTo(position: Int) {
        mediaPlayer?.seekTo(position)
    }

    fun isPlaying(): Boolean {
        return mediaPlayer?.isPlaying ?: false
    }

    fun pause() {
        mediaPlayer?.pause()
    }

    fun resume() {
        mediaPlayer?.start()
    }

    fun isCurrentSong(url: String): Boolean {
        return currentSongUrl == url
    }

}
