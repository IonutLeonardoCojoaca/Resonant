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

    // --- UI Components ---
    private lateinit var swipeRefresh: SwipeRefreshLayout // NUEVO

    private lateinit var recyclerViewArtists: RecyclerView
    private lateinit var artistAdapter: ArtistAdapter

    private lateinit var recyclerViewAlbums: RecyclerView
    private lateinit var albumsAdapter: AlbumAdapter

    private lateinit var recyclerViewSongs: RecyclerView
    private lateinit var songAdapter: SongAdapter

    private lateinit var songsFeaturedTitle: TextView
    private lateinit var songsFeaturedTitleAlbums: TextView
    private lateinit var songsFeaturedTitleArtists: TextView

    // Shimmer & Error Layouts
    private lateinit var shimmerSongLayout: ShimmerFrameLayout
    private lateinit var shimmerArtistLayout: ShimmerFrameLayout
    private lateinit var shimmerAlbumLayout: ShimmerFrameLayout
    private lateinit var layoutErrorSongs: LinearLayout
    private lateinit var layoutErrorArtists: LinearLayout
    private lateinit var layoutErrorAlbums: LinearLayout
    private lateinit var tvErrorSongs: TextView
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

        val userId = UserManager(requireContext()).getUserId() ?: ""

        homeViewModel.loadSongs(userId)
        homeViewModel.loadArtists(userId)
        homeViewModel.loadAlbums(userId)

        swipeRefresh.setOnRefreshListener {
            homeViewModel.refreshAll(userId)
        }

        lifecycleScope.launch {
            downloadViewModel.downloadedSongIds.collect { downloadedIds ->
                songAdapter.downloadedSongIds = downloadedIds
            }
        }

        swipeRefresh.setColorSchemeResources(R.color.white)
        swipeRefresh.setProgressViewOffset(false, 0, 200)
        swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.black)
    }

    private fun initViews(view: View) {
        swipeRefresh = view.findViewById(R.id.swipeRefresh) // Inicializar

        shimmerSongLayout = view.findViewById(R.id.shimmerSongLayout)
        shimmerArtistLayout = view.findViewById(R.id.shimmerArtist)
        shimmerAlbumLayout = view.findViewById(R.id.shimmerAlbum)
        songsFeaturedTitle = view.findViewById(R.id.songsFeatured)
        songsFeaturedTitleAlbums = view.findViewById(R.id.albumsFeatured)
        songsFeaturedTitleArtists = view.findViewById(R.id.artistFeatured)
        layoutErrorSongs = view.findViewById(R.id.layoutErrorSongs)
        layoutErrorArtists = view.findViewById(R.id.layoutErrorArtists)
        layoutErrorAlbums = view.findViewById(R.id.layoutErrorAlbums)
        tvErrorSongs = view.findViewById(R.id.tvErrorSongs)
        tvErrorArtists = view.findViewById(R.id.tvErrorArtists)
        tvErrorAlbums = view.findViewById(R.id.tvErrorAlbums)
        recyclerViewArtists = view.findViewById(R.id.listArtistsRecycler)
        recyclerViewAlbums = view.findViewById(R.id.listAlbumsRecycler)
        recyclerViewSongs = view.findViewById(R.id.allSongList)
        userProfileImage = view.findViewById(R.id.userProfile)
        Utils.loadUserProfile(requireContext(), userProfileImage)
    }

    private fun setupRecyclerViews() {
        // Artists
        recyclerViewArtists.layoutManager = GridLayoutManager(context, 3)
        artistAdapter = ArtistAdapter(mutableListOf())
        artistAdapter.setViewType(ArtistAdapter.Companion.VIEW_TYPE_GRID)
        recyclerViewArtists.adapter = artistAdapter
        recyclerViewArtists.isNestedScrollingEnabled = false // <--- AÑADIR ESTO

        // Albums
        recyclerViewAlbums.layoutManager = GridLayoutManager(context, 3)
        albumsAdapter = AlbumAdapter(mutableListOf(), 0)
        recyclerViewAlbums.adapter = albumsAdapter
        recyclerViewAlbums.isNestedScrollingEnabled = false // <--- AÑADIR ESTO

        // Songs
        recyclerViewSongs.layoutManager = LinearLayoutManager(requireContext())
        songAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_FULL)
        recyclerViewSongs.adapter = songAdapter
        recyclerViewSongs.isNestedScrollingEnabled = false // <--- AÑADIR ESTO

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

            // Si terminaron de cargar las canciones, paramos el SwipeRefresh
            if (swipeRefresh.isRefreshing) swipeRefresh.isRefreshing = false
        }
        homeViewModel.songsTitle.observe(viewLifecycleOwner) { title ->
            songsFeaturedTitle.text = title
        }
        homeViewModel.songsLoading.observe(viewLifecycleOwner) { isLoading ->
            // Solo mostramos Shimmer si NO es un swipe manual (para no tener doble loader)
            if (isLoading && !swipeRefresh.isRefreshing) {
                updateSectionState(true, false, recyclerViewSongs, shimmerSongLayout, layoutErrorSongs)
            }
        }
        homeViewModel.songsError.observe(viewLifecycleOwner) { error ->
            // Si hay error, paramos el swipe
            if (swipeRefresh.isRefreshing) swipeRefresh.isRefreshing = false

            if (error != null) updateSectionState(
                false, true, recyclerViewSongs, shimmerSongLayout, layoutErrorSongs, tvErrorSongs, error
            )
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
            if (isLoading && !swipeRefresh.isRefreshing) {
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
            if (isLoading && !swipeRefresh.isRefreshing) {
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
            currentSong?.let { songAdapter.setCurrentPlayingSong(it.id) }
        }

        // User Profile
        userViewModel.profileImageUpdated.observe(viewLifecycleOwner) { isUpdated ->
            if (isUpdated) Utils.loadUserProfile(requireContext(), userProfileImage)
        }

        // Favorites
        favoritesViewModel.loadFavoriteSongs()
        favoritesViewModel.favoriteSongIds.observe(viewLifecycleOwner) { songIds ->
            songAdapter.favoriteSongIds = songIds
            if (songAdapter.currentList.isNotEmpty()) {
                songAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun setupSongClickListeners() {
        var currentHomeQueueId: String = System.currentTimeMillis().toString()

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
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE, QueueSource.HOME)
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE_ID, currentHomeQueueId)
            }
            requireContext().startService(playIntent)
        }

        songAdapter.onFavoriteClick = { song, _ -> favoritesViewModel.toggleFavoriteSong(song) }

        songAdapter.onSettingsClick = { song ->
            lifecycleScope.launch {
                val artistList = artistService.getArtistsBySongId(song.id)
                song.artistName = artistList.joinToString(", ") { it.name }

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