package com.example.spomusicapp

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.spomusicapp.MediaPlayerManager.isPlaying
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.lang.ref.WeakReference

object PlaybackManager {

    private var uiListeners = mutableListOf<WeakReference<PlaybackUIListener>>()
    var songs: List<Song> = emptyList()
    private var currentIndex = 0
    private var currentSong: Song? = null

    fun playSong(context: Context, song: Song, autoStart: Boolean = true) {

        if (currentSong?.url == song.url) {
            return
        }
        val newIndex = songs.indexOfFirst { it.url == song.url }
        if (newIndex != -1) {
            currentIndex = newIndex
        } else {
            songs = songs + song
            currentIndex = songs.size - 1
        }

        currentSong = song
        saveCurrentSongMetadata(context, song, currentIndex)

        val cachedFile = File(context.cacheDir, "cached_${song.title}.mp3")
        val dataSource = if (cachedFile.exists()) cachedFile.absolutePath else song.url

        notifySongChanged(song, isPlaying())

        MediaPlayerManager.play(
            context, dataSource, currentIndex, autoStart,
            onStreamStart = {
                incrementSongStreams(song.id)
            }
        )
    }

    fun playNext(context: Context) {
        if (songs.isNotEmpty()) {
            currentIndex = (currentIndex + 1) % songs.size
            val song = songs[currentIndex]
            saveCurrentSongMetadata(context, song, currentIndex)  // Aquí guardas en SharedPreferences
            MediaPlayerManager.play(
                context, song.url, currentIndex, autoStart = true,
                onPrepared = {
                    notifySongChanged(song, isPlaying())
                },
                onStreamStart = {
                    incrementSongStreams(song.id)
                }
            )
        }
    }

    fun playPrevious(context: Context) {
        if (songs.isNotEmpty()) {
            currentIndex = if (currentIndex - 1 < 0) songs.size - 1 else currentIndex - 1
            val song = songs[currentIndex]
            saveCurrentSongMetadata(context, song, currentIndex)  // Aquí guardas en SharedPreferences
            MediaPlayerManager.play(
                context, song.url, currentIndex, autoStart = true,
                onPrepared = {
                    notifySongChanged(song, isPlaying())
                },
                onStreamStart = {
                    incrementSongStreams(song.id)
                }
            )
        }
    }

    fun incrementSongStreams(songId: String) {
        val db = FirebaseFirestore.getInstance()
        val songRef = db.collection("songs").document(songId)

        db.runTransaction { transaction ->
            val snapshot = transaction.get(songRef)
            val currentStreams = snapshot.getLong("streams") ?: 0
            transaction.update(songRef, "streams", currentStreams + 1)
        }.addOnSuccessListener {
            Log.d("Firestore", "Reproducciones incrementadas correctamente.")
        }.addOnFailureListener { e ->
            Log.w("Firestore", "Error al incrementar reproducciones", e)
        }
    }

    fun saveCurrentSongMetadata(context: Context, song: Song, index: Int) {
        currentSong = song
        currentIndex = index

        val sharedPref = context.getSharedPreferences(PreferenceKeys.MUSIC_PREFERENCES, Context.MODE_PRIVATE)
        sharedPref.edit().apply {
            putString(PreferenceKeys.CURRENT_SONG_ID, song.id)
            putString(PreferenceKeys.CURRENT_SONG_URL, song.url)
            putString(PreferenceKeys.CURRENT_SONG_TITLE, song.title)
            putString(PreferenceKeys.CURRENT_SONG_ARTIST, song.artistName)
            putString(PreferenceKeys.CURRENT_SONG_ALBUM, song.albumName)
            putString(PreferenceKeys.CURRENT_SONG_DURATION, song.duration)
            putString(PreferenceKeys.CURRENT_SONG_COVER, song.localCoverPath)
            putBoolean(PreferenceKeys.CURRENT_ISPLAYING, true)
            putInt(PreferenceKeys.CURRENT_SONG_INDEX, index)
            apply()
        }
    }

    fun notifyPlaybackStateChanged() {
        val isPlaying = isPlaying()
        uiListeners.forEach { it.get()?.onPlaybackStateChanged(isPlaying) }
        uiListeners.removeAll { it.get() == null }
    }

    fun addUIListener(listener: PlaybackUIListener) {
        if (uiListeners.any { it.get() == listener }) return
        uiListeners.add(WeakReference(listener))
    }

    private fun notifySongChanged(song: Song, isPlaying: Boolean) {
        uiListeners.forEach { it.get()?.onSongChanged(song, isPlaying) }
        uiListeners.removeAll { it.get() == null }
    }

    fun clearUIListener() {
        uiListeners?.clear()
    }

    fun updateSongs(newSongs: List<Song>) {
        songs = newSongs
    }

    fun getCurrentSong(): Song? {
        return currentSong
    }

    fun setCurrentSong(song: Song) {
        currentSong = song
    }



}
