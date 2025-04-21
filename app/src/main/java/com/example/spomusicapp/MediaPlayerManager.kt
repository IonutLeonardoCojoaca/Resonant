package com.example.spomusicapp

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer

object MediaPlayerManager {
    private var mediaPlayer: MediaPlayer? = null

    fun play(context: Context, url: String) {
        // Si ya está reproduciendo, lo paramos
        mediaPlayer?.release()
        mediaPlayer = null

        mediaPlayer = MediaPlayer().apply {
            setDataSource(url)
            setAudioStreamType(AudioManager.STREAM_MUSIC)
            setOnPreparedListener {
                start()
            }
            setOnCompletionListener {
                release()
                mediaPlayer = null
            }
            prepareAsync()
        }
    }

    fun stop() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
