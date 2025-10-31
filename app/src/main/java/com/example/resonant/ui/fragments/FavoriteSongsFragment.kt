package com.example.resonant.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.playback.QueueSource
import com.example.resonant.R
import com.example.resonant.ui.bottomsheets.SelectPlaylistBottomSheet
import com.example.resonant.ui.viewmodels.SongViewModel
import com.example.resonant.ui.adapters.SongAdapter
import com.example.resonant.ui.bottomsheets.SongOptionsBottomSheet
import com.example.resonant.utils.Utils
import com.example.resonant.data.network.ApiClient
import com.example.resonant.ui.viewmodels.FavoriteItem
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import kotlinx.coroutines.launch

class FavoriteSongsFragment : BaseFragment(R.layout.fragment_favorite_songs) {

    private lateinit var recyclerLikedSongs: RecyclerView
    private lateinit var loadingAnimation: LottieAnimationView
    private lateinit var noLikedSongsText: TextView

    private lateinit var songViewModel: SongViewModel
    private lateinit var favoritesViewModel: FavoritesViewModel
    private lateinit var songAdapter: SongAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_favorite_songs, container, false)

        noLikedSongsText = view.findViewById(R.id.noLikedAlbumsText)
        loadingAnimation = view.findViewById(R.id.loadingAnimation)

        recyclerLikedSongs = view.findViewById(R.id.favoriteAlbumsList)
        recyclerLikedSongs.layoutManager = LinearLayoutManager(requireContext())
        songAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_FULL)
        recyclerLikedSongs.adapter = songAdapter

        songViewModel = ViewModelProvider(requireActivity()).get(SongViewModel::class.java)

        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            currentSong?.let {
                songAdapter.setCurrentPlayingSong(it.id)
            }
        }

        songAdapter.onItemClick = { (song, bitmap) ->
            val currentIndex = songAdapter.currentList.indexOfFirst { it.url == song.url }
            val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), it, song.id) }
            val songList = ArrayList(songAdapter.currentList)

            val queueItem = "MAIN"

            val playIntent = Intent(context, MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.Companion.ACTION_PLAY
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_SONG, song)
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_INDEX, currentIndex)
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                putParcelableArrayListExtra(MusicPlaybackService.Companion.SONG_LIST, songList)
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE, QueueSource.FAVORITE_SONGS) // <-- Contexto: home random
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE_ID, queueItem) // <-- id único para esta cola (puede ser fijo o dinámico)
            }

            requireContext().startService(playIntent)
        }

        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]

        favoritesViewModel.loadFavoriteSongs()

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

                val favoriteSongs = favorites.filterIsInstance<FavoriteItem.SongItem>().map { it.song }
                val ids = favoriteSongs.map { it.id }.toSet()
                songAdapter.favoriteSongIds = ids
                songAdapter.submitList(favoriteSongs)
            }
        }

        songAdapter.onFavoriteClick = { song, wasFavorite ->
            // Le decimos al ViewModel que se encargue de la lógica.
            // Es exactamente la misma llamada que hicimos desde el BottomSheet.
            favoritesViewModel.toggleFavoriteSong(song)
        }

        songAdapter.onSettingsClick = { song ->
            val service = ApiClient.getService(requireContext())
            lifecycleScope.launch {
                val artistList = service.getArtistsBySongId(song.id)
                song.artistName = artistList.joinToString(", ") { it.name }

                val bottomSheet = SongOptionsBottomSheet(
                    song = song,
                    onSeeSongClick = { selectedSong ->
                        val bundle = Bundle().apply {
                            putParcelable("song", selectedSong)
                        }
                        findNavController().navigate(
                            R.id.action_favoriteSongsFragment_to_detailedSongFragment,
                            bundle
                        )
                    },
                    onFavoriteToggled = { toggledSong ->
                        favoritesViewModel.toggleFavoriteSong(toggledSong)
                    },
                    onAddToPlaylistClick = { songToAdd ->
                        val selectPlaylistBottomSheet = SelectPlaylistBottomSheet(
                            song = songToAdd,
                            onNoPlaylistsFound = {
                                findNavController().navigate(R.id.action_global_to_createPlaylistFragment)
                            }
                        )
                        selectPlaylistBottomSheet.show(
                            parentFragmentManager,
                            "SelectPlaylistBottomSheet"
                        )
                    }
                )
                bottomSheet.show(parentFragmentManager, "SongOptionsBottomSheet")
            }
        }

        loadingAnimation.visibility = View.VISIBLE
        noLikedSongsText.visibility = View.GONE
        recyclerLikedSongs.visibility = View.GONE

        return view
    }

    fun setCurrentPlayingSong(url: String) {
        songAdapter.setCurrentPlayingSong(url)
    }

}