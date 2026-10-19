package com.example.resonant.ui.fragments

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.example.resonant.R
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.services.ArtistService
import com.example.resonant.managers.UserManager
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.adapters.SongAdapter
import com.example.resonant.ui.bottomsheets.SelectPlaylistBottomSheet
import com.example.resonant.ui.bottomsheets.SongOptionsBottomSheet
import com.example.resonant.ui.components.ChartHero
import com.example.resonant.ui.components.ChartPeriodSelector
import com.example.resonant.ui.components.ResonantAccent
import com.example.resonant.ui.viewmodels.DownloadViewModel
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.ui.viewmodels.SongViewModel
import com.example.resonant.ui.viewmodels.TopChartsViewModel
import com.example.resonant.utils.Utils
import kotlinx.coroutines.launch
import com.example.resonant.utils.SnackbarUtils.favoriteSongFeedback

class TopChartsFragment : Fragment() {

    // ViewModels
    private lateinit var viewModel: TopChartsViewModel
    private lateinit var songViewModel: SongViewModel
    private lateinit var favoritesViewModel: FavoritesViewModel
    private lateinit var downloadViewModel: DownloadViewModel

    // Services
    private lateinit var artistService: ArtistService

    // UI
    private lateinit var songAdapter: SongAdapter
    private lateinit var rvSongs: RecyclerView
    private lateinit var rootLayout: androidx.coordinatorlayout.widget.CoordinatorLayout
    private lateinit var appBarLayout: com.google.android.material.appbar.AppBarLayout
    private lateinit var tvToolbarTitle: TextView // Nuevo
    private lateinit var progressBar: LottieAnimationView
    private lateinit var btnBack: ImageButton

    // Argumentos
    private var chartTitle: String = ""
    private var period: Int = 0
    private var isTrending: Boolean = false
    private var shouldResetScroll: Boolean = false // Flag para resetear scroll
    private var heroAnimated = false // La entrada del hero solo la primera vez, no al volver del back stack
    private var animateNextList = true // Entrada escalonada de filas en la próxima lista

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            chartTitle = it.getString("TITLE", "Top Chart")
            period = it.getInt("PERIOD", 0)
            isTrending = it.getBoolean("IS_TRENDING", false)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_top_charts, container, false)

        // Inicializar Servicios y ViewModels
        artistService = ApiClient.getArtistService(requireContext())
        setupViewModels()

        setupUI(view)
        setupObservers()

        viewModel.loadChartData(isTrending, period)

        return view
    }

    private fun setupViewModels() {
        viewModel = ViewModelProvider(this)[TopChartsViewModel::class.java]
        songViewModel = ViewModelProvider(requireActivity())[SongViewModel::class.java]
        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]
        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
    }

    private fun setupUI(view: View) {
        rootLayout = view.findViewById(R.id.rootLayout)
        appBarLayout = view.findViewById(R.id.appBarLayout)
        tvToolbarTitle = view.findViewById(R.id.tvToolbarTitle)
        btnBack = view.findViewById(R.id.btnBack)
        rvSongs = view.findViewById(R.id.rvSongs)
        progressBar = view.findViewById(R.id.lottieLoader)

        songAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_TOP_SONG)
        rvSongs.layoutManager = LinearLayoutManager(context)
        rvSongs.adapter = songAdapter

        // --- HERO POR CAPAS: verde para los rankings de canciones, azul para Tendencias ---
        val hero = view.findViewById<View>(R.id.chartHero)
        val accent = if (isTrending) ResonantAccent.TRENDING else ResonantAccent.SONGS
        val (title, subtitle) = chartTexts(period, isTrending)
        ChartHero.bind(
            hero,
            accent,
            eyebrow = if (isTrending) "Lo más viral" else "Ranking de canciones",
            title = title,
            subtitle = subtitle
        )
        tvToolbarTitle.text = title
        ChartHero.attachParallax(appBarLayout, hero, tvToolbarTitle)
        if (!heroAnimated) {
            ChartHero.animateIn(hero)
            heroAnimated = true
        }

        // --- SELECTOR DE PERIODO (no aplica a Tendencias) ---
        view.findViewById<View>(R.id.periodSelectorContainer).visibility =
            if (isTrending) View.GONE else View.VISIBLE
        ChartPeriodSelector(
            view.findViewById(R.id.periodSelector),
            accent.color,
            labels = listOf("Hoy", "Semana", "Mes", "Global"),
            initialIndex = period.coerceIn(0, 3)
        ) { newPeriod ->
            period = newPeriod
            shouldResetScroll = true
            animateNextList = true
            val (newTitle, newSubtitle) = chartTexts(newPeriod, false)
            ChartHero.setText(hero, newTitle, newSubtitle, animate = true)
            ChartHero.pulse(hero)
            tvToolbarTitle.text = newTitle
            viewModel.loadChartData(false, period)
        }

        // --- INTERACTIVIDAD DE CANCIONES ---
        val queueId = if (isTrending) "CHART_TRENDING" else "CHART_PERIOD_$period"
        setupAdapterListeners(songAdapter, queueId)

        // 4. Navegación Atrás
        btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    /** Título y subtítulo del hero para cada periodo (o para Tendencias). */
    private fun chartTexts(period: Int, isTrending: Boolean): Pair<String, String> = when {
        isTrending -> "Tendencias" to "Canciones con crecimiento explosivo en las últimas 24 horas"
        period == 0 -> "Top Diario" to "Lo más escuchado hoy"
        period == 1 -> "Top Semanal" to "Lo más escuchado esta semana"
        period == 2 -> "Top Mensual" to "Lo más escuchado este mes"
        period == 3 -> "Top Global" to "Lo que siempre suena"
        else -> "Éxitos" to "Lo más escuchado siempre"
    }

    private fun setupAdapterListeners(adapter: SongAdapter, queueId: String) {
        // A. CLICK EN LA CANCIÓN (REPRODUCIR)
        adapter.onItemClick = { (song, bitmap) ->
            val currentIndex = adapter.currentList.indexOfFirst { it.id == song.id }
            val songList = ArrayList(adapter.currentList)

            viewLifecycleOwner.lifecycleScope.launch {
                val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), it, song.id) }

                val playIntent = Intent(context, MusicPlaybackService::class.java).apply {
                    action = MusicPlaybackService.Companion.ACTION_PLAY
                    putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_SONG, song)
                    putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_INDEX, currentIndex)
                    putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                    putParcelableArrayListExtra(MusicPlaybackService.Companion.SONG_LIST, songList)

                    // NOTA: Puedes crear un QueueSource.CHART si quieres diferenciarlo,
                    // o usar uno genérico y diferenciar por queueId.
                    putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE, QueueSource.HOME) // O QueueSource.PLAYLIST
                    putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE_ID, queueId)
                }
                requireContext().startService(playIntent)
            }
        }

        // B. FAVORITO (CORAZÓN)
        adapter.onFavoriteClick = { song, _ ->
            favoritesViewModel.toggleFavoriteSong(song, favoriteSongFeedback(song))
        }

        // C. OPCIONES (3 PUNTOS)
        adapter.onSettingsClick = { song ->
            lifecycleScope.launch {
                // Use artists already embedded in the song from the API
                song.artistName = song.artists.joinToString(", ") { it.name }

                val bottomSheet = SongOptionsBottomSheet(
                    song = song,
                    onSeeSongClick = { selectedSong ->
                        val bundle = Bundle().apply { putParcelable("song", selectedSong) }
                        findNavController().navigate(R.id.action_topChartsFragment_to_detailedSongFragment, bundle)
                    },
                    onFavoriteToggled = { toggledSong ->
                        favoritesViewModel.toggleFavoriteSong(toggledSong, favoriteSongFeedback(toggledSong))
                    },
                    onAddToPlaylistClick = { songToAdd ->
                        val sheet = SelectPlaylistBottomSheet(
                            song = songToAdd,
                            onNoPlaylistsFound = {
                                // Navegación global para crear playlist
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

    private fun setupObservers() {
        // 1. Lista de canciones del Chart
        viewModel.songs.observe(viewLifecycleOwner) { songs ->
            songAdapter.submitList(songs) {
                // Callback cuando el DiffUtil termina de actualizar
                if (shouldResetScroll) {
                    rvSongs.scrollToPosition(0)
                    appBarLayout.setExpanded(true, true)
                    shouldResetScroll = false
                }
                if (animateNextList && songs.isNotEmpty()) {
                    animateNextList = false
                    rvSongs.scheduleLayoutAnimation()
                }
            }
            // Aseguramos que se actualicen los favoritos/descargas sobre la nueva lista
            favoritesViewModel.loadFavoriteSongs()
        }

        // 2. Loading
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            rvSongs.visibility = if (isLoading) View.INVISIBLE else View.VISIBLE
        }

        // 3. Canción sonando actualmente (Para resaltar en verde)
        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            currentSong?.let {
                songAdapter.setCurrentPlayingSong(it.id)
            }
        }

        // 4. Estado de Favoritos
        favoritesViewModel.favoriteSongIds.observe(viewLifecycleOwner) { songIds ->
            songAdapter.favoriteSongIds = songIds
            if (songAdapter.currentList.isNotEmpty()) {
                songAdapter.notifyDataSetChanged()
            }
        }

        // 5. Estado de Descargas
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                downloadViewModel.downloadedSongIds.collect { downloadedIds ->
                    songAdapter.downloadedSongIds = downloadedIds
                }
            }
        }
    }
}