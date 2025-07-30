package com.example.resonant

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

class FavoriteSongsFragment : Fragment()
    //, PlaybackUIListener
    {

    private var likedSongList = mutableListOf<Song>()
    private lateinit var songAdapter: SongAdapter
    private lateinit var recyclerLikedSongs: RecyclerView
    private var isPlaying = false

    private var likeListenerRegistration: ListenerRegistration? = null

    private lateinit var loadingAnimation: LottieAnimationView
    private lateinit var noLikedSongsText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {super.onCreate(savedInstanceState)}

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_favorite_songs, container, false)
        /*
        val sharedPref = requireActivity().getSharedPreferences("music_prefs", Context.MODE_PRIVATE)

        recyclerLikedSongs = view.findViewById(R.id.favoriteSongsList)
        recyclerLikedSongs.layoutManager = LinearLayoutManager(requireContext())
        songAdapter = SongAdapter()
        recyclerLikedSongs.adapter = songAdapter

        loadingAnimation = view.findViewById(R.id.loadingAnimation)
        noLikedSongsText = view.findViewById(R.id.noLikedSongsText)

        PlaybackManager.addUIListener(this)

        songAdapter.onLikeClick = { song, position ->
            FirebaseUtils.toggleLike(song) { isLiked ->
                if (isLiked) {
                    songAdapter.updateLikeStatus(position, true)
                } else {
                    songAdapter.removeSongAt(position)
                    if(likedSongList.isEmpty()){
                        noLikedSongsText.visibility = View.VISIBLE
                    }
                }
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

        if(likedSongList.isEmpty()){
            noLikedSongsText.visibility = View.VISIBLE
        }
        */
        return view
    }

    fun observeLikedSongIds(userId: String) {
        val db = FirebaseFirestore.getInstance()
        likeListenerRegistration?.remove()

        likeListenerRegistration = db.collection("users")
            .document(userId)
            .collection("likes")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("FavoriteSongs", "Error al escuchar cambios en likes: ${error.message}")
                    return@addSnapshotListener
                }

                if (snapshot != null && !snapshot.isEmpty) {
                    val likedIds = snapshot.documents.mapNotNull { it.id }
                    updateLikedSongs(likedIds)
                } else {
                    likedSongList.clear()
                    songAdapter.submitList(emptyList())
                }
            }
    }

    fun updateLikedSongs(likedIds: List<String>) {
        loadingAnimation.visibility = View.VISIBLE
        noLikedSongsText.visibility = View.GONE

        loadAllSongs { allSongs ->
            val filtered = allSongs.filter { likedIds.contains(it.id) }

            if (filtered.isNotEmpty()) {
                val enrichedSongsDeferred = filtered.map { song ->
                    lifecycleScope.async(Dispatchers.IO) {
                        //Utils.enrichSong(requireContext(), song)?.copy(isLiked = true)
                    }
                }

                lifecycleScope.launch {
                    val enrichedSongs = enrichedSongsDeferred.awaitAll().filterNotNull()

                    likedSongList.clear()
                    //likedSongList.addAll(enrichedSongs)

                    songAdapter.submitList(likedSongList.toList())
                    //PlaybackManager.updateSongs(likedSongList)

                    loadingAnimation.visibility = View.INVISIBLE

                    if(likedSongList.isEmpty()){
                        noLikedSongsText.visibility = View.VISIBLE
                    }
                }
            } else {
                likedSongList.clear()
                songAdapter.submitList(emptyList())
            }
        }
    }

    private fun loadAllSongs(callback: (List<Song>) -> Unit) {
        val firestore = FirebaseFirestore.getInstance()
        firestore.collection("songs")
            .get()
            .addOnSuccessListener { result ->
                val allSongs = result.documents.mapNotNull { it.toObject(Song::class.java) }
                callback(allSongs)
            }
            .addOnFailureListener { exception ->
                Log.e("FavoriteSongs", "Error al cargar canciones: ${exception.message}")
                callback(emptyList())
            }
    }
    /*
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
    */
    override fun onDestroyView() {
        super.onDestroyView()
        likeListenerRegistration?.remove()
    }


}