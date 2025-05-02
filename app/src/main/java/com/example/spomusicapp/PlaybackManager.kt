package com.example.spomusicapp

import android.content.Context
import androidx.core.content.edit

object PlaybackManager {
    private var songs: List<Song> = emptyList() // Tu modelo de canción
    private var currentIndex = 0

    fun setSongs(songList: List<Song>) {
        songs = songList
    }

    fun playSongAt(context: Context, index: Int) {
        if (index in songs.indices) {
            currentIndex = index
            val song = songs[index]
            MediaPlayerManager.play(context, song.url, index)
        }
    }

    fun playNext(context: Context) {
        if (songs.isNotEmpty()) {
            currentIndex = (currentIndex + 1) % songs.size
            val song = songs[currentIndex]
            MediaPlayerManager.play(context, song.url, currentIndex)

            val sharedPref = context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
            sharedPref.edit() { putString("current_playing_url", song.url) }

            (context as? ActivitySongList)?.songAdapter?.setCurrentPlayingSong(song.url)
        }
    }

    fun playPrevious(context: Context) {
        if (songs.isNotEmpty()) {
            currentIndex = if (currentIndex - 1 < 0) songs.size - 1 else currentIndex - 1
            val song = songs[currentIndex]
            MediaPlayerManager.play(context, song.url, currentIndex)

            val sharedPref = context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
            sharedPref.edit() { putString("current_playing_url", song.url) }

            (context as? ActivitySongList)?.songAdapter?.setCurrentPlayingSong(song.url)
        }
    }

}
