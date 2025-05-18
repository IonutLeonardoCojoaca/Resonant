package com.example.spomusicapp

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.example.spomusicapp.MediaPlayerManager.isPlaying
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.lang.ref.WeakReference

object PlaybackManager {

    private var uiListeners = mutableListOf<WeakReference<PlaybackUIListener>>()
    var songs: List<Song> = emptyList()
    private var currentIndex = 0

    fun playSongAt(context: Context, index: Int, autoStart: Boolean = true) {
        if (index in songs.indices) {
            currentIndex = index
            val song = songs[index]
            saveCurrentSongMetadata(context, song, currentIndex)
            val cachedFile = File(context.cacheDir, "cached_${song.title}.mp3")
            val dataSource = if (cachedFile.exists()) {
                cachedFile.absolutePath
            } else {
                song.url
            }
            notifySongChanged(song, isPlaying())
            MediaPlayerManager.play(context, dataSource, index, autoStart)
            Log.i("id stream", song.id)
            incrementSongStreams(song.id)
        }
        Log.i("PlaybackManager", "Índice actual después: $currentIndex")
    }

    fun playNext(context: Context) {
        if (songs.isNotEmpty()) {
            currentIndex = (currentIndex + 1) % songs.size
            val song = songs[currentIndex]
            saveCurrentSongMetadata(context, song, currentIndex)
            MediaPlayerManager.play(context, song.url, currentIndex, autoStart = true) {
                saveCurrentSongMetadata(context, song, currentIndex)
                notifySongChanged(song, isPlaying())
            }
        }
    }

    fun playPrevious(context: Context) {
        if (songs.isNotEmpty()) {
            currentIndex = if (currentIndex - 1 < 0) songs.size - 1 else currentIndex - 1
            val song = songs[currentIndex]
            saveCurrentSongMetadata(context, song, currentIndex)
            MediaPlayerManager.play(context, song.url, currentIndex, autoStart = true) {
                saveCurrentSongMetadata(context, song, currentIndex)
                notifySongChanged(song, isPlaying())
            }
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

    fun updateSongs(songList: List<Song>) {
        songs = emptyList()
        songs = songList
    }

    private fun saveCurrentSongMetadata(context: Context, song: Song, index: Int) {
        val sharedPref = context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        sharedPref.edit {
            putInt("current_playing_index", currentIndex)
            putString("current_playing_url", song.url)
            putString("current_playing_title", song.title)
            putString("current_playing_artist", song.artistName)
            putString("current_playing_album", song.albumName)
            putString("current_playing_duration", song.duration)
            putInt("current_index", index) // <-- Guarda el índice
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

    fun notifySongChanged(song: Song) {
        notifySongChanged(song, isPlaying())
    }

    fun getCurrentSongIndex(): Int {
        return currentIndex
    }

    fun getCurrentSong(): Song? {
        return if (currentIndex in songs.indices) songs[currentIndex] else null
    }

}
