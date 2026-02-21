package com.example.resonant.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.managers.PlaylistManager
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.adapters.SongAdapter
import com.example.resonant.ui.bottomsheets.PlaylistOptionsBottomSheet
import com.example.resonant.ui.bottomsheets.SelectPlaylistBottomSheet
import com.example.resonant.ui.bottomsheets.SongOptionsBottomSheet
import com.example.resonant.ui.viewmodels.DownloadViewModel
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.ui.viewmodels.PlaylistDetailViewModel
import com.example.resonant.ui.viewmodels.PlaylistDetailViewModelFactory
import com.example.resonant.ui.viewmodels.PlaylistScreenState
import com.example.resonant.ui.viewmodels.SongViewModel
import com.example.resonant.ui.views.NonScrollableLinearLayoutManager
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.example.resonant.utils.Utils
import kotlinx.coroutines.launch

class PlaylistFragment : BaseFragment(R.layout.fragment_playlist) {

    private lateinit var songAdapter: SongAdapter

    private lateinit var noSongsInPlaylistText: TextView
    private lateinit var playlistNumberOfTracks: TextView
    private lateinit var playlistName: TextView
    private lateinit var playlistText: TextView
    private lateinit var playlistOwner: TextView
    private lateinit var playlistDuration: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var playlistLoader: LottieAnimationView
    private lateinit var playlistCoverImage: ImageView

    private lateinit var songViewModel: SongViewModel
    private lateinit var favoritesViewModel: FavoritesViewModel

    private var firstRenderDone = false
    private lateinit var settingsButtonContainer: FrameLayout
    private var isReadOnly = false

    // New views for redesigned layout
    private var playlistDescriptionView: TextView? = null
    private var tvVisibilityBadge: TextView? = null
    private var visibilityBadge: View? = null

    private lateinit var downloadViewModel: DownloadViewModel

    private val playlistViewModel: PlaylistDetailViewModel by viewModels {
        val playlistManager = PlaylistManager(requireContext())
        PlaylistDetailViewModelFactory(playlistManager)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupRecyclerView()
        setupViewModels()

        playlistViewModel.screenState.observe(viewLifecycleOwner) { state ->
            updateUI(state)
        }

        val playlistId = arguments?.getString("playlistId")
        if (playlistId != null) {
            val current = playlistViewModel.screenState.value
            val hasDataCached = (current?.playlistDetails != null) || !(current?.songs.isNullOrEmpty())
            if (!hasDataCached) {
                playlistViewModel.loadPlaylistScreenData(playlistId)
            }
        } else {
            Toast.makeText(requireContext(), "No se encontró la playlist", Toast.LENGTH_SHORT).show()
        }

        // --- CAMBIO IMPORTANTE ---
        // Usamos requireActivity() para acceder al ViewModel global de descargas
        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]

        lifecycleScope.launch {
            downloadViewModel.downloadedSongIds.collect { downloadedIds ->
                songAdapter.downloadedSongIds = downloadedIds
                // Refrescamos visualmente si ya hay canciones cargadas
                if (songAdapter.currentList.isNotEmpty()) {
                    songAdapter.notifyDataSetChanged()
                }
            }
        }

        view.findViewById<ImageButton>(R.id.arrowGoBackButton).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        // Si es una playlist de otro usuario (modo lectura), ocultamos el botón de opciones
        isReadOnly = arguments?.getBoolean("isReadOnly", false) ?: false
        if (isReadOnly) {
            settingsButtonContainer.visibility = View.GONE
        } else {
            settingsButtonContainer.setOnClickListener {
                showPlaylistOptions()
            }
        }

        setupAdapterClickListeners(playlistId)
    }

    private fun showPlaylistOptions() {
        val playlist = playlistViewModel.screenState.value?.playlistDetails

        if (playlist == null || playlist.id.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "Error: No se ha cargado la información.", Toast.LENGTH_SHORT).show()
            return
        }

        val bottomSheet = PlaylistOptionsBottomSheet(
            playlist = playlist,
            playlistImageBitmap = null,
            onDeleteClick = { playlistToDelete ->
                playlistViewModel.deleteCurrentPlaylist(
                    playlistId = playlistToDelete.id!!,
                    onSuccess = {
                        showResonantSnackbar(
                            text = "Playlist eliminada correctamente",
                            colorRes = R.color.successColor,
                            iconRes = R.drawable.ic_success
                        )
                        findNavController().previousBackStackEntry?.savedStateHandle?.set(
                            "PLAYLIST_UPDATED_ID", "DELETED"
                        )
                        findNavController().popBackStack()
                    },
                    onError = { errorMsg ->
                        Toast.makeText(requireContext(), "Error: $errorMsg", Toast.LENGTH_SHORT).show()
                    }
                )
            },
            onEditClick = { playlistToEdit ->
                val bundle = Bundle().apply { putParcelable("playlist", playlistToEdit) }
                findNavController().navigate(R.id.action_playlistFragment_to_editPlaylistFragment, bundle)
            },
            onToggleVisibilityClick = { pl ->
                playlistViewModel.toggleVisibility(
                    playlistId = pl.id!!,
                    currentIsPublic = pl.isPublic ?: false,
                    onSuccess = { newIsPublic ->
                        val msg = if (newIsPublic) "Playlist ahora es pública" else "Playlist ahora es privada"
                        showResonantSnackbar(text = msg, colorRes = R.color.successColor, iconRes = R.drawable.ic_success)
                    },
                    onError = { err ->
                        Toast.makeText(requireContext(), "Error: $err", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        )
        bottomSheet.show(parentFragmentManager, bottomSheet.tag)
    }

    private fun initViews(view: View) {
        noSongsInPlaylistText = view.findViewById(R.id.noSongsInPlaylist)
        playlistName = view.findViewById(R.id.playlistName)
        playlistText = view.findViewById(R.id.playlistText)
        playlistOwner = view.findViewById(R.id.playlistOwner)
        playlistDuration = view.findViewById(R.id.playlistDuration)
        playlistNumberOfTracks = view.findViewById(R.id.playlistNumberOfTracks)
        recyclerView = view.findViewById(R.id.songList)
        playlistLoader = view.findViewById(R.id.lottieLoader)
        settingsButtonContainer = view.findViewById(R.id.settingsBackground)
        playlistCoverImage = view.findViewById(R.id.playlistCoverImage)
        // New views from redesigned layout
        playlistDescriptionView = view.findViewById(R.id.playlistDescription)
        tvVisibilityBadge = view.findViewById(R.id.tvVisibilityBadge)
        visibilityBadge = view.findViewById(R.id.visibilityBadge)
    }

    private fun updateUI(state: PlaylistScreenState) {
        val isInitialLoad = state.isLoading && state.songs.isEmpty() && state.playlistDetails == null
        if (isInitialLoad) {
            playlistLoader.visibility = View.VISIBLE
            playlistLoader.playAnimation()
            recyclerView.visibility = View.GONE
            noSongsInPlaylistText.visibility = View.GONE
            playlistCoverImage.visibility = View.GONE
        } else {
            playlistLoader.cancelAnimation()
            playlistLoader.visibility = View.GONE
            playlistCoverImage.visibility = View.VISIBLE
        }

        val showEmptyState = !state.isLoading && state.songs.isEmpty()
        if (showEmptyState) {
            noSongsInPlaylistText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            noSongsInPlaylistText.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }

        state.playlistDetails?.let { details ->
            playlistName.text = details.name
            playlistText.text = details.name

            val count = details.numberOfTracks ?: state.songs.size
            playlistNumberOfTracks.text = when {
                count == 0 -> "Sin canciones"
                count == 1 -> "1 canción"
                else -> "$count canciones"
            }

            details.duration?.let { seconds ->
                playlistDuration.text = Utils.formatDuration(seconds.toInt())
            }

            // Description
            val desc = details.description?.trim()
            if (!desc.isNullOrEmpty()) {
                playlistDescriptionView?.text = desc
                playlistDescriptionView?.visibility = View.VISIBLE
            } else {
                playlistDescriptionView?.visibility = View.GONE
            }

            // Visibility badge
            val pub = details.isPublic ?: false
            tvVisibilityBadge?.text = if (pub) "Pública" else "Privada"
            visibilityBadge?.visibility = if (isReadOnly) View.GONE else View.VISIBLE

            val imageUrl = details.imageUrl
            if (!imageUrl.isNullOrEmpty()) {
                Glide.with(this)
                    .load(imageUrl)
                    .error(R.drawable.ic_playlist_stack)
                    .centerCrop()
                    .into(playlistCoverImage)
            } else {
                playlistCoverImage.setImageResource(R.drawable.ic_playlist_stack)
            }
        }

        playlistOwner.text = state.ownerName

        songAdapter.submitList(state.songs.toList()) {
            val playingId = songViewModel.currentSongLiveData.value?.id
            songAdapter.setCurrentPlayingSong(playingId)
            songAdapter.notifyDataSetChanged()
        }

        if (!firstRenderDone && state.songs.isNotEmpty()) firstRenderDone = true
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_FULL)
        recyclerView.apply {
            adapter = songAdapter
            layoutManager = NonScrollableLinearLayoutManager(requireContext())
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setHasFixedSize(false)
            itemAnimator?.changeDuration = 120
        }
    }

    private fun setupViewModels() {
        songViewModel = ViewModelProvider(requireActivity())[SongViewModel::class.java]
        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            currentSong?.let {
                val playingId = currentSong.id
                songAdapter.setCurrentPlayingSong(playingId)
            }
        }

        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]
        favoritesViewModel.loadFavoriteSongs()
        favoritesViewModel.favoriteSongIds.observe(viewLifecycleOwner) { songIds ->
            songAdapter.favoriteSongIds = songIds
        }
    }

    private fun setupAdapterClickListeners(playlistId: String?) {
        songAdapter.onItemClick = { (song, bitmap) ->
            val currentIndex = songAdapter.currentList.indexOfFirst { it.id == song.id }
            val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), it, song.id) }
            val songList = ArrayList(songAdapter.currentList)

            val playIntent = Intent(context, MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.Companion.ACTION_PLAY
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_SONG, song)
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_INDEX, currentIndex)
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE, QueueSource.PLAYLIST)
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE_ID, playlistId)
                putParcelableArrayListExtra(MusicPlaybackService.Companion.SONG_LIST, songList)
                val playlistName = playlistViewModel.screenState.value?.playlistDetails?.name
                putExtra("EXTRA_QUEUE_SOURCE_NAME", playlistName)
            }
            requireContext().startService(playIntent)
        }

        songAdapter.onFavoriteClick = { song, wasFavorite ->
            favoritesViewModel.toggleFavoriteSong(song)
        }

        songAdapter.onSettingsClick = { song ->
            viewLifecycleOwner.lifecycleScope.launch {
                // Use artists already embedded in the song from the API
                song.artistName = song.artists.joinToString(", ") { it.name }

                val bottomSheet = SongOptionsBottomSheet(
                    song = song,
                    onSeeSongClick = { selectedSong ->
                        val bundle = Bundle().apply { putParcelable("song", selectedSong) }
                        findNavController().navigate(
                            R.id.action_playlistFragment_to_detailedSongFragment,
                            bundle
                        )
                    },
                    onFavoriteToggled = { toggledSong ->
                        favoritesViewModel.toggleFavoriteSong(toggledSong)
                    },
                    // Null si es de solo lectura: el bottomsheet ocultará la opción eliminar
                    playlistId = if (isReadOnly) null else playlistId,
                    onAddToPlaylistClick = { songToAdd ->
                        val sheet = SelectPlaylistBottomSheet(
                            song = songToAdd,
                            onNoPlaylistsFound = {
                                findNavController().navigate(R.id.action_global_to_createPlaylistFragment)
                            }
                        )
                        sheet.show(parentFragmentManager, "SelectPlaylistBottomSheet")
                    },
                    onRemoveFromPlaylistClick = { songToRemove, id ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                playlistViewModel.removeSongFromPlaylist(
                                    songId = songToRemove.id,
                                    playlistId = id,
                                    context = requireContext()
                                )
                                showResonantSnackbar(
                                    text = "Canción eliminada de la playlist",
                                    colorRes = R.color.successColor,
                                    iconRes = R.drawable.ic_success
                                )

                                findNavController().previousBackStackEntry?.savedStateHandle?.set(
                                    "PLAYLIST_UPDATED_ID",
                                    id
                                )

                            } catch (e: Exception) {
                                Log.e("PlaylistFragment", "Error al eliminar canción", e)
                                showResonantSnackbar(
                                    text = "Error al eliminar canción",
                                    colorRes = R.color.errorColor,
                                    iconRes = R.drawable.ic_error
                                )
                            }
                        }
                    },
                    onDownloadClick = { songToDownload ->
                        downloadViewModel.downloadSong(songToDownload)
                    },
                    onRemoveDownloadClick = { songToDelete ->
                        downloadViewModel.deleteSong(songToDelete)
                    },
                    onGoToAlbumClick = { albumId ->
                        val bundle = Bundle().apply { putString("albumId", albumId) }
                        findNavController().navigate(R.id.albumFragment, bundle)
                    },
                    onGoToArtistClick = { artist ->
                         val bundle = Bundle().apply { 
                             putString("artistId", artist.id)
                             putString("artistName", artist.name)
                             putString("artistImageUrl", artist.url)
                        }
                        findNavController().navigate(R.id.artistFragment, bundle)
                    }
                )
                bottomSheet.show(parentFragmentManager, "SongOptionsBottomSheet")
            }
        }
    }
}