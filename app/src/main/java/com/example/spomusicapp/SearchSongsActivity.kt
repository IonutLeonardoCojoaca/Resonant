package com.example.spomusicapp

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.spomusicapp.ActivitySongList
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.collections.mapNotNull

class SearchSongsActivity : AppCompatActivity() {

    private lateinit var songAdapter: SongAdapter
    private val originalSongList = mutableListOf<Song>()
    private val songRepository = SongRepository()
    private lateinit var noSongsFounded: TextView


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_search_songs)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        noSongsFounded = findViewById(R.id.noSongsFounded)

        val recyclerView = findViewById<RecyclerView>(R.id.filteredListSongs)
        recyclerView.layoutManager = LinearLayoutManager(this)
        songAdapter = SongAdapter()
        recyclerView.adapter = songAdapter

        val sharedPref = this@SearchSongsActivity.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)

        songAdapter.onItemClick = { song ->
            val index = songAdapter.currentList.indexOf(song)
            songAdapter.setCurrentPlayingSong(song.url)

            sharedPref.edit() { putString("current_playing_url", song.url) }
            songAdapter.setCurrentPlayingSong(song.url)
            Log.i("Musica reproduciendose", song.toString())
            if (index != -1) {
                PlaybackManager.setSongs(songAdapter.currentList)
                PlaybackManager.playSongAt(this@SearchSongsActivity, index)
            }
        }

        val editTextQuery = findViewById<EditText>(R.id.editTextQuery)

        editTextQuery.addTextChangedListener(object : TextWatcher {

            override fun afterTextChanged(s: Editable?) {
                if(s.toString().isEmpty() || s.toString().isBlank()){
                    noSongsFounded.visibility = View.VISIBLE
                    songAdapter.submitList(emptyList())
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(newText: CharSequence?, start: Int, before: Int, count: Int) {

                if(newText.toString().trim().isEmpty()){
                    songAdapter.submitList(emptyList())
                    noSongsFounded.visibility = View.VISIBLE
                }else{
                    lifecycleScope.launch(Dispatchers.Main) {

                        originalSongList.clear()
                        val songs = songRepository.searchSongs(newText.toString())

                        if (songs != null && songs.isNotEmpty()) {
                            noSongsFounded.visibility = View.INVISIBLE
                            originalSongList.addAll(songs)
                            songAdapter.submitList(songs)
                        } else {
                            songAdapter.submitList(emptyList())
                            noSongsFounded.visibility = View.VISIBLE
                        }
                    }
                }

            }

        })

    }




}