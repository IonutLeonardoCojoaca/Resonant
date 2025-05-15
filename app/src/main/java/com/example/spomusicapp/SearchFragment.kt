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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.collections.isNotEmpty
import kotlin.collections.map

class SearchFragment : Fragment() {

    private lateinit var songAdapter: SongAdapter
    private val originalSongList = mutableListOf<Song>()
    private val songRepository = SongRepository()
    private lateinit var noSongsFounded: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var editTextQuery: EditText

    private lateinit var sharedPref: SharedPreferences

    private var searchJob: Job? = null

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

        songAdapter.onItemClick = { (song, _) ->
            val index = songAdapter.currentList.indexOf(song)
            songAdapter.setCurrentPlayingSong(song.url)

            sharedPref.edit() { putString("current_playing_url", song.url) }
            songAdapter.setCurrentPlayingSong(song.url)
            if (index != -1) {
                PlaybackManager.updateSongs(songAdapter.currentList)
                PlaybackManager.playSongAt(requireContext(), index)
            }
        }

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
                        val enrichedSongs = songs.map { song ->
                            async(Dispatchers.IO) {
                                Utils.enrichSong(requireContext(), song)
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





        return view
    }




}