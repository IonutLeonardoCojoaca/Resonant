package com.example.resonant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SearchFragment : Fragment() {

    private lateinit var sharedPref: SharedPreferences
    private var searchJob: Job? = null

    private lateinit var noSongsFounded: TextView
    private lateinit var songListContainer: RelativeLayout
    private lateinit var songAdapter: SongAdapter
    private val originalSongList = mutableListOf<Song>()
    private lateinit var recyclerView: RecyclerView
    private lateinit var editTextQuery: EditText

    private lateinit var loadingAnimation: LottieAnimationView

    private lateinit var sharedViewModel: SharedViewModel
    private val _currentSongLiveData = MutableLiveData<Song>()
    private val songChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MusicPlaybackService.ACTION_SONG_CHANGED) {
                val song = intent.getParcelableExtra<Song>(MusicPlaybackService.EXTRA_CURRENT_SONG)
                song?.let {
                    setCurrentPlayingSong(it.url ?: "")
                    updateCurrentSong(it)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        var view = inflater.inflate(R.layout.fragment_search, container, false)

        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            songChangedReceiver,
            IntentFilter(MusicPlaybackService.ACTION_SONG_CHANGED)
        )

        noSongsFounded = view.findViewById(R.id.noSongFoundedText)
        songListContainer = view.findViewById(R.id.listSongsContainer)
        recyclerView = view.findViewById(R.id.filteredListSongs)
        recyclerView.layoutManager = LinearLayoutManager(context)
        songAdapter = SongAdapter()
        recyclerView.adapter = songAdapter
        editTextQuery = view.findViewById(R.id.editTextQuery)

        loadingAnimation = view.findViewById(R.id.loadingAnimation)

        sharedPref = requireContext().getSharedPreferences("music_prefs", Context.MODE_PRIVATE)

        sharedViewModel = ViewModelProvider(requireActivity()).get(SharedViewModel::class.java)

        songAdapter.onItemClick = { (song, bitmap) ->
            val currentIndex = songAdapter.currentList.indexOfFirst { it.url == song.url }

            val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), it, song.id) }

            val playIntent = Intent(context, MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_PLAY
                putExtra(MusicPlaybackService.EXTRA_CURRENT_SONG, song)
                putExtra(MusicPlaybackService.EXTRA_CURRENT_INDEX, currentIndex)
                putExtra(MusicPlaybackService.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
            }

            requireContext().startService(playIntent)
        }

        editTextQuery.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (s.isNullOrBlank()) {
                    noSongsFounded.visibility = View.VISIBLE
                    songAdapter.submitList(emptyList())
                    originalSongList.clear()
                    loadingAnimation.visibility = View.INVISIBLE
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(newText: CharSequence?, start: Int, before: Int, count: Int) {
                val query = newText.toString().trim()
                searchJob?.cancel()

                if (originalSongList.isEmpty()) {
                    loadingAnimation.visibility = View.VISIBLE
                    noSongsFounded.visibility = View.VISIBLE
                }

                searchJob = lifecycleScope.launch {
                    delay(200)
                    if (query.isEmpty()) {
                        songAdapter.submitList(emptyList())
                        return@launch
                    }

                    val songs = withContext(Dispatchers.IO) {
                        try {
                            val service = ApiClient.getService(requireContext())
                            val results = service.searchByQuery(query)

                            for (song in results) {
                                val artists = service.getArtistsBySongId(song.id)
                                song.artistName = artists.joinToString(", ") { it.name }
                            }

                            val cancionesSinUrl = results.filter { it.url == null }
                            if (cancionesSinUrl.isNotEmpty()) {
                                val fileNames = cancionesSinUrl.map { it.fileName }
                                val urlList = service.getMultipleSongUrls(fileNames)
                                val urlMap = urlList.associateBy { it.fileName }

                                cancionesSinUrl.forEach { song ->
                                    song.url = urlMap[song.fileName]?.url
                                }
                            }

                            results
                        } catch (e: Exception) {
                            e.printStackTrace()
                            emptyList<Song>()
                        }
                    }

                    if (songs.isNotEmpty()) {
                        loadingAnimation.visibility = View.INVISIBLE
                        originalSongList.clear()
                        originalSongList.addAll(songs)

                        noSongsFounded.visibility = View.INVISIBLE
                        songAdapter.submitList(songs)

                        val updateIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                            action = MusicPlaybackService.UPDATE_SONGS
                            putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, ArrayList(originalSongList))
                        }
                        requireContext().startService(updateIntent)
                    } else {
                        loadingAnimation.visibility = View.INVISIBLE
                        songAdapter.submitList(emptyList())
                        noSongsFounded.visibility = View.VISIBLE
                    }
                }
            }
        })

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(songChangedReceiver)
    }

    private fun updateCurrentSong(song: Song) {
        _currentSongLiveData.postValue(song)
    }

    private fun setCurrentPlayingSong(url: String) {
        songAdapter.setCurrentPlayingSong(url)
    }

}