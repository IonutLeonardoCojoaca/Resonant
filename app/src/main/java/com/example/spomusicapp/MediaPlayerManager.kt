package com.example.spomusicapp

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object MediaPlayerManager {
    private var mediaPlayer: MediaPlayer? = null
    private var currentSongUrl: String? = null
    var currentSongIndex: Int = -1

    fun play(context: Context,url: String,index: Int,autoStart: Boolean = true,onPrepared: (() -> Unit)? = null) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val file = Utils.cacheSongIfNeeded(context, url)

                // 💡 Aquí limpiamos archivos antiguos
                Utils.cleanOldCacheFiles(context)

                stop()
                mediaPlayer = MediaPlayer().apply {
                    setAudioStreamType(AudioManager.STREAM_MUSIC)
                    setDataSource(file.absolutePath)
                    setOnPreparedListener {
                        if (autoStart) {
                            it.start() // ✅ Solo empieza si autoStart = true
                        }
                        onPrepared?.invoke()
                    }
                    setOnCompletionListener {
                        PlaybackManager.playNext(context)
                    }
                    prepareAsync()
                }
                currentSongUrl = url
                currentSongIndex = index
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Error al reproducir canción", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun stop() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        currentSongUrl = null  // <-- Añade esto
    }

    fun initialize(context: Context) {
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer()
            mediaPlayer?.setOnCompletionListener {
                // Manejar el final de la canción si es necesario
                stop()
            }
        }
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

    fun getCurrentSongUrl(): String? {
        return currentSongUrl
    }


}
