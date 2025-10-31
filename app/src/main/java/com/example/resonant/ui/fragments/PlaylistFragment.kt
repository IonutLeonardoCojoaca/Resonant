package com.example.resonant.ui.fragments

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.example.resonant.ui.views.NonScrollableLinearLayoutManager
import com.example.resonant.ui.viewmodels.PlaylistDetailViewModel
import com.example.resonant.ui.viewmodels.PlaylistDetailViewModelFactory
import com.example.resonant.managers.PlaylistManager
import com.example.resonant.playback.QueueSource
import com.example.resonant.R
import com.example.resonant.ui.viewmodels.SongViewModel
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.example.resonant.ui.adapters.SongAdapter
import com.example.resonant.ui.bottomsheets.SongOptionsBottomSheet
import com.example.resonant.utils.Utils
import com.example.resonant.data.network.ApiClient
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.viewmodels.FavoriteItem
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.google.android.material.imageview.ShapeableImageView
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

    private var img0: ShapeableImageView? = null
    private var img1: ShapeableImageView? = null
    private var img2: ShapeableImageView? = null
    private var img3: ShapeableImageView? = null

    private lateinit var songViewModel : SongViewModel
    private lateinit var favoritesViewModel: FavoritesViewModel

    private var firstRenderDone = false

    private val playlistViewModel: PlaylistDetailViewModel by viewModels {
        val service = ApiClient.getService(requireContext())
        val playlistManager = PlaylistManager(service)
        PlaylistDetailViewModelFactory(playlistManager)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupRecyclerView()
        setupViewModels()

        // En PlaylistFragment.kt, dentro de onViewCreated -> playlistViewModel.screenState.observe

        playlistViewModel.screenState.observe(viewLifecycleOwner) { state ->
            Log.d("PlaylistFragment", "Nuevo estado: isLoading=${state.isLoading}, songs=${state.songs.size}")

            // --- Lógica de Visibilidad ---
            val collageContainer = view.findViewById<View>(R.id.playlistCollageContainer)

            val isInitialLoad = state.isLoading && state.songs.isEmpty() && state.playlistDetails == null
            if (isInitialLoad) {
                playlistLoader.visibility = View.VISIBLE
                playlistLoader.playAnimation()
                recyclerView.visibility = View.GONE
                noSongsInPlaylistText.visibility = View.GONE
                collageContainer.visibility = View.GONE // Ocultar collage durante la carga inicial
            } else {
                playlistLoader.cancelAnimation()
                playlistLoader.visibility = View.GONE

                // ✅ --- INICIO DE LA CORRECCIÓN ---
                // Una vez que la carga termina, el collage SIEMPRE es visible.
                collageContainer.visibility = View.VISIBLE
                // Y siempre intentamos actualizarlo. El ViewModel ya nos dará los placeholders si es necesario.
                updateCollage(state.collageBitmaps)
                // --- FIN DE LA CORRECCIÓN ---
            }

            val showEmptyState = !state.isLoading && state.songs.isEmpty()
            if (showEmptyState) {
                noSongsInPlaylistText.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                noSongsInPlaylistText.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
            }

            // --- Actualización de Datos (resto del código igual) ---
            state.playlistDetails?.let { details ->
                playlistName.text = details.name
                playlistText.text = details.name
                details.numberOfTracks?.let { count ->
                    playlistNumberOfTracks.text = "$count canciones"
                }
                details.duration?.let { seconds ->
                    playlistDuration.text = Utils.formatDuration(seconds.toInt())
                }
            }
            playlistOwner.text = state.ownerName

            songAdapter.submitList(state.songs.toList()) {
                val playingId = songViewModel.currentSongLiveData.value?.id
                songAdapter.setCurrentPlayingSong(playingId)
            }

            if (!firstRenderDone && state.songs.isNotEmpty()) firstRenderDone = true
        }

        val playlistId = arguments?.getString("playlistId")
        if (playlistId != null) {
            val current = playlistViewModel.screenState.value
            val hasDataCached = (current?.playlistDetails != null) || !(current?.songs.isNullOrEmpty())
            if (!hasDataCached) {
                playlistLoader.visibility = View.VISIBLE
                playlistLoader.playAnimation()
                playlistViewModel.loadPlaylistScreenData(playlistId, requireContext())
            }
        } else {
            Toast.makeText(requireContext(), "No se encontró la playlist", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<ImageButton>(R.id.arrowGoBackButton).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        setupAdapterClickListeners(playlistId)
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

        img0 = view.findViewById(R.id.img0)
        img1 = view.findViewById(R.id.img1)
        img2 = view.findViewById(R.id.img2)
        img3 = view.findViewById(R.id.img3)
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

    private fun updateCollage(bitmaps: List<Bitmap?>) {
        val placeholder = ContextCompat.getDrawable(requireContext(), R.drawable.ic_playlist_stack)
        val imgViews = listOfNotNull(img0, img1, img2, img3)

        imgViews.forEachIndexed { index, imageView ->
            val bitmapToShow = bitmaps.getOrNull(index)

            if (bitmapToShow != null) {
                imageView.setImageBitmap(bitmapToShow)
            } else {
                imageView.setImageDrawable(placeholder)
            }
        }
    }

    private fun setupViewModels() {
        songViewModel = ViewModelProvider(requireActivity())[SongViewModel::class.java]
        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            currentSong?.let {
                val playingId = currentSong?.id
                Log.i("Reproduciendo", "PlaylistFragment: $playingId")
                songAdapter.setCurrentPlayingSong(playingId)
            }
        }

        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]
        favoritesViewModel.loadFavoriteSongs()
        favoritesViewModel.favorites.observe(viewLifecycleOwner) { favorites ->
            val songIds = favorites.filterIsInstance<FavoriteItem.SongItem>().map { it.song.id }.toSet()
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
                val artistNames = playlistViewModel.getArtistsForSong(song.id)
                song.artistName = artistNames

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
                    playlistId = playlistId,
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
                    }
                )
                bottomSheet.show(parentFragmentManager, "SongOptionsBottomSheet")
            }
        }
    }
}