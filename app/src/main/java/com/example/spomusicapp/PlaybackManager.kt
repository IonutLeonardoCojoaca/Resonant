package com.example.spomusicapp

import android.content.Context
import androidx.core.content.edit
import java.io.File

object PlaybackManager {

    var songs: List<Song> = emptyList()
    private var currentIndex = 0

    var onSongChanged: ((Song) -> Unit)? = null

    fun updateSongs(songList: List<Song>) {
        songs = songList
    }

    fun playSongAt(context: Context, index: Int) {
        if (index in songs.indices) {
            currentIndex = index
            val song = songs[index]

            val sharedPref = context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
            sharedPref.edit() {
                putInt("current_playing_index", index)
                putString("current_playing_url", song.url)
                putString("current_playing_title", song.title)
                putString("current_playing_artist", song.artist)
                putString("current_playing_album", song.album)
                putString("current_playing_duration", song.duration)
            }

            val cachedFile = File(context.cacheDir, "cached_${song.title}.mp3")
            val dataSource = if (cachedFile.exists()) {
                cachedFile.absolutePath
            } else {
                song.url
            }

            MediaPlayerManager.play(context, dataSource, index)
        }
    }

    fun playNext(context: Context) {
        if (songs.isNotEmpty()) {
            currentIndex = (currentIndex + 1) % songs.size
            val song = songs[currentIndex]
            MediaPlayerManager.play(context, song.url, currentIndex)

            // Guardamos los metadatos de la canción actual en SharedPreferences
            val sharedPref = context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
            sharedPref.edit() {
                putString("current_playing_url", song.url)
                putString("current_playing_title", song.title)
                putString("current_playing_artist", song.artist)
                putString("current_playing_album", song.album)
                putString("current_playing_duration", song.duration)
            }

            onSongChanged?.invoke(song)
        }
    }

    fun playPrevious(context: Context) {
        if (songs.isNotEmpty()) {
            currentIndex = if (currentIndex - 1 < 0) songs.size - 1 else currentIndex - 1
            val song = songs[currentIndex]
            MediaPlayerManager.play(context, song.url, currentIndex)

            // Guardamos los metadatos de la canción actual en SharedPreferences
            val sharedPref = context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
            sharedPref.edit() {
                putString("current_playing_url", song.url)
                putString("current_playing_title", song.title)
                putString("current_playing_artist", song.artist)
                putString("current_playing_album", song.album)
                putString("current_playing_duration", song.duration)
            }

            onSongChanged?.invoke(song)
        }
    }


    fun setCurrentSong(context: Context) {
        val sharedPref = context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        val url = sharedPref.getString("current_playing_url", null)
        val title = sharedPref.getString("current_playing_title", null)
        val artist = sharedPref.getString("current_playing_artist", null)
        val album = sharedPref.getString("current_playing_album", null)
        val duration = sharedPref.getString("current_playing_duration", null)

        if (url != null && title != null && artist != null) {
            val song = Song(
                title = title,
                artist = artist,
                url = url,
                album = album ?: "Desconocido",
                duration = duration ?: "0"
            )

            val songIndex = songs.indexOfFirst { it.url == url }
            if (songIndex != -1) {
                currentIndex = songIndex
                onSongChanged?.invoke(song)
            }
        }
    }

    fun getCurrentSongIndex(): Int {
        return currentIndex
    }

    fun getCurrentSong(): Song? {
        return if (currentIndex in songs.indices) songs[currentIndex] else null
    }

}
