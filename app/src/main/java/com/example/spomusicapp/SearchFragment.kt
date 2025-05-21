package com.example.spomusicapp

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.spomusicapp.ActivitySongList
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.collections.isNotEmpty
import kotlin.collections.map

class SearchFragment : Fragment(), PlaybackUIListener {

    private lateinit var songAdapter: SongAdapter
    private val originalSongList = mutableListOf<Song>()
    private lateinit var noSongsFounded: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var editTextQuery: EditText

    private lateinit var sharedPref: SharedPreferences

    private var searchJob: Job? = null

    private var isPlaying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        var view = inflater.inflate(R.layout.fragment_search, container, false)

        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        noSongsFounded = view.findViewById(R.id.noSongsFounded)
        recyclerView = view.findViewById(R.id.filteredListSongs)
        recyclerView.layoutManager = LinearLayoutManager(context)
        songAdapter = SongAdapter()
        recyclerView.adapter = songAdapter
        editTextQuery = view.findViewById(R.id.editTextQuery)

        sharedPref = requireContext().getSharedPreferences("music_prefs", Context.MODE_PRIVATE)

        PlaybackManager.addUIListener(this)

        songAdapter.onItemClick = { (song, _) ->
            val index = songAdapter.currentList.indexOf(song)
            sharedPref.edit() { putString("current_playing_url", song.url) }
            songAdapter.setCurrentPlayingSong(song.url)

            if (index != -1) {
                PlaybackManager.updateSongs(songAdapter.currentList)
                PlaybackManager.playSongAt(requireContext(), index)
                var isPlaying = true
                (requireActivity() as? MainActivity)?.updatePlayerUI(song, isPlaying)
            }

        }

        editTextQuery.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (s.isNullOrBlank()) {
                    noSongsFounded.visibility = View.VISIBLE
                    songAdapter.submitList(emptyList())
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(newText: CharSequence?, start: Int, before: Int, count: Int) {
                val query = newText.toString().trim()
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(200)
                    if (query.isEmpty()) {
                        noSongsFounded.visibility = View.VISIBLE
                        songAdapter.submitList(emptyList())
                        return@launch
                    }
                    val songs = withContext(Dispatchers.IO) {
                        searchSongs(query)
                    }
                    if (songs.isNotEmpty()) {
                        val enrichedSongs = songs.map { song ->
                            async(Dispatchers.IO) {
                                Utils.enrichSong(requireContext(), song) ?: song
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


        return view
    }

    suspend fun searchSongs(query: String): List<Song> {
        return try {
            val firestore = FirebaseFirestore.getInstance()

            val snapshot = firestore.collection("songs")
                .whereArrayContains("searchKeywords", query.lowercase())
                .limit(10)
                .get()
                .await()

            snapshot.documents.mapNotNull { it.toObject(Song::class.java) }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override fun onSongChanged(song: Song, isPlaying: Boolean) {
        songAdapter.setCurrentPlayingSong(song.url)
        this.isPlaying = isPlaying
        val index = songAdapter.currentList.indexOfFirst { it.url == song.url }
        if (index != -1) {
            songAdapter.notifyItemChanged(index)
        }
        (requireActivity() as? MainActivity)?.updatePlayerUI(song, isPlaying)
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        this.isPlaying = isPlaying
    }

    override fun onResume() {
        super.onResume()
        PlaybackManager.addUIListener(this)
    }

    override fun onPause() {
        PlaybackManager.clearUIListener()
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        PlaybackManager.clearUIListener()
    }

}