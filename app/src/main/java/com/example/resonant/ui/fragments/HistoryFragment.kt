package com.example.resonant.ui.fragments

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.data.models.Song
import com.example.resonant.managers.CapabilitiesManager
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.adapters.HeaderViewAdapter
import com.example.resonant.ui.adapters.SongAdapter
import com.example.resonant.ui.bottomsheets.SelectPlaylistBottomSheet
import com.example.resonant.ui.bottomsheets.SongOptionsBottomSheet
import com.example.resonant.ui.components.ChartHero
import com.example.resonant.ui.components.ChartPeriodSelector
import com.example.resonant.ui.components.CollectionActions
import com.example.resonant.ui.components.CollectionState
import com.example.resonant.ui.components.ResonantAccent
import com.example.resonant.ui.viewmodels.DownloadViewModel
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.ui.viewmodels.HistoryViewModel
import com.example.resonant.ui.viewmodels.SongViewModel
import com.example.resonant.utils.Utils
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch
import com.example.resonant.utils.SnackbarUtils.favoriteSongFeedback

class HistoryFragment : Fragment() {

    private companion object {
        val LIMITS = listOf(20, 50, 100)
    }

    private val accent = ResonantAccent.HISTORY

    private lateinit var viewModel: HistoryViewModel
    private lateinit var songViewModel: SongViewModel
    private lateinit var favoritesViewModel: FavoritesViewModel
    private lateinit var downloadViewModel: DownloadViewModel

    private lateinit var songAdapter: SongAdapter
    private lateinit var actionsHeader: HeaderViewAdapter
    private lateinit var actionsView: View
    private lateinit var rvHistory: RecyclerView
    private lateinit var appBarLayout: AppBarLayout
    private lateinit var hero: View
    private lateinit var tvToolbarTitle: TextView
    private lateinit var shimmerLoading: ShimmerFrameLayout
    private lateinit var stateView: View
    private lateinit var btnBack: ImageButton

    private var heroAnimated = false // La entrada del hero solo la primera vez, no al volver del back stack
    private var animateNextList = true // Entrada escalonada de filas en la próxima lista

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
        setupHero(view)
        setupObservers()

