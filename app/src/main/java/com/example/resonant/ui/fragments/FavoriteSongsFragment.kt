package com.example.resonant.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
import com.example.resonant.data.network.services.ArtistService
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import kotlinx.coroutines.launch
import androidx.recyclerview.widget.ConcatAdapter
import com.example.resonant.ui.adapters.FavoriteSongsHeaderAdapter
import com.example.resonant.ui.viewmodels.DownloadViewModel

class FavoriteSongsFragment : BaseFragment(R.layout.fragment_favorite_songs) {

    private lateinit var recyclerLikedSongs: RecyclerView
    private lateinit var loadingAnimation: LottieAnimationView
    private lateinit var noLikedSongsText: TextView
    private lateinit var headerAdapter: FavoriteSongsHeaderAdapter
    private var isPlaying: Boolean = false

    private lateinit var songViewModel: SongViewModel
    private lateinit var favoritesViewModel: FavoritesViewModel
    private lateinit var songAdapter: SongAdapter
    private lateinit var artistService: ArtistService
    private lateinit var userProfileImage: ImageView

    // ViewModel para descargas
    private lateinit var downloadViewModel: DownloadViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_favorite_songs, container, false)

        // Inicializar vistas
        noLikedSongsText = view.findViewById(R.id.noLikedAlbumsText)
        loadingAnimation = view.findViewById(R.id.loadingAnimation)
        recyclerLikedSongs = view.findViewById(R.id.favoriteAlbumsList)
        recyclerLikedSongs.layoutManager = LinearLayoutManager(requireContext())
        recyclerLikedSongs.setHasFixedSize(true)
        recyclerLikedSongs.setItemViewCacheSize(20)

        songAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_FULL)
        headerAdapter = FavoriteSongsHeaderAdapter {
            onPlayHeaderClicked()
        }

        recyclerLikedSongs.adapter = ConcatAdapter(headerAdapter, songAdapter)

        userProfileImage = view.findViewById(R.id.userProfile)
        Utils.loadUserProfile(requireContext(), userProfileImage)

        artistService = ApiClient.getArtistService(requireContext())
        songViewModel = ViewModelProvider(requireActivity()).get(SongViewModel::class.java)
        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]

        // --- CAMBIO IMPORTANTE ---
        // Usar requireActivity() para compartir el estado de las descargas con HomeFragment y otros
        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]

        // Configurar listeners
        setupPlayButtonLogic()
        setupAdapterListeners()

        if (favoritesViewModel.favoriteSongs.value.isNullOrEmpty()) {
            loadingAnimation.visibility = View.VISIBLE
            recyclerLikedSongs.visibility = View.GONE
            noLikedSongsText.visibility = View.GONE
        }

        setupFavoritesObserver()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Observar cambios en las descargas para pintar los iconos verdes
        lifecycleScope.launch {
            downloadViewModel.downloadedSongIds.collect { downloadedIds ->
                songAdapter.downloadedSongIds = downloadedIds
                // Refrescar visualmente si la lista ya tiene datos
                if (songAdapter.currentList.isNotEmpty()) {
                    songAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun setupAdapterListeners() {
        songAdapter.onItemClick = { (song, bitmap) ->
            val currentIndex = songAdapter.currentList.indexOfFirst { it.url == song.url }
            val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), it, song.id) }
            val songList = ArrayList(songAdapter.currentList)

            val playIntent = Intent(context, MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.Companion.ACTION_PLAY
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_SONG, song)
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_INDEX, currentIndex)
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                putParcelableArrayListExtra(MusicPlaybackService.Companion.SONG_LIST, songList)
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE, QueueSource.FAVORITE_SONGS)
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE_ID, "FAVORITES")
            }
            requireContext().startService(playIntent)
        }

        songAdapter.onFavoriteClick = { song, _ ->
            favoritesViewModel.toggleFavoriteSong(song)
        }

        songAdapter.onSettingsClick = { song ->
            showSongOptions(song)
        }
    }

    private fun onPlayHeaderClicked() {
        val currentList = ArrayList(songAdapter.currentList)
        if (currentList.isEmpty()) return

        if (isPlaying) {
            val intent = Intent(requireContext(), MusicPlaybackService::class.java)
            intent.action = MusicPlaybackService.Companion.ACTION_PAUSE
            requireContext().startService(intent)
        } else {
            val firstSong = currentList[0]
            val bitmapPath: String? = null
            val playIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.Companion.ACTION_PLAY
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_SONG, firstSong)
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_INDEX, 0)
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                putParcelableArrayListExtra(MusicPlaybackService.Companion.SONG_LIST, currentList)
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE, QueueSource.FAVORITE_SONGS)
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE_ID, "FAVORITES")
            }
            requireContext().startService(playIntent)
        }
    }

    private fun setupPlayButtonLogic() {
        fun updateButtonState() {
            val serviceIsPlaying = songViewModel.isPlayingLiveData.value ?: false
            val currentSongId = songViewModel.currentSongLiveData.value?.id
            val isSongInList = songAdapter.currentList.any { it.id == currentSongId }
            this.isPlaying = serviceIsPlaying && isSongInList
            updatePlayPauseIcon(this.isPlaying)
        }

        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) {
            songAdapter.setCurrentPlayingSong(it?.id)
            updateButtonState()
        }

        songViewModel.isPlayingLiveData.observe(viewLifecycleOwner) {
            updateButtonState()
        }
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        if (::headerAdapter.isInitialized) {
            headerAdapter.setPlayingState(isPlaying)
        }
    }

    private fun setupFavoritesObserver() {
        if (favoritesViewModel.favoriteSongs.value.isNullOrEmpty()) {
            favoritesViewModel.loadFavoriteSongs()
        }
        favoritesViewModel.favoriteSongs.observe(viewLifecycleOwner) { favoriteSongs ->
            loadingAnimation.visibility = View.GONE

            if (favoriteSongs.isEmpty()) {
                noLikedSongsText.visibility = View.VISIBLE
                recyclerLikedSongs.visibility = View.GONE

                songAdapter.submitList(emptyList())
                songAdapter.favoriteSongIds = emptySet()
            } else {
                noLikedSongsText.visibility = View.GONE
                recyclerLikedSongs.visibility = View.VISIBLE

                val count = favoriteSongs.size
                headerAdapter.updateCount(count)

                val ids = favoriteSongs.map { it.id }.toSet()
                songAdapter.favoriteSongIds = ids

                // Actualizamos la lista y aseguramos refresco visual
                songAdapter.submitList(favoriteSongs) {
                    songAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun showSongOptions(song: com.example.resonant.data.models.Song) {
        lifecycleScope.launch {
            val artistList = artistService.getArtistsBySongId(song.id)
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
                },
                onDownloadClick = { songToDownload ->
                    downloadViewModel.downloadSong(songToDownload)
                },
                onRemoveDownloadClick = { songToDelete ->
                    downloadViewModel.deleteSong(songToDelete)
                }
            )
            bottomSheet.show(parentFragmentManager, "SongOptionsBottomSheet")
        }
    }
}