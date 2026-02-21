package com.example.resonant.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.children
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.data.models.Playlist
import com.example.resonant.data.models.Song
import com.example.resonant.managers.PlaylistManager
import com.example.resonant.managers.UserManager
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.adapters.PlaylistAdapter
import com.example.resonant.ui.adapters.SearchResult
import com.example.resonant.ui.adapters.SearchResultAdapter
import com.example.resonant.ui.bottomsheets.AlbumOptionsBottomSheet
import com.example.resonant.ui.bottomsheets.ArtistOptionsBottomSheet
import com.example.resonant.ui.bottomsheets.ArtistSelectorBottomSheet
import com.example.resonant.ui.bottomsheets.PlaylistOptionsBottomSheet
import com.example.resonant.ui.bottomsheets.SelectPlaylistBottomSheet
import com.example.resonant.ui.bottomsheets.SongOptionsBottomSheet
import com.example.resonant.ui.viewmodels.DownloadViewModel
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.ui.viewmodels.PlaylistsListViewModel
import com.example.resonant.ui.viewmodels.PlaylistsListViewModelFactory
import com.example.resonant.ui.viewmodels.SongViewModel
import com.example.resonant.utils.AnimationsUtils
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.example.resonant.utils.Utils
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayList

class SavedFragment : BaseFragment(R.layout.fragment_saved) {

    // Views
    private lateinit var chipGroup: ChipGroup
    private lateinit var playlistsContainerView: View
    private lateinit var favoritesRecyclerView: RecyclerView
    private lateinit var downloadsItemContainer: View
    private var lastCheckedId = View.NO_ID
    
    // Playlists Section
    private lateinit var playlistRecyclerView: RecyclerView
    private lateinit var newPlaylistOption: ConstraintLayout
    private lateinit var userProfileImage: ImageView
    private lateinit var playlistAdapter: PlaylistAdapter

    // Favorites Section
    private lateinit var favoritesAdapter: SearchResultAdapter

    // ViewModels
    private val playlistsListViewModel: PlaylistsListViewModel by viewModels {
        val playlistManager = PlaylistManager(requireContext())
        PlaylistsListViewModelFactory(playlistManager)
    }

    private val favoritesViewModel: FavoritesViewModel by viewModels()
    private val downloadViewModel: DownloadViewModel by activityViewModels()
    private val songViewModel: SongViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_saved, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Observe Downloads to update icons
        lifecycleScope.launch {
            downloadViewModel.downloadedSongIds.collect { ids ->
                favoritesAdapter.downloadedSongIds = ids
            }
        }

        initViews(view)
        setupAdapters()
        setupClickListeners()
        setupChipListener()
        
        // Observe Playlists
        playlistsListViewModel.playlists.observe(viewLifecycleOwner, Observer { playlists ->
            Log.d("SavedFragment", "Playlists recibidas: ${playlists?.size ?: 0}")
            playlistAdapter.submitList(playlists ?: emptyList())
            updateEmptyView(playlists)
        })

        // Observe Favorites (to update list if currently viewing favorites)
        observeFavorites()

        // Observe Current Song to update playing state icons (Play/Pause/Equalizer)
        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            currentSong?.let {
                 favoritesAdapter.setCurrentPlayingSong(it.id)
            }
        }

        // Handle BackStack results (e.g. after editing playlist)
        val navController = findNavController()
        navController.currentBackStackEntry?.savedStateHandle?.getLiveData<String>("PLAYLIST_UPDATED_ID")
            ?.observe(viewLifecycleOwner) { playlistId ->
                if (playlistId != null) {
                    Log.d("SavedFragment", "Refrescando playlist ID: $playlistId")
                    playlistAdapter.clearCacheForPlaylist(playlistId)
                    forceReloadPlaylists()
                    navController.currentBackStackEntry?.savedStateHandle?.remove<String>("PLAYLIST_UPDATED_ID")
                }
            }

        // Initial Loads
        reloadPlaylistsInitial()
        favoritesViewModel.loadAllFavorites()
    }

    private fun initViews(view: View) {
        chipGroup = view.findViewById(R.id.chipGroup)
        playlistsContainerView = view.findViewById(R.id.playlistsContainerView)
        favoritesRecyclerView = view.findViewById(R.id.favoritesRecyclerView)
        downloadsItemContainer = view.findViewById(R.id.downloadsItemContainer)
        
        playlistRecyclerView = view.findViewById(R.id.playlistList)
        newPlaylistOption = view.findViewById(R.id.noPlaylistContainer)
        userProfileImage = view.findViewById(R.id.userProfile)
        
        Utils.loadUserProfile(requireContext(), userProfileImage)
        setupChipStyles()
    }

    private fun setupAdapters() {
        // 1. Playlist Adapter
        playlistAdapter = PlaylistAdapter(
            viewType = PlaylistAdapter.VIEW_TYPE_GRID,
            onClick = { playlist ->
                val bundle = Bundle().apply { putString("playlistId", playlist.id) }
                findNavController().navigate(R.id.action_savedFragment_to_playlistFragment, bundle)
            },
            onPlaylistLongClick = { playlist, _ -> showPlaylistOptions(playlist) },
            onSettingsClick = { playlist -> showPlaylistOptions(playlist) }
        )
        playlistRecyclerView.adapter = playlistAdapter
        playlistRecyclerView.layoutManager = LinearLayoutManager(context)

        // 2. Favorites Adapter (Reusable SearchResultAdapter)
        favoritesAdapter = SearchResultAdapter().apply {
            // Songs
            onSongClick = { (song, bitmap) ->
                val songs = favoritesViewModel.favoriteSongs.value ?: emptyList()
                val currentIndex = songs.indexOfFirst { it.id == song.id }

                if (currentIndex != -1) {
                    val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), bitmap, song.id) }
                    val songArrayList = ArrayList(songs)

                    val intent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                        action = MusicPlaybackService.ACTION_PLAY
                        putExtra(MusicPlaybackService.EXTRA_CURRENT_SONG, song)
                        putExtra(MusicPlaybackService.EXTRA_CURRENT_INDEX, currentIndex)
                        putExtra(MusicPlaybackService.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                        putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, songArrayList)
                        putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE, QueueSource.FAVORITE_SONGS)
                        putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE_ID, "favorites")
                    }
                    requireContext().startService(intent)
                }
            }
            
            onSettingsClick = { song ->
                val bottomSheet = SongOptionsBottomSheet(
                    song = song,
                    onSeeSongClick = { selectedSong ->
                        val bundle = Bundle().apply { putParcelable("song", selectedSong) }
                        findNavController().navigate(R.id.action_savedFragment_to_detailedSongFragment, bundle)
                    },
                    onFavoriteToggled = { toggledSong ->
                        favoritesViewModel.toggleFavoriteSong(toggledSong)
                    },
                    onAddToPlaylistClick = { songToAdd ->
                         val selectPlaylistBottomSheet = SelectPlaylistBottomSheet(
                            song = songToAdd,
                            onNoPlaylistsFound = {
                                findNavController().navigate(R.id.action_savedFragment_to_createPlaylistFragment)
                            }
                        )
                        selectPlaylistBottomSheet.show(parentFragmentManager, "SelectPlaylistBottomSheet")
                    },
                    onDownloadClick = { songToDownload ->
                         downloadViewModel.downloadSong(songToDownload)
                    },
                    onRemoveDownloadClick = { songToDelete ->
                         downloadViewModel.deleteSong(songToDelete)
                    },
                    onGoToAlbumClick = { albumId ->
                         val bundle = Bundle().apply { putString("albumId", albumId) }
                         findNavController().navigate(R.id.action_savedFragment_to_albumFragment, bundle)
                    },
                    onGoToArtistClick = { artist ->
                         val bundle = Bundle().apply { putString("artistId", artist.id) }
                         findNavController().navigate(R.id.action_savedFragment_to_artistFragment, bundle)
                    }
                )
                bottomSheet.show(childFragmentManager, bottomSheet.tag)
            }
            onFavoriteClick = { song, _ ->
                favoritesViewModel.toggleFavoriteSong(song)
            }

            // Albums
            onAlbumClick = { album ->
                val bundle = Bundle().apply { putString("albumId", album.id) }
                findNavController().navigate(R.id.action_savedFragment_to_albumFragment, bundle)
            }
            onAlbumSettingsClick = { album ->
                 val bottomSheet = AlbumOptionsBottomSheet(
                    album = album,
                    onGoToAlbumClick = {
                        val bundle = Bundle().apply { putString("albumId", it.id) }
                        findNavController().navigate(R.id.action_savedFragment_to_albumFragment, bundle)
                    },
                    onGoToArtistClick = { albumObj ->
                        val artists = albumObj.artists
                        if (artists.isNotEmpty()) {
                            if (artists.size > 1) {
                                val selector = ArtistSelectorBottomSheet(artists) { selectedArtist ->
                                    val bundle = Bundle().apply { putString("artistId", selectedArtist.id) }
                                    findNavController().navigate(R.id.action_savedFragment_to_artistFragment, bundle)
                                }
                                selector.show(parentFragmentManager, "ArtistSelectorBottomSheet")
                            } else {
                                val artist = artists[0]
                                val bundle = Bundle().apply { putString("artistId", artist.id) }
                                findNavController().navigate(R.id.action_savedFragment_to_artistFragment, bundle)
                            }
                        }
                    },
                    onViewDetailsClick = {
                         // Option to see detailed info, currently just navigating to fragment
                         val bundle = Bundle().apply { putString("albumId", it.id) }
                         findNavController().navigate(R.id.action_savedFragment_to_albumFragment, bundle)
                    }
                 )
                 bottomSheet.show(childFragmentManager, bottomSheet.tag)
            }

            // Artists
            onArtistClick = { artist, _ ->
                val bundle = Bundle().apply { putString("artistId", artist.id) }
                findNavController().navigate(R.id.action_savedFragment_to_artistFragment, bundle)
            }
            onArtistSettingsClick = { artist ->
                 val bottomSheet = ArtistOptionsBottomSheet(
                    artist = artist,
                    onGoToArtistClick = {
                        val bundle = Bundle().apply { putString("artistId", it.id) }
                        findNavController().navigate(R.id.action_savedFragment_to_artistFragment, bundle)
                    },
                    onViewDetailsClick = {
                         // Option to see detailed info, currently just navigating to fragment
                         val bundle = Bundle().apply { putString("artistId", it.id) }
                         findNavController().navigate(R.id.action_savedFragment_to_artistFragment, bundle)
                    }
                 )
                 bottomSheet.show(childFragmentManager, bottomSheet.tag)
            }
        }
        
        favoritesRecyclerView.layoutManager = LinearLayoutManager(context)
        favoritesRecyclerView.setItemViewCacheSize(20) // Optimización básica
        // Por defecto no asignamos adapter aquí, se asigna en show...View()
    }
    
    private fun setupChipListener() {
        chipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val newCheckedId = checkedIds.firstOrNull() ?: View.NO_ID
            
            // Si el nuevo es el mismo que el anterior, no hacemos nada (ya está seleccionado)
            if (newCheckedId == lastCheckedId) return@setOnCheckedStateChangeListener

            // 1. Animar el chip que se DESELECCIONA
            if (lastCheckedId != View.NO_ID) {
                group.findViewById<Chip>(lastCheckedId)?.let { chip ->
                    AnimationsUtils.animateChip(chip, false) // Escala
                    AnimationsUtils.animateChipColor(chip, false) // Color
                }
            }

            // 2. Animar el chip que se SELECCIONA
            if (newCheckedId != View.NO_ID) {
                group.findViewById<Chip>(newCheckedId)?.let { chip ->
                    AnimationsUtils.animateChip(chip, true) // Escala
                    AnimationsUtils.animateChipColor(chip, true) // Color
                }
            }

            lastCheckedId = newCheckedId

            when (newCheckedId) {
                R.id.chipPlaylists -> showPlaylistsView()
                R.id.chipSongs -> showSongsView()
                R.id.chipArtists -> showArtistsView()
                R.id.chipAlbums -> showAlbumsView()
            }
        }
    }

    private fun setupChipStyles() {
        val font = ResourcesCompat.getFont(requireContext(), R.font.unageo_medium)
        // Set initial state for animations
        val checkedId = chipGroup.checkedChipId
        lastCheckedId = checkedId
        
        chipGroup.children.forEach { view ->
             if (view is Chip) {
                 view.typeface = font
                 // Ensure visual state matches logical state initially
                 if (view.id == checkedId) {
                     AnimationsUtils.animateChipColor(view, true)
                 } else {
                     AnimationsUtils.animateChipColor(view, false)
                 }
             }
        }
    }
    
    // --- Visibility Modes ---
    
    private fun showPlaylistsView() {
        favoritesAdapter.submitList(emptyList()) // Ensure adapter is clean
        playlistsContainerView.visibility = View.VISIBLE
        favoritesRecyclerView.visibility = View.GONE
        favoritesRecyclerView.adapter = null // Ensure hidden view is clean
    }

    private fun showSongsView() {
        favoritesAdapter.submitList(emptyList()) // Clean adapter state to prevent ghosting
        // Clear immediate view state
        favoritesRecyclerView.adapter = null // Detach FIRST to prevent ghosting
        playlistsContainerView.visibility = View.GONE
        favoritesRecyclerView.visibility = View.VISIBLE
        
        lifecycleScope.launch {
             val songs = favoritesViewModel.favoriteSongs.value ?: emptyList()
             
             val items = withContext(Dispatchers.Default) {
                 songs.map { SearchResult.SongItem(it) }
             }
             
             // Re-attach and submit
             favoritesRecyclerView.adapter = favoritesAdapter
             favoritesAdapter.submitList(items)
        }
    }

    private fun showArtistsView() {
        favoritesAdapter.submitList(emptyList()) // Clean adapter state to prevent ghosting
        favoritesRecyclerView.adapter = null // Detach FIRST to prevent ghosting
        playlistsContainerView.visibility = View.GONE
        favoritesRecyclerView.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            val artists = favoritesViewModel.favoriteArtists.value ?: emptyList()
            val items = withContext(Dispatchers.Default) {
                artists.map { SearchResult.ArtistItem(it) }
            }
            
            // Re-attach and submit
            favoritesRecyclerView.adapter = favoritesAdapter
            favoritesAdapter.submitList(items)
        }
    }

    private fun showAlbumsView() {
        favoritesAdapter.submitList(emptyList()) // Clean adapter state to prevent ghosting
        favoritesRecyclerView.adapter = null // Detach FIRST to prevent ghosting
        playlistsContainerView.visibility = View.GONE
        favoritesRecyclerView.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            val albums = favoritesViewModel.favoriteAlbums.value ?: emptyList()
            val items = withContext(Dispatchers.Default) {
                albums.map { SearchResult.AlbumItem(it) }
            }
            
            // Re-attach and submit
            favoritesRecyclerView.adapter = favoritesAdapter
            favoritesAdapter.submitList(items)
        }
    }

    private fun observeFavorites() {
        favoritesViewModel.favoriteSongs.observe(viewLifecycleOwner) { songs ->
            if (chipGroup.checkedChipId == R.id.chipSongs) {
                 lifecycleScope.launch {
                     val items = withContext(Dispatchers.Default) {
                         songs.map { SearchResult.SongItem(it) }
                     }
                     favoritesAdapter.submitList(items)
                 }
            }
            favoritesAdapter.favoriteSongIds = favoritesViewModel.favoriteSongIds.value ?: emptySet()
        }
        
        favoritesViewModel.favoriteSongIds.observe(viewLifecycleOwner) { ids ->
             favoritesAdapter.favoriteSongIds = ids
        }

        favoritesViewModel.favoriteArtists.observe(viewLifecycleOwner) { artists ->
            if (chipGroup.checkedChipId == R.id.chipArtists) {
                 lifecycleScope.launch {
                    val items = withContext(Dispatchers.Default) {
                        artists.map { SearchResult.ArtistItem(it) }
                    }
                    favoritesAdapter.submitList(items)
                }
            }
        }

        favoritesViewModel.favoriteAlbums.observe(viewLifecycleOwner) { albums ->
            if (chipGroup.checkedChipId == R.id.chipAlbums) {
                 lifecycleScope.launch {
                    val items = withContext(Dispatchers.Default) {
                        albums.map { SearchResult.AlbumItem(it) }
                    }
                    favoritesAdapter.submitList(items)
                }
            }
        }
    }

    private fun setupClickListeners() {
        // Downloads Item Click
        downloadsItemContainer.setOnClickListener {
             findNavController().navigate(R.id.action_savedFragment_to_downloadedSongsFragment)
        }

        newPlaylistOption.setOnClickListener {
            findNavController().navigate(R.id.action_savedFragment_to_createPlaylistFragment)
        }
    }

    private fun showPlaylistOptions(playlist: Playlist) {
        val bottomSheet = PlaylistOptionsBottomSheet(
            playlist = playlist,
            playlistImageBitmap = null,
            onDeleteClick = { playlistToDelete ->
                playlistsListViewModel.deletePlaylist(playlistToDelete.id!!)
                showResonantSnackbar(
                    text = "Se ha borrado la lista correctamente",
                    colorRes = R.color.successColor,
                    iconRes = R.drawable.ic_success
                )
            },
            onEditClick = { playlistToEdit ->
                val bundle = Bundle().apply {
                    putParcelable("playlist", playlistToEdit)
                }
                findNavController().navigate(R.id.action_savedFragment_to_editPlaylistFragment, bundle)
            },
            onToggleVisibilityClick = { pl ->
                playlistsListViewModel.toggleVisibility(
                    playlistId = pl.id!!,
                    currentIsPublic = pl.isPublic ?: false,
                    onSuccess = { newIsPublic ->
                        val msg = if (newIsPublic) "Playlist ahora es pública" else "Playlist ahora es privada"
                        showResonantSnackbar(
                            text = msg,
                            colorRes = R.color.successColor,
                            iconRes = R.drawable.ic_success
                        )
                    },
                    onError = { err ->
                        showResonantSnackbar(
                            text = "Error: $err",
                            colorRes = R.color.errorColor,
                            iconRes = R.drawable.ic_error
                        )
                    }
                )
            }
        )
        bottomSheet.show(childFragmentManager, bottomSheet.tag)
    }

    private fun reloadPlaylistsInitial() {
        if (playlistsListViewModel.playlists.value.isNullOrEmpty()) {
            forceReloadPlaylists()
        }
    }

    private fun forceReloadPlaylists() {
        playlistsListViewModel.loadMyPlaylists()
    }

    private fun updateEmptyView(playlists: List<Playlist>?) {
        val userManager = UserManager(requireContext())
        val userId = userManager.getUserId()

        if (userId == null || playlists.isNullOrEmpty()) {
            newPlaylistOption.visibility = View.VISIBLE
            playlistRecyclerView.visibility = View.GONE
        } else {
            newPlaylistOption.visibility = View.GONE
            playlistRecyclerView.visibility = View.VISIBLE
        }
    }
}