        // La carga inicial la dispara onResume() (siempre se llama justo después
        // de onViewCreated), respetando el caché de HistoryViewModel para evitar
        // una doble petición de red.
    }

    private fun setupViewModels() {
        viewModel = ViewModelProvider(this)[HistoryViewModel::class.java]
        songViewModel = ViewModelProvider(requireActivity())[SongViewModel::class.java]
        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]
        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
    }

    private fun initViews(view: View) {
        appBarLayout = view.findViewById(R.id.appBarLayout)
        hero = view.findViewById(R.id.chartHero)
        rvHistory = view.findViewById(R.id.rvHistory)
        tvToolbarTitle = view.findViewById(R.id.tvToolbarTitle)
        shimmerLoading = view.findViewById(R.id.shimmerLoading)
        stateView = view.findViewById(R.id.stateView)
        btnBack = view.findViewById(R.id.btnBack)

        btnBack.setOnClickListener { findNavController().navigateUp() }

        // "Tus estadísticas" solo si el servidor la ofrece; sin ella, Historial queda como siempre.
        // Historial no ofrece borrar escuchas (el historial es inmutable por decisión de producto).
        val btnListeningStats = view.findViewById<View>(R.id.btnListeningStats)
        btnListeningStats.isVisible = CapabilitiesManager.isListeningStatsEnabled()
        btnListeningStats.setOnClickListener {
            findNavController().navigate(R.id.action_historyFragment_to_listeningStatsFragment)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val enabled = CapabilitiesManager.refresh(requireContext()).listeningStats
            btnListeningStats.isVisible = enabled
        }
    }

    private fun setupHero(view: View) {
        ChartHero.bind(hero, accent, "Tu actividad", "Historial", "Lo último que has escuchado")
        ChartHero.attachParallax(appBarLayout, hero, tvToolbarTitle)
        if (!heroAnimated) {
            ChartHero.animateIn(hero)
            heroAnimated = true
        }

        val currentLimit = viewModel.currentLimit.value ?: 50
        ChartPeriodSelector(
            view.findViewById(R.id.limitSelector),
            accent.color,
            labels = LIMITS.map { "Últimas $it" },
            initialIndex = LIMITS.indexOf(currentLimit).coerceAtLeast(0)
        ) { index ->
            animateNextList = true
            viewModel.loadHistory(LIMITS[index])
            rvHistory.scrollToPosition(0)
            appBarLayout.setExpanded(true, true)
        }
    }

    private fun setupAdapter() {
        songAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_FULL)
        // El LayoutManager tiene que existir antes de inflar la cabecera con rvHistory
        // como padre: RecyclerView lo necesita para generar los LayoutParams.
        rvHistory.layoutManager = LinearLayoutManager(requireContext())

        // "Reproducir / Aleatorio" van como cabecera de la lista: hacen scroll con ella.
        actionsView = layoutInflater.inflate(R.layout.view_collection_actions, rvHistory, false).apply {
            setPadding(paddingLeft, 4.dp, paddingRight, 12.dp)
        }
        CollectionActions.bind(
            actionsView,
            accent,
            onPlayAll = { playSongs(songAdapter.currentList, 0) },
            onShuffle = { playSongs(songAdapter.currentList.shuffled(), 0) }
        )
        actionsHeader = HeaderViewAdapter(actionsView).apply { isVisible = false }

        rvHistory.adapter = ConcatAdapter(actionsHeader, songAdapter)

        // Reproducción al tocar canción
        songAdapter.onItemClick = { (song, bitmap) ->
            val currentIndex = songAdapter.currentList.indexOfFirst { it.id == song.id }
            playSongs(songAdapter.currentList, currentIndex, song, bitmap)
        }

        // Favorito
        songAdapter.onFavoriteClick = { song, _ ->
            favoritesViewModel.toggleFavoriteSong(song, favoriteSongFeedback(song))
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
                        favoritesViewModel.toggleFavoriteSong(toggledSong, favoriteSongFeedback(toggledSong))
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

    /** Reproduce [songs] desde [index]; si no viene la canción concreta (Reproducir/Aleatorio), usa la de [index]. */
    private fun playSongs(
        songs: List<Song>,
        index: Int,
        song: Song? = null,
        bitmap: Bitmap? = null
    ) {
        if (index !in songs.indices) return
        val current = song ?: songs[index]
        val songList = ArrayList(songs)

        viewLifecycleOwner.lifecycleScope.launch {
            val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), it, current.id) }

            val playIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_PLAY
                putExtra(MusicPlaybackService.EXTRA_CURRENT_SONG, current)
                putExtra(MusicPlaybackService.EXTRA_CURRENT_INDEX, index)
                putExtra(MusicPlaybackService.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, songList)
                putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE, QueueSource.HOME)
                putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE_ID, "HISTORY")
            }
            requireContext().startService(playIntent)
        }
    }

    private fun setupObservers() {
        viewModel.songs.observe(viewLifecycleOwner) { songs ->
            songAdapter.submitList(songs) {
                if (animateNextList && songs.isNotEmpty()) {
                    animateNextList = false
                    rvHistory.scheduleLayoutAnimation()
                }
            }

            val count = songs.size
            ChartHero.setMeta(hero, if (count == 0) null else "$count ${if (count == 1) "canción" else "canciones"}")
            actionsHeader.isVisible = songs.isNotEmpty()
            render()

            favoritesViewModel.loadFavoriteSongs()
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { render() }
        viewModel.error.observe(viewLifecycleOwner) { render() }

        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            currentSong?.let { songAdapter.setCurrentPlayingSong(it.id) }
        }

        favoritesViewModel.favoriteSongIds.observe(viewLifecycleOwner) { songIds ->
            songAdapter.favoriteSongIds = songIds
            if (songAdapter.currentList.isNotEmpty()) songAdapter.notifyDataSetChanged()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                downloadViewModel.downloadedSongIds.collect { downloadedIds ->
                    songAdapter.downloadedSongIds = downloadedIds
                }
            }
        }
    }

    /** Skeleton mientras carga sin datos; si no hay filas, estado de error (con reintentar) o vacío. */
    private fun render() {
        val isLoading = viewModel.isLoading.value == true
        val songs = viewModel.songs.value
        val hasRows = !songs.isNullOrEmpty()

        val showSkeleton = isLoading && !hasRows
        if (showSkeleton != shimmerLoading.isVisible) {
            shimmerLoading.isVisible = showSkeleton
            if (showSkeleton) shimmerLoading.startShimmer() else shimmerLoading.stopShimmer()
        }

        when {
            isLoading || hasRows || songs == null -> CollectionState.hide(stateView)
            viewModel.error.value != null -> CollectionState.show(
                stateView, accent, R.drawable.ic_recent,
                title = "No se pudo cargar el historial",
                subtitle = "Revisa tu conexión e inténtalo de nuevo",
                actionText = "Reintentar",
                onAction = { viewModel.loadHistory(forceRefresh = true) }
            )
            else -> CollectionState.show(
                stateView, accent, R.drawable.ic_recent,
                title = "Aún no has escuchado nada",
                subtitle = "Las canciones que escuches aparecerán aquí",
                actionText = "Explorar música",
                onAction = {
                    requireActivity().findViewById<BottomNavigationView>(R.id.bottom_navigation)
                        ?.selectedItemId = R.id.exploreFragment
                }
            )
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        // Refrescar al volver al fragmento
        viewModel.refresh()
    }
}
