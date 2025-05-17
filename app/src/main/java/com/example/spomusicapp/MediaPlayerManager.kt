package com.example.spomusicapp

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object MediaPlayerManager {

    private var isCompletionListenerEnabled = true

    private var mediaPlayer: MediaPlayer? = null
    private var currentSongUrl: String? = null
    var currentSongIndex: Int = -1

    fun play(context: Context, url: String, index: Int, autoStart: Boolean = true, onPrepared: (() -> Unit)? = null) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val file = Utils.cacheSongIfNeeded(context, url)
                Utils.cleanOldCacheFiles(context)

                isCompletionListenerEnabled = false

                mediaPlayer?.apply {
                    stop()
                    reset()
                    release()
                }
                mediaPlayer = null

                mediaPlayer = MediaPlayer().apply {
                    setAudioStreamType(AudioManager.STREAM_MUSIC)
                    setDataSource(file.absolutePath)
                    setOnPreparedListener {
                        isCompletionListenerEnabled = true // Sólo aquí activamos el listener
                        if (autoStart) {
                            it.start()
                        }
                        onPrepared?.invoke()
                    }
                    setOnCompletionListener {
                        Log.i("MediaPlayerManager", "onCompletionListener triggered for $url")
                        if (isCompletionListenerEnabled) {
                            PlaybackManager.playNext(context)
                        } else {
                            Log.i("MediaPlayerManager", "onCompletionListener ignored due to flag")
                        }
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
        // Deshabilitamos el listener para que no se ejecute al parar el reproductor
        isCompletionListenerEnabled = false

        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        currentSongUrl = null

        // Volvemos a habilitar el listener para la próxima canción que se reproduzca normalmente
        isCompletionListenerEnabled = true
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
