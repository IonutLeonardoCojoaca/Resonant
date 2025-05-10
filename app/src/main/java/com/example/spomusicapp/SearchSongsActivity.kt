package com.example.spomusicapp

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchSongsActivity : AppCompatActivity() {

    private lateinit var songAdapter: SongAdapter
    private val originalSongList = mutableListOf<Song>()
    private val songRepository = SongRepository()
    private lateinit var noSongsFounded: TextView
    private lateinit var arrowGoBackButton: ImageButton

    private var searchJob: Job? = null

    private val backCallback = OnBackInvokedCallback {
        finish()
    }

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
        arrowGoBackButton = findViewById(R.id.arrowGoBackButton)

        val recyclerView = findViewById<RecyclerView>(R.id.filteredListSongs)
        recyclerView.layoutManager = LinearLayoutManager(this)
        songAdapter = SongAdapter()
        recyclerView.adapter = songAdapter

        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            backCallback
        )

        arrowGoBackButton.setOnClickListener {
            backCallback.onBackInvoked()
        }

        val sharedPref = this@SearchSongsActivity.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)

        songAdapter.onItemClick = { (song, _) ->
            val index = songAdapter.currentList.indexOf(song)
            songAdapter.setCurrentPlayingSong(song.url)

            sharedPref.edit() { putString("current_playing_url", song.url) }
            songAdapter.setCurrentPlayingSong(song.url)
            if (index != -1) {
                PlaybackManager.updateSongs(songAdapter.currentList)
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
                val query = newText.toString().trim()

                searchJob = lifecycleScope.launch {

                    val songs = withContext(Dispatchers.IO) {
                        songRepository.searchSongs(query)
                    }

                    if (songs != null && songs.isNotEmpty()) {
                        // Enriquecer en paralelo los metadatos
                        val enrichedSongs = songs.map { song ->
                            async(Dispatchers.IO) {
                                enrichSong(song)
                                    ?: song
                            }
                        }.awaitAll()

                        originalSongList.clear()
                        originalSongList.addAll(enrichedSongs)

                        noSongsFounded.visibility = View.INVISIBLE
                        songAdapter.submitList(enrichedSongs)
                    } else {
                        songAdapter.submitList(emptyList())
                        noSongsFounded.visibility = View.VISIBLE
                    }
                }
            }


        })


    }

    override fun onDestroy() {
        super.onDestroy()
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
    }

    suspend fun enrichSong(song: Song): Song? {
        return withContext(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(song.url, HashMap())
                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: song.title
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Artista desconocido"
                retriever.release()

                Song(
                    title = title,
                    artist = artist,
                    album = "",
                    url = song.url
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

}