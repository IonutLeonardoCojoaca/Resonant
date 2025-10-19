package com.example.resonant

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log

class MediaSessionManager(
    private val context: Context,
    private val playerController: PlayerController // Depende de la interfaz, no de la clase concreta
) {
    val mediaSession: MediaSessionCompat

    val sessionToken: MediaSessionCompat.Token
        get() = mediaSession.sessionToken

    init {
        mediaSession = MediaSessionCompat(context, "MusicPlaybackService").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { playerController.resume() }
                override fun onPause() { playerController.pause() }
                override fun onSkipToNext() { playerController.playNext() }
                override fun onSkipToPrevious() { playerController.playPrevious() }
                override fun onStop() { playerController.stop() }
                override fun onSeekTo(pos: Long) { playerController.seekTo(pos) }
            })

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("fromNotification", true)
            }
            val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            setSessionActivity(pendingIntent)
            isActive = true
        }
    }

    fun updatePlaybackState(currentPosition: Long) {
        val state = if (PlaybackStateRepository.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val actions = (PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO)

        val pbState = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, currentPosition, 1.0f, SystemClock.elapsedRealtime())
            .build()
        mediaSession.setPlaybackState(pbState)
        Log.d("MediaSessionManager", "PlaybackState actualizado - Estado: $state, Posición: $currentPosition")
    }

    fun updateMetadata(song: Song?, albumArt: Bitmap?, duration: Long) {
        if (song == null) {
            mediaSession.setMetadata(null)
            return
        }

        Log.d("MediaSessionManager", "🎵 Actualizando metadata para: ${song.title}")
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title ?: "Título desconocido")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artistName ?: "Artista desconocido")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, song.id ?: "")

        val art = albumArt ?: BitmapFactory.decodeResource(context.resources, R.drawable.s_resonant_white)
        metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)

        mediaSession.setMetadata(metadataBuilder.build())
        Log.d("MediaSessionManager", "✅ Metadata actualizada.")
    }

    fun release() {
        mediaSession.isActive = false
        mediaSession.release()
    }
}