package com.example.resonant.ui.fragments

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.example.resonant.R
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.adapters.SongAdapter
import com.example.resonant.ui.bottomsheets.SelectPlaylistBottomSheet
import com.example.resonant.ui.bottomsheets.SongOptionsBottomSheet
import com.example.resonant.ui.viewmodels.DownloadViewModel
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.ui.viewmodels.HistoryViewModel
import com.example.resonant.ui.viewmodels.SongViewModel
import com.example.resonant.utils.Utils
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch

class HistoryFragment : Fragment() {

    private lateinit var viewModel: HistoryViewModel
    private lateinit var songViewModel: SongViewModel
    private lateinit var favoritesViewModel: FavoritesViewModel
    private lateinit var downloadViewModel: DownloadViewModel

    private lateinit var songAdapter: SongAdapter
    private lateinit var rvHistory: RecyclerView
    private lateinit var appBarLayout: AppBarLayout
    private lateinit var tvSongCount: TextView
    private lateinit var tvToolbarTitle: TextView
    private lateinit var lottieLoader: LottieAnimationView
    private lateinit var emptyStateLayout: View
    private lateinit var chipGroupLimit: ChipGroup
    private lateinit var chip20: Chip
    private lateinit var chip50: Chip
    private lateinit var chip100: Chip
    private lateinit var btnBack: ImageButton

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViewModels()
        initViews(view)
        setupAdapter()
        setupChipListeners()
        setupScrollBehavior()
        setupObservers()

        // Carga inicial: 50 canciones (chip por defecto)
        viewModel.loadHistory(50)
    }

    private fun setupViewModels() {
        viewModel = ViewModelProvider(this)[HistoryViewModel::class.java]
        songViewModel = ViewModelProvider(requireActivity())[SongViewModel::class.java]
        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]
        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
    }

    private fun initViews(view: View) {
        appBarLayout = view.findViewById(R.id.appBarLayout)
        rvHistory = view.findViewById(R.id.rvHistory)
        tvSongCount = view.findViewById(R.id.tvSongCount)
        tvToolbarTitle = view.findViewById(R.id.tvToolbarTitle)
        lottieLoader = view.findViewById(R.id.lottieLoader)
        emptyStateLayout = view.findViewById(R.id.emptyStateLayout)
        chipGroupLimit = view.findViewById(R.id.chipGroupLimit)
        chip20 = view.findViewById(R.id.chip20)
        chip50 = view.findViewById(R.id.chip50)
        chip100 = view.findViewById(R.id.chip100)
        btnBack = view.findViewById(R.id.btnBack)

        btnBack.setOnClickListener { findNavController().navigateUp() }
    }

    private fun setupAdapter() {
        songAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_FULL)
        rvHistory.layoutManager = LinearLayoutManager(requireContext())
        rvHistory.adapter = songAdapter

        // Reproducción al tocar canción
        songAdapter.onItemClick = { (song, bitmap) ->
            val currentIndex = songAdapter.currentList.indexOfFirst { it.id == song.id }
            val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), it, song.id) }
            val songList = ArrayList(songAdapter.currentList)

            val playIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_PLAY
                putExtra(MusicPlaybackService.EXTRA_CURRENT_SONG, song)
                putExtra(MusicPlaybackService.EXTRA_CURRENT_INDEX, currentIndex)
                putExtra(MusicPlaybackService.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, songList)
                putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE, QueueSource.HOME)
                putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE_ID, "HISTORY")
            }
            requireContext().startService(playIntent)
        }

        // Favorito
        songAdapter.onFavoriteClick = { song, _ ->
            favoritesViewModel.toggleFavoriteSong(song)
        }

        // Opciones (3 puntos)
        songAdapter.onSettingsClick = { song ->
            lifecycleScope.launch {
                song.artistName = song.artists.joinToString(", ") { it.name }

                val bottomSheet = SongOptionsBottomSheet(
                    song = song,
                    onSeeSongClick = { selectedSong ->
                        val bundle = Bundle().apply { putParcelable("song", selectedSong) }
                        findNavController().navigate(R.id.action_historyFragment_to_detailedSongFragment, bundle)
                    },
                    onFavoriteToggled = { toggledSong ->
                        favoritesViewModel.toggleFavoriteSong(toggledSong)
                    },
                    onAddToPlaylistClick = { songToAdd ->
                        val sheet = SelectPlaylistBottomSheet(
                            song = songToAdd,
                            onNoPlaylistsFound = {
                                findNavController().navigate(R.id.action_global_to_createPlaylistFragment)
                            }
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

    private fun setupChipListeners() {
        chipGroupLimit.setOnCheckedStateChangeListener { _, checkedIds ->
            val limit = when {
                checkedIds.contains(R.id.chip20) -> 20
                checkedIds.contains(R.id.chip50) -> 50
                checkedIds.contains(R.id.chip100) -> 100
                else -> 50
            }
            if (limit != viewModel.currentLimit.value) {
                viewModel.loadHistory(limit)
                // Scroll al inicio cuando cambia el límite
                rvHistory.scrollToPosition(0)
                appBarLayout.setExpanded(true, true)
            }
        }
    }

    private fun setupScrollBehavior() {
        appBarLayout.addOnOffsetChangedListener { appBar, verticalOffset ->
            val totalScrollRange = appBar.totalScrollRange
            val percentage = Math.abs(verticalOffset).toFloat() / totalScrollRange.toFloat()
            val triggerPoint = 0.75f
            tvToolbarTitle.alpha = if (percentage > triggerPoint) {
                (percentage - triggerPoint) / (1f - triggerPoint)
            } else {
                0f
            }
        }
    }

    private fun setupObservers() {
        viewModel.songs.observe(viewLifecycleOwner) { songs ->
            songAdapter.submitList(songs)

            val count = songs.size
            tvSongCount.text = "$count ${if (count == 1) "canción" else "canciones"}"
            emptyStateLayout.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
            rvHistory.visibility = if (songs.isEmpty()) View.GONE else View.VISIBLE

            favoritesViewModel.loadFavoriteSongs()
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            lottieLoader.visibility = if (isLoading) View.VISIBLE else View.GONE
            if (isLoading) lottieLoader.playAnimation() else lottieLoader.cancelAnimation()
            if (!isLoading) rvHistory.visibility = View.VISIBLE
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                emptyStateLayout.visibility = View.VISIBLE
                rvHistory.visibility = View.GONE
                val tvEmptyTitle = view?.findViewById<TextView>(R.id.tvEmptyTitle)
                tvEmptyTitle?.text = "Error al cargar el historial"
            }
        }

        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            currentSong?.let { songAdapter.setCurrentPlayingSong(it.id) }
        }

        favoritesViewModel.favoriteSongIds.observe(viewLifecycleOwner) { songIds ->
            songAdapter.favoriteSongIds = songIds
            if (songAdapter.currentList.isNotEmpty()) songAdapter.notifyDataSetChanged()
        }

        lifecycleScope.launch {
            downloadViewModel.downloadedSongIds.collect { downloadedIds ->
                songAdapter.downloadedSongIds = downloadedIds
                if (songAdapter.currentList.isNotEmpty()) songAdapter.notifyDataSetChanged()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refrescar al volver al fragmento
        viewModel.refresh()
    }
}
