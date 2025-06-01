package com.example.spomusicapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
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
import kotlin.collections.isNotEmpty
import kotlin.collections.map

class SearchFragment : Fragment(), PlaybackUIListener {

    private lateinit var sharedPref: SharedPreferences
    private var searchJob: Job? = null

    private lateinit var noSongsFounded: TextView
    private lateinit var songListContainer: RelativeLayout
    private lateinit var songAdapter: SongAdapter
    private val originalSongList = mutableListOf<Song>()
    private lateinit var recyclerView: RecyclerView
    private lateinit var editTextQuery: EditText

    private var isPlaying = false

    private lateinit var loadingAnimation: LottieAnimationView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        var view = inflater.inflate(R.layout.fragment_search, container, false)

        /*
        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        */

        noSongsFounded = view.findViewById(R.id.noSongFoundedText)
        songListContainer = view.findViewById(R.id.listSongsContainer)
        recyclerView = view.findViewById(R.id.filteredListSongs)
        recyclerView.layoutManager = LinearLayoutManager(context)
        songAdapter = SongAdapter()
        recyclerView.adapter = songAdapter
        editTextQuery = view.findViewById(R.id.editTextQuery)

        loadingAnimation = view.findViewById(R.id.loadingAnimation)

        sharedPref = requireContext().getSharedPreferences("music_prefs", Context.MODE_PRIVATE)

        PlaybackManager.addUIListener(this)

        songAdapter.onLikeClick = { song, position ->
            FirebaseUtils.toggleLike(song) { isLiked ->
                songAdapter.updateLikeStatus(position, isLiked)
            }
        }

        songAdapter.onItemClick = { (song, imageUri) ->
            val index = songAdapter.currentList.indexOf(song)
            songAdapter.setCurrentPlayingSong(song.url)
            if (imageUri != null) {
                songAdapter.imageUriCache[song.url] = imageUri
                sharedPref.edit() { putString(PreferenceKeys.CURRENT_SONG_COVER, imageUri.toString()) }
            }

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

            if (index != -1) {
                PlaybackManager.updateSongs(songAdapter.currentList)
                PlaybackManager.playSong(requireContext(), song)
                isPlaying = true
                (requireActivity() as? MainActivity)?.updatePlayerUI(song, isPlaying)
                NotificationManagerHelper.createNotificationChannel(requireContext())
            }

            songAdapter.notifyItemChanged(index)
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

                if(originalSongList.isEmpty()){
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
                        searchSongs(query)
                    }

                    if (songs.isNotEmpty()) {
                        val enrichedSongs = songs.map { song ->
                            async(Dispatchers.IO) {
                                Utils.enrichSong(requireContext(), song) ?: song
                            }
                        }.awaitAll()

                        loadingAnimation.visibility = View.INVISIBLE

                        originalSongList.clear()
                        originalSongList.addAll(enrichedSongs)

                        FirebaseUtils.loadUserLikes { likedIds ->
                            val updatedSongs = originalSongList.map { song ->
                                song.copy(isLiked = likedIds.contains(song.id))
                            }

                            songAdapter.submitList(updatedSongs)
                        }

                        noSongsFounded.visibility = View.INVISIBLE
                        songAdapter.submitList(enrichedSongs)
                    } else {
                        loadingAnimation.visibility = View.INVISIBLE
                        songAdapter.submitList(emptyList())
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
                .limit(7)
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