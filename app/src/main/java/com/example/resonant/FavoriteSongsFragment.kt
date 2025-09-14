package com.example.resonant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView

class FavoriteSongsFragment : Fragment() {

    private lateinit var recyclerLikedSongs: RecyclerView
    private lateinit var loadingAnimation: LottieAnimationView
    private lateinit var noLikedSongsText: TextView

    private lateinit var sharedViewModel: SharedViewModel
    private lateinit var favoritesViewModel: FavoritesViewModel
    private lateinit var songAdapter: SongAdapter
    private val _currentSongLiveData = MutableLiveData<Song>()

    private val songChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MusicPlaybackService.ACTION_SONG_CHANGED) {
                val song = intent.getParcelableExtra<Song>(MusicPlaybackService.EXTRA_CURRENT_SONG)
                song?.let {
                    songAdapter.setCurrentPlayingSong(it.id)
                    sharedViewModel.setCurrentSong(it)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            songChangedReceiver,
            IntentFilter(MusicPlaybackService.ACTION_SONG_CHANGED)
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_favorite, container, false)

        noLikedSongsText = view.findViewById(R.id.noLikedSongsText)
        loadingAnimation = view.findViewById(R.id.loadingAnimation)

        recyclerLikedSongs = view.findViewById(R.id.favoriteSongsList)
        recyclerLikedSongs.layoutManager = LinearLayoutManager(requireContext())
        songAdapter = SongAdapter()
        recyclerLikedSongs.adapter = songAdapter

        sharedViewModel = ViewModelProvider(requireActivity()).get(SharedViewModel::class.java)

        sharedViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            currentSong?.let {
                songAdapter.setCurrentPlayingSong(it.id)
            }
        }

        songAdapter.onItemClick = { (song, bitmap) ->
            val currentIndex = songAdapter.currentList.indexOfFirst { it.url == song.url }
            val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), it, song.id) }
            val songList = ArrayList(songAdapter.currentList)

            val playIntent = Intent(context, MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_PLAY
                putExtra(MusicPlaybackService.EXTRA_CURRENT_SONG, song)
                putExtra(MusicPlaybackService.EXTRA_CURRENT_INDEX, currentIndex)
                putExtra(MusicPlaybackService.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, songList)
            }

            requireContext().startService(playIntent)
        }

        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]

        favoritesViewModel.loadFavorites()

        favoritesViewModel.favorites.observe(viewLifecycleOwner) { favorites ->
            loadingAnimation.visibility = View.GONE

            if (favorites.isNullOrEmpty()) {
                noLikedSongsText.visibility = View.VISIBLE
                recyclerLikedSongs.visibility = View.GONE
                songAdapter.submitList(emptyList())
                songAdapter.favoriteSongIds = emptySet()
            } else {
                noLikedSongsText.visibility = View.GONE
                recyclerLikedSongs.visibility = View.VISIBLE

                val ids = favorites.map { it.id }.toSet()
                songAdapter.favoriteSongIds = ids

                songAdapter.submitList(favorites)
            }
        }

        songAdapter.onFavoriteClick = { song, currentlyFavorite ->
            if (currentlyFavorite) {
                favoritesViewModel.deleteFavorite(song.id) { success ->
                    if (!success) {
                        favoritesViewModel.loadFavorites()
                        Toast.makeText(requireContext(), "Error al eliminar favorito", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                favoritesViewModel.addFavorite(song) { success ->
                    if (!success) {
                        favoritesViewModel.loadFavorites()
                        Toast.makeText(requireContext(), "Error al añadir favorito", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        loadingAnimation.visibility = View.VISIBLE
        noLikedSongsText.visibility = View.GONE
        recyclerLikedSongs.visibility = View.GONE
        favoritesViewModel.loadFavorites()

        return view
    }

    fun updateCurrentSong(song: Song) {
        _currentSongLiveData.postValue(song)
    }

    fun setCurrentPlayingSong(url: String) {
        songAdapter.setCurrentPlayingSong(url)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(songChangedReceiver)
    }

}