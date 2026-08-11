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
import androidx.recyclerview.widget.ItemTouchHelper
import com.airbnb.lottie.LottieAnimationView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.aria.AriaScreenContextHolder
import com.example.resonant.data.models.Playlist
import com.example.resonant.managers.PlaylistManager
import com.example.resonant.managers.UserManager
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
    private lateinit var playlistFavoriteButton: ImageButton
    private lateinit var reorderControls: View
    private lateinit var reorderPlaylistButton: TextView
    private lateinit var savePlaylistOrderButton: TextView
    private lateinit var cancelPlaylistOrderButton: TextView
    private lateinit var reorderHint: TextView

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
    private val ariaContextSource = Any()

    private val playlistViewModel: PlaylistDetailViewModel by viewModels {
        val playlistManager = PlaylistManager(requireContext())
        PlaylistDetailViewModelFactory(playlistManager)
    }

    override fun onResume() {
        super.onResume()
        reportVisiblePlaylist()
    }

    override fun onPause() {
        AriaScreenContextHolder.clearEntity(ariaContextSource)
        super.onPause()
    }

    private fun reportVisiblePlaylist() {
        if (!isResumed) return
        val playlist = playlistViewModel.screenState.value?.playlistDetails ?: return
        if (!isOwnedByCurrentUser(playlist)) {
            AriaScreenContextHolder.clearEntity(ariaContextSource)
            return
        }
        val id = arguments?.getString("playlistId") ?: arguments?.getString("playlist_id")
        if (!id.isNullOrBlank() || playlist.name.isNotBlank()) {
            AriaScreenContextHolder.update(
                screen = "playlist_detail",
                entity = AriaScreenContextHolder.VisibleEntity("playlist", id, playlist.name),
                source = ariaContextSource
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupRecyclerView()
        setupViewModels()

        playlistViewModel.screenState.observe(viewLifecycleOwner) { state ->
            updateUI(state)
        }
        playlistViewModel.error.observe(viewLifecycleOwner) { message ->
            if (!message.isNullOrBlank()) {
                showResonantSnackbar(
                    message,
                    R.color.adviseColor,
                    R.drawable.ic_warning
                )
            }
        }

        val playlistId = arguments?.getString("playlistId") ?: arguments?.getString("playlist_id")
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

        playlistFavoriteButton.setOnClickListener {
            playlistViewModel.togglePlaylistSaved(
                onSuccess = { saved ->
                    playlistId?.let { changedId ->
                        findNavController()
                            .previousBackStackEntry
                            ?.savedStateHandle
                            ?.set("PLAYLIST_UPDATED_ID", changedId)
                    }
                    showResonantSnackbar(
                        if (saved) "Playlist guardada en tu biblioteca"
                        else "Playlist eliminada de tu biblioteca",
                        R.color.successColor,
                        R.drawable.ic_success
                    )
                },
                onError = { message ->
                    showResonantSnackbar(
                        message,
                        R.color.errorColor,
                        R.drawable.ic_warning
                    )
                }
            )
        }

        reorderPlaylistButton.setOnClickListener {
            playlistViewModel.beginReorder()
        }
        cancelPlaylistOrderButton.setOnClickListener {
            playlistViewModel.cancelReorder()
        }
        savePlaylistOrderButton.setOnClickListener {
            playlistViewModel.saveReorderedTracks(
                onSuccess = {
                    playlistId?.let(::notifyPlaylistOrderChanged)
                    showResonantSnackbar(
                        "Orden de la playlist guardado",
                        R.color.successColor,
                        R.drawable.ic_success
                    )
                },
                onError = { message ->
                    showResonantSnackbar(
                        message,
                        R.color.errorColor,
                        R.drawable.ic_warning
                    )
                }
            )
        }

        setupAdapterClickListeners(playlistId)
    }

    private fun showPlaylistOptions() {
        val playlist = playlistViewModel.screenState.value?.playlistDetails

        if (playlist == null || playlist.id.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "Error: No se ha cargado la información.", Toast.LENGTH_SHORT).show()
            return
        }

        if (isEffectivelyReadOnly(playlist)) {
            showResonantSnackbar(
                "Solo el propietario puede modificar esta playlist",
                R.color.adviseColor,
                R.drawable.ic_warning
            )
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
        playlistFavoriteButton = view.findViewById(R.id.playlistFavoriteButton)
        reorderControls = view.findViewById(R.id.reorderControls)
        reorderPlaylistButton = view.findViewById(R.id.reorderPlaylistButton)
        savePlaylistOrderButton = view.findViewById(R.id.savePlaylistOrderButton)
        cancelPlaylistOrderButton = view.findViewById(R.id.cancelPlaylistOrderButton)
        reorderHint = view.findViewById(R.id.reorderHint)
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
            reportVisiblePlaylist()
            val readOnly = isEffectivelyReadOnly(details)
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
            visibilityBadge?.visibility = if (readOnly) View.GONE else View.VISIBLE
            settingsButtonContainer.visibility = if (readOnly) View.GONE else View.VISIBLE
            playlistFavoriteButton.setImageResource(
                if (details.isSaved) R.drawable.ic_favorite
                else R.drawable.ic_favorite_border
            )

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

        val canEditOrder = state.canReorder &&
            state.playlistDetails?.let(::isEffectivelyReadOnly) != true
        reorderControls.visibility = if (canEditOrder) View.VISIBLE else View.GONE
        reorderPlaylistButton.visibility = if (state.reorderMode) View.GONE else View.VISIBLE
        savePlaylistOrderButton.visibility = if (state.reorderMode) View.VISIBLE else View.GONE
        cancelPlaylistOrderButton.visibility = if (state.reorderMode) View.VISIBLE else View.GONE
        reorderHint.visibility = if (state.reorderMode) View.VISIBLE else View.GONE
        savePlaylistOrderButton.isEnabled = !state.savingOrder
        savePlaylistOrderButton.alpha = if (state.savingOrder) 0.5f else 1f

        songAdapter.playlistTrackIds = state.tracks.map { it.playlistTrackId }
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
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val enabled = playlistViewModel.screenState.value?.reorderMode == true
                return if (enabled) {
                    makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                } else {
                    makeMovementFlags(0, 0)
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                return playlistViewModel.previewTrackMove(from, to)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun isLongPressDragEnabled(): Boolean {
                return playlistViewModel.screenState.value?.reorderMode == true
            }
        }).attachToRecyclerView(recyclerView)
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
        songAdapter.onItemClickAtPosition = click@ { song, bitmap, position ->
            if (playlistViewModel.screenState.value?.reorderMode == true) {
                return@click
            }
            val currentIndex = position
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

        songAdapter.onSettingsClick = settings@ { song ->
            if (playlistViewModel.screenState.value?.reorderMode == true) {
                return@settings
            }
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
                    playlistId = if (
                        playlistViewModel.screenState.value?.playlistDetails
                            ?.let(::isEffectivelyReadOnly) == true
                    ) null else playlistId,
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
                    },
                    onAddToPlaymixClick = { songToAdd ->
                        val sheet = com.example.resonant.ui.bottomsheets.SelectPlaymixBottomSheet(
                            song = songToAdd,
                            onNoPlaymixesFound = { findNavController().navigate(R.id.action_global_to_playmixListFragment) }
                        )
                        sheet.show(parentFragmentManager, "SelectPlaymixBottomSheet")
                    }
                )
                bottomSheet.show(parentFragmentManager, "SongOptionsBottomSheet")
            }
        }
    }

    private fun notifyPlaylistOrderChanged(playlistId: String) {
        requireContext().startService(
            Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_PLAYLIST_MODIFIED
                putExtra(MusicPlaybackService.EXTRA_PLAYLIST_ID, playlistId)
            }
        )
        findNavController().previousBackStackEntry?.savedStateHandle?.set(
            "PLAYLIST_UPDATED_ID",
            playlistId
        )
    }

    private fun isEffectivelyReadOnly(playlist: Playlist): Boolean {
        if (isReadOnly || playlist.canEdit == false) return true
        val currentUserId = UserManager(requireContext()).getUserId()
        return !currentUserId.isNullOrBlank() &&
            !playlist.userId.isNullOrBlank() &&
            currentUserId != playlist.userId
    }

    private fun isOwnedByCurrentUser(playlist: Playlist): Boolean {
        if (isReadOnly || playlist.canEdit == false) return false
        val currentUserId = UserManager(requireContext()).getUserId()
        if (!currentUserId.isNullOrBlank() && !playlist.userId.isNullOrBlank()) {
            return currentUserId == playlist.userId
        }
        return playlist.canEdit == true
    }
}
