package com.example.resonant.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.resonant.R
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.services.ArtistService
import com.example.resonant.managers.UserManager
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.adapters.AlbumAdapter
import com.example.resonant.ui.adapters.ArtistAdapter
import com.example.resonant.ui.adapters.SongAdapter
import com.example.resonant.ui.bottomsheets.AlbumOptionsBottomSheet
import com.example.resonant.ui.bottomsheets.ArtistOptionsBottomSheet
import com.example.resonant.ui.bottomsheets.ArtistSelectorBottomSheet
import com.example.resonant.ui.bottomsheets.SelectPlaylistBottomSheet
import com.example.resonant.ui.bottomsheets.SongOptionsBottomSheet
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.ui.viewmodels.HomeViewModel
import com.example.resonant.ui.viewmodels.SongViewModel
import com.example.resonant.ui.viewmodels.UserViewModel
import com.example.resonant.utils.Utils
import com.facebook.shimmer.ShimmerFrameLayout
import kotlinx.coroutines.launch
import com.example.resonant.ui.viewmodels.DownloadViewModel

class HomeFragment : BaseFragment(R.layout.fragment_home) {

    private lateinit var recyclerViewHistory: RecyclerView
    private lateinit var historyAdapter: SongAdapter

    private lateinit var recyclerViewArtists: RecyclerView
    private lateinit var artistAdapter: ArtistAdapter

    private lateinit var recyclerViewAlbums: RecyclerView
    private lateinit var albumsAdapter: AlbumAdapter

    private lateinit var recyclerViewSongs: RecyclerView
    private lateinit var songAdapter: SongAdapter

    private lateinit var songsFeaturedTitle: TextView
    private lateinit var songsFeaturedTitleAlbums: TextView
    private lateinit var songsFeaturedTitleArtists: TextView
    
    // Containers
    private lateinit var historyContainer: View

    // Shimmer & Error Layouts
    private lateinit var shimmerSongLayout: ShimmerFrameLayout
    private lateinit var shimmerHistoryLayout: ShimmerFrameLayout
    private lateinit var shimmerArtistLayout: ShimmerFrameLayout
    private lateinit var shimmerAlbumLayout: ShimmerFrameLayout
    private lateinit var layoutErrorSongs: LinearLayout
    private lateinit var layoutErrorHistory: LinearLayout
    private lateinit var layoutErrorArtists: LinearLayout
    private lateinit var layoutErrorAlbums: LinearLayout
    private lateinit var tvErrorSongs: TextView
    private lateinit var tvErrorHistory: TextView
    private lateinit var tvErrorArtists: TextView
    private lateinit var tvErrorAlbums: TextView

    private lateinit var userProfileImage: ImageView

    // ViewModels
    private lateinit var songViewModel: SongViewModel
    private lateinit var favoritesViewModel: FavoritesViewModel
    private lateinit var userViewModel: UserViewModel
    private lateinit var homeViewModel: HomeViewModel

    private lateinit var artistService: ArtistService

    private lateinit var downloadViewModel: DownloadViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)
        initViews(view)
        setupRecyclerViews()

        artistService = ApiClient.getArtistService(requireContext())
        setupViewModels()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        homeViewModel.loadSongs()
        homeViewModel.loadArtists()
        homeViewModel.loadAlbums()

        homeViewModel.loadHistory()
        // view.findViewById<View>(R.id.historyPrincipalContainer)?.visibility = View.GONE // Removed

        lifecycleScope.launch {
            downloadViewModel.downloadedSongIds.collect { downloadedIds ->
                songAdapter.downloadedSongIds = downloadedIds
                historyAdapter.downloadedSongIds = downloadedIds
            }
        }
    }

    private fun initViews(view: View) {
        shimmerSongLayout = view.findViewById(R.id.shimmerSongLayout)
        shimmerHistoryLayout = view.findViewById(R.id.shimmerHistory)
        shimmerArtistLayout = view.findViewById(R.id.shimmerArtist)
        shimmerAlbumLayout = view.findViewById(R.id.shimmerAlbum)
        songsFeaturedTitle = view.findViewById(R.id.songsFeatured)
        songsFeaturedTitleAlbums = view.findViewById(R.id.albumsFeatured)
        songsFeaturedTitleArtists = view.findViewById(R.id.artistFeatured)
        layoutErrorSongs = view.findViewById(R.id.layoutErrorSongs)
        layoutErrorHistory = view.findViewById(R.id.layoutErrorHistory)
        layoutErrorArtists = view.findViewById(R.id.layoutErrorArtists)
        layoutErrorAlbums = view.findViewById(R.id.layoutErrorAlbums)
        tvErrorSongs = view.findViewById(R.id.tvErrorSongs)
        tvErrorHistory = view.findViewById(R.id.tvErrorHistory)
        tvErrorArtists = view.findViewById(R.id.tvErrorArtists)
        tvErrorAlbums = view.findViewById(R.id.tvErrorAlbums)
        recyclerViewArtists = view.findViewById(R.id.listArtistsRecycler)
        recyclerViewHistory = view.findViewById(R.id.listHistoryRecycler)
        recyclerViewAlbums = view.findViewById(R.id.listAlbumsRecycler)
        recyclerViewAlbums = view.findViewById(R.id.listAlbumsRecycler)
        recyclerViewSongs = view.findViewById(R.id.allSongList)
        historyContainer = view.findViewById(R.id.historyPrincipalContainer)
        userProfileImage = view.findViewById(R.id.userProfile)
        Utils.loadUserProfile(requireContext(), userProfileImage)
    }

    private fun setupRecyclerViews() {
        // History
        recyclerViewHistory.layoutManager = GridLayoutManager(context, 3) 
        historyAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_GRID)
        recyclerViewHistory.adapter = historyAdapter
        recyclerViewHistory.isNestedScrollingEnabled = false

        // Artists
        recyclerViewArtists.layoutManager = GridLayoutManager(context, 3)
        artistAdapter = ArtistAdapter(mutableListOf())
        artistAdapter.setViewType(ArtistAdapter.Companion.VIEW_TYPE_GRID)
        
        // Setup artist click listener
        artistAdapter.onArtistClick = { artist, sharedImage ->
            val bundle = Bundle().apply {
                putString("artistId", artist.id)
                putString("artistName", artist.name)
                putString("artistImageUrl", artist.url)
                putString("artistImageTransitionName", sharedImage.transitionName)
            }
            val extras = androidx.navigation.fragment.FragmentNavigatorExtras(
                sharedImage to sharedImage.transitionName
            )
            findNavController().navigate(
                R.id.action_homeFragment_to_artistFragment,
                bundle,
                null,
                extras
            )
        }
        
        // Artist Settings Click
        artistAdapter.onSettingsClick = { artist ->
            val bottomSheet = ArtistOptionsBottomSheet(
                artist = artist,
                onGoToArtistClick = { selectedArtist ->
                    val bundle = Bundle().apply { 
                         putString("artistId", selectedArtist.id)
                         putString("artistName", selectedArtist.name)
                         putString("artistImageUrl", selectedArtist.url)
                    }
                    findNavController().navigate(R.id.action_homeFragment_to_artistFragment, bundle)
                },
                onViewDetailsClick = {
                     val bundle = Bundle().apply { 
                         putParcelable("artist", it)
                         putString("artistId", it.id)
                     }
                     findNavController().navigate(R.id.action_global_to_detailedArtistFragment, bundle)
                }
            )
            bottomSheet.show(parentFragmentManager, "ArtistOptionsBottomSheet")
        }
        
        recyclerViewArtists.adapter = artistAdapter
        recyclerViewArtists.isNestedScrollingEnabled = false

        // Albums
        recyclerViewAlbums.layoutManager = GridLayoutManager(context, 3)
        albumsAdapter = AlbumAdapter(mutableListOf(), 0)
        albumsAdapter.onAlbumClick = { album ->
            val bundle = Bundle().apply { putString("albumId", album.id) }
            findNavController().navigate(R.id.action_homeFragment_to_albumFragment, bundle)
        }
        
        // Album Settings Click
        albumsAdapter.onSettingsClick = { album ->
             val bottomSheet = AlbumOptionsBottomSheet(
                 album = album,
                onGoToAlbumClick = {
                    val bundle = Bundle().apply { putString("albumId", it.id) }
                    findNavController().navigate(R.id.action_homeFragment_to_albumFragment, bundle)
                },
                onGoToArtistClick = {
                    val artists = it.artists
                     if (artists.isNotEmpty()) {
                        if (artists.size > 1) {
                            val selector = ArtistSelectorBottomSheet(artists) { selectedArtist ->
                                val bundle = Bundle().apply { 
                                     putString("artistId", selectedArtist.id)
                                     putString("artistName", selectedArtist.name)
                                     putString("artistImageUrl", selectedArtist.url)
                                }
                                findNavController().navigate(R.id.action_homeFragment_to_artistFragment, bundle)
                            }
                            selector.show(parentFragmentManager, "ArtistSelectorBottomSheet")
                        } else {
                            val artist = artists[0]
                             val bundle = Bundle().apply { 
                                 putString("artistId", artist.id)
                                 putString("artistName", artist.name)
                                 putString("artistImageUrl", artist.url)
                            }
                            findNavController().navigate(R.id.action_homeFragment_to_artistFragment, bundle)
                        }
                    }
                },
                onViewDetailsClick = {
                     val bundle = Bundle().apply { 
                         putParcelable("album", it)
                         putString("albumId", it.id)
                     }
                     findNavController().navigate(R.id.action_global_to_detailedAlbumFragment, bundle)
                }
             )
             bottomSheet.show(parentFragmentManager, "AlbumOptionsBottomSheet")
        }
        
        recyclerViewAlbums.adapter = albumsAdapter
        recyclerViewAlbums.isNestedScrollingEnabled = false

        // Songs - NestedScrollView handles the scrolling
        recyclerViewSongs.layoutManager = LinearLayoutManager(requireContext())
        songAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_FULL)
        recyclerViewSongs.adapter = songAdapter
        recyclerViewSongs.isNestedScrollingEnabled = false

        setupSongClickListeners()
    }

    private fun setupViewModels() {
        songViewModel = ViewModelProvider(requireActivity()).get(SongViewModel::class.java)
        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]
        userViewModel = ViewModelProvider(requireActivity())[UserViewModel::class.java]
        homeViewModel = ViewModelProvider(requireActivity())[HomeViewModel::class.java]
        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]

        setupHomeObservers()
        setupOtherObservers()
    }

    private fun setupHomeObservers() {
        // --- OBSERVAR CANCIONES ---
        homeViewModel.songs.observe(viewLifecycleOwner) { songs ->
            songAdapter.submitList(songs)
            updateSectionState(false, false, recyclerViewSongs, shimmerSongLayout, layoutErrorSongs)
        }
        homeViewModel.songsTitle.observe(viewLifecycleOwner) { title ->
            songsFeaturedTitle.text = title
        }
        homeViewModel.songsLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) {
                updateSectionState(true, false, recyclerViewSongs, shimmerSongLayout, layoutErrorSongs)
            }
        }
        homeViewModel.songsError.observe(viewLifecycleOwner) { error ->
            if (error != null) updateSectionState(
                false, true, recyclerViewSongs, shimmerSongLayout, layoutErrorSongs, tvErrorSongs, error
            )
        }

        // --- OBSERVAR HISTORIAL ---
        homeViewModel.history.observe(viewLifecycleOwner) { history ->
            if (history.isNullOrEmpty()) {
                historyContainer.visibility = View.GONE
                updateSectionState(false, false, recyclerViewHistory, shimmerHistoryLayout, layoutErrorHistory)
            } else {
                historyContainer.visibility = View.VISIBLE
                historyAdapter.submitList(history)
                updateSectionState(false, false, recyclerViewHistory, shimmerHistoryLayout, layoutErrorHistory)
            }
        }
        homeViewModel.historyLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) {
                 historyContainer.visibility = View.VISIBLE
                 updateSectionState(true, false, recyclerViewHistory, shimmerHistoryLayout, layoutErrorHistory)
            } else {
                 if (historyAdapter.currentList.isEmpty()) {
                     historyContainer.visibility = View.GONE
                 }
                 updateSectionState(false, false, recyclerViewHistory, shimmerHistoryLayout, layoutErrorHistory)
            }
        }
        homeViewModel.historyError.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                 historyContainer.visibility = View.VISIBLE
                 updateSectionState(
                    false, true, recyclerViewHistory, shimmerHistoryLayout, layoutErrorHistory, tvErrorHistory, error
                )
            }
        }

        // --- OBSERVAR ARTISTAS ---
        homeViewModel.artists.observe(viewLifecycleOwner) { artists ->
            artistAdapter.submitArtists(artists)
            updateSectionState(false, false, recyclerViewArtists, shimmerArtistLayout, layoutErrorArtists)
        }
        homeViewModel.artistsTitle.observe(viewLifecycleOwner) { title ->
            songsFeaturedTitleArtists.text = title
        }
        homeViewModel.artistsLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) {
                updateSectionState(true, false, recyclerViewArtists, shimmerArtistLayout, layoutErrorArtists)
            }
        }
        homeViewModel.artistsError.observe(viewLifecycleOwner) { error ->
            if (error != null) updateSectionState(
                false, true, recyclerViewArtists, shimmerArtistLayout, layoutErrorArtists, tvErrorArtists, error
            )
        }

        // --- OBSERVAR ÁLBUMES ---
        homeViewModel.albums.observe(viewLifecycleOwner) { albums ->
            albumsAdapter.updateList(albums)
            updateSectionState(false, false, recyclerViewAlbums, shimmerAlbumLayout, layoutErrorAlbums)
        }
        homeViewModel.albumsTitle.observe(viewLifecycleOwner) { title ->
            songsFeaturedTitleAlbums.text = title
        }
        homeViewModel.albumsLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) {
                updateSectionState(true, false, recyclerViewAlbums, shimmerAlbumLayout, layoutErrorAlbums)
            }
        }
        homeViewModel.albumsError.observe(viewLifecycleOwner) { error ->
            if (error != null) updateSectionState(
                false, true, recyclerViewAlbums, shimmerAlbumLayout, layoutErrorAlbums, tvErrorAlbums, error
            )
        }
    }

    private fun setupOtherObservers() {
        // Song playing
        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            currentSong?.let { 
                songAdapter.setCurrentPlayingSong(it.id)
                historyAdapter.setCurrentPlayingSong(it.id)
            }
        }

        // User Profile
        userViewModel.profileImageUpdated.observe(viewLifecycleOwner) { isUpdated ->
            if (isUpdated) Utils.loadUserProfile(requireContext(), userProfileImage)
        }

        // Favorites
        favoritesViewModel.loadFavoriteSongs()
        favoritesViewModel.favoriteSongIds.observe(viewLifecycleOwner) { songIds ->
            songAdapter.favoriteSongIds = songIds
            historyAdapter.favoriteSongIds = songIds
            if (songAdapter.currentList.isNotEmpty()) {
                songAdapter.notifyDataSetChanged()
            }
             if (historyAdapter.currentList.isNotEmpty()) {
                historyAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun setupSongClickListeners() {
        val currentHomeQueueId = System.currentTimeMillis().toString()
        setupAdapterListeners(songAdapter, currentHomeQueueId)
        setupAdapterListeners(historyAdapter, currentHomeQueueId)
    }

    private fun setupAdapterListeners(adapter: SongAdapter, queueId: String) {
        adapter.onItemClick = { (song, bitmap) ->
            val currentIndex = adapter.currentList.indexOfFirst { it.id == song.id }
            val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), it, song.id) }
            val songList = ArrayList(adapter.currentList)

            val playIntent = Intent(context, MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.Companion.ACTION_PLAY
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_SONG, song)
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_INDEX, currentIndex)
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                putParcelableArrayListExtra(MusicPlaybackService.Companion.SONG_LIST, songList)
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE, QueueSource.HOME)
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE_ID, queueId)
            }
            requireContext().startService(playIntent)
        }

        adapter.onFavoriteClick = { song, _ -> favoritesViewModel.toggleFavoriteSong(song) }

        adapter.onSettingsClick = { song ->
            lifecycleScope.launch {
                // Song already has artists list from the API, use it
                song.artistName = song.artists?.joinToString(", ") { it.name } ?: song.artistName ?: "Desconocido"

                val bottomSheet = SongOptionsBottomSheet(
                    song = song,
                    onSeeSongClick = { selectedSong ->
                        val bundle = Bundle().apply { putParcelable("song", selectedSong) }
                        findNavController().navigate(R.id.action_homeFragment_to_detailedSongFragment, bundle)
                    },
                    onFavoriteToggled = { toggledSong -> favoritesViewModel.toggleFavoriteSong(toggledSong) },
                    onAddToPlaylistClick = { songToAdd ->
                        val sheet = SelectPlaylistBottomSheet(
                            song = songToAdd,
                            onNoPlaylistsFound = { findNavController().navigate(R.id.action_global_to_createPlaylistFragment) }
                        )
                        sheet.show(parentFragmentManager, "SelectPlaylistBottomSheet")
                    },
                    onDownloadClick = { songToDownload ->
                        downloadViewModel.downloadSong(songToDownload)
                    },
                    onRemoveDownloadClick = { songToDelete ->
                        downloadViewModel.deleteSong(songToDelete)
                    },
                    onGoToAlbumClick = { albumId ->
                        val bundle = Bundle().apply { putString("albumId", albumId) }
                        findNavController().navigate(R.id.action_homeFragment_to_albumFragment, bundle)
                    },
                    onGoToArtistClick = { artist ->
                         val bundle = Bundle().apply { 
                             putString("artistId", artist.id)
                             putString("artistName", artist.name)
                             putString("artistImageUrl", artist.url)
                        }
                        findNavController().navigate(R.id.action_homeFragment_to_artistFragment, bundle)
                    }
                )
                bottomSheet.show(parentFragmentManager, "SongOptionsBottomSheet")
            }
        }
    }

    private fun updateSectionState(
        isLoading: Boolean,
        isError: Boolean,
        recyclerView: RecyclerView,
        shimmer: ShimmerFrameLayout,
        errorView: View? = null,
        errorMessageView: TextView? = null,
        message: String = ""
    ) {
        if (isLoading) {
            recyclerView.visibility = View.INVISIBLE
            errorView?.visibility = View.GONE
            shimmer.visibility = View.VISIBLE
            shimmer.startShimmer()
        } else if (isError) {
            shimmer.stopShimmer()
            shimmer.visibility = View.GONE
            recyclerView.visibility = View.GONE
            errorView?.visibility = View.VISIBLE
            errorMessageView?.text = message
        } else {
            shimmer.stopShimmer()
            shimmer.visibility = View.GONE
            errorView?.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }
}