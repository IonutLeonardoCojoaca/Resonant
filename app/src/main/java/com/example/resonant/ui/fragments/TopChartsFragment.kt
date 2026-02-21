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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
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
import com.example.resonant.ui.viewmodels.DownloadViewModel
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.ui.viewmodels.SongViewModel
import com.example.resonant.ui.viewmodels.TopChartsViewModel
import com.example.resonant.utils.Utils
import kotlinx.coroutines.launch

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
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvToolbarTitle: TextView // Nuevo
    private lateinit var progressBar: LottieAnimationView
    private lateinit var btnBack: ImageButton

    // Argumentos
    private var chartTitle: String = ""
    private var startColor: String = "#000000"
    private var endColor: String = "#000000"
    private var period: Int = 0
    private var isTrending: Boolean = false
    private var shouldResetScroll: Boolean = false // Flag para resetear scroll

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            chartTitle = it.getString("TITLE", "Top Chart")
            startColor = it.getString("START_COLOR", "#333333")
            endColor = it.getString("END_COLOR", "#000000")
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
        // Buscamos las vistas
        rootLayout = view.findViewById(R.id.rootLayout)
        appBarLayout = view.findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.appBarLayout)
        val headerBackground = view.findViewById<View>(R.id.viewHeaderBackground)
        tvTitle = view.findViewById(R.id.tvChartTitle)
        tvSubtitle = view.findViewById(R.id.tvChartSubtitle)
        tvToolbarTitle = view.findViewById(R.id.tvToolbarTitle) // Nuevo
        btnBack = view.findViewById(R.id.btnBack)
        rvSongs = view.findViewById(R.id.rvSongs)
        progressBar = view.findViewById(R.id.lottieLoader)

        // --- MAGIA VISUAL: MOSTRAR TÍTULO EN TOOLBAR AL HACER SCROLL ---
        appBarLayout.addOnOffsetChangedListener { appBar, verticalOffset ->
            val totalScrollRange = appBar.totalScrollRange
            val percentage = Math.abs(verticalOffset).toFloat() / totalScrollRange.toFloat()

            // Solo mostrar cuando está colapsado más del 75%
            // De 0% a 75% -> alpha = 0
            // De 75% a 100% -> alpha transiciona de 0 a 1
            val triggerPoint = 0.75f
            if (percentage > triggerPoint) {
                // Normalizar el rango restante (0.25) a 0.0-1.0
                val alpha = (percentage - triggerPoint) / (1f - triggerPoint)
                tvToolbarTitle.alpha = alpha
            } else {
                tvToolbarTitle.alpha = 0f
            }
        }

        // Botones de tipo de chart y descripción de trending
        val chartTypeButtonsContainer = view.findViewById<View>(R.id.chartTypeButtonsContainer)
        val trendingDescriptionCard = view.findViewById<View>(R.id.trendingDescriptionCard)
        
        val btnDaily = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDaily)
        val btnWeekly = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnWeekly)
        val btnMonthly = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMonthly)
        val btnGlobal = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGlobal)

        // 3. Configurar RecyclerView y Adapter
        songAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_FULL)
        rvSongs.layoutManager = LinearLayoutManager(context)
        rvSongs.adapter = songAdapter

        // Función auxiliar
        fun dpToPx(dp: Int): Int {
            return (dp * resources.displayMetrics.density).toInt()
        }
        
        // --- FUNCIÓN PARA ALTERNAR ENTRE MODO CHARTS Y TRENDING ---
        fun updateUIMode(showTrending: Boolean) {
            if (showTrending) {
                // Modo Tendencias: Ocultar botones, mostrar descripción
                chartTypeButtonsContainer.visibility = View.GONE
                trendingDescriptionCard.visibility = View.VISIBLE
            } else {
                // Modo Charts: Mostrar botones, ocultar descripción
                chartTypeButtonsContainer.visibility = View.VISIBLE
                trendingDescriptionCard.visibility = View.GONE
            }
        }

        // --- CONFIGURAR BOTONES DE TIPO DE CHART ---
        // Función para actualizar estado de botones
        fun updateButtonStates(selectedPeriod: Int, showingTrending: Boolean) {
            val buttons = listOf(btnDaily, btnWeekly, btnMonthly, btnGlobal)
            val periods = listOf(0, 1, 2, 3)

            buttons.forEachIndexed { index, button ->
                // Solo marcamos si NO es trending y coincide el periodo
                if (!showingTrending && periods[index] == selectedPeriod) {
                    // Botón seleccionado
                    button.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(requireContext(), R.color.secondaryColorTheme)
                    )
                    button.strokeWidth = 0
                    button.setTextColor(Color.WHITE)
                } else {
                    // Botón no seleccionado
                    button.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        Color.TRANSPARENT
                    )
                    button.strokeColor = android.content.res.ColorStateList.valueOf(
                        Color.parseColor("#80FFFFFF") // Más visible
                    )
                    button.strokeWidth = dpToPx(1) // Usamos DP para consistencia
                    button.setTextColor(Color.parseColor("#CCFFFFFF"))
                }
            }
        }

        // Función para actualizar título y fondo
        // Función para actualizar título, subtítulo y fondo con animación
        fun updateChartTheme(period: Int, isTrending: Boolean) {
            val titleText: String
            val subtitleText: String // Quitamos el var, usamos val
            val colorStart: String
            val colorEnd: String

            if (isTrending) {
                titleText = "Tendencias"
                subtitleText = "" // Texto vacío para tendencias
                colorStart = "#eb3b5a"
                colorEnd = "#fa8231"
            } else {
                when (period) {
                    0 -> { // Diario
                        titleText = "Top Diario"
                        subtitleText = "Lo más escuchado hoy"
                        colorStart = "#FF9F40"
                        colorEnd = "#F53B57"
                    }
                    1 -> { // Semanal
                        titleText = "Top Semanal"
                        subtitleText = "Lo más escuchado esta semana"
                        colorStart = "#22A6B3"
                        colorEnd = "#006266"
                    }
                    2 -> { // Mensual
                        titleText = "Top Mensual"
                        subtitleText = "Lo más escuchado este mes"
                        colorStart = "#FFEAA7"
                        colorEnd = "#FAB1A0"
                    }
                    3 -> { // Global
                        titleText = "Top Global"
                        subtitleText = "Siempre escuchado"
                        colorStart = "#a55eea"
                        colorEnd = "#4b7bec"
                    }
                    else -> {
                        titleText = "Éxitos"
                        subtitleText = "Lo más escuchado siempre"
                        colorStart = "#333333"
                        colorEnd = "#000000"
                    }
                }
            }

            // 1. ANIMACIÓN DEL TÍTULO
            tvTitle.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction {
                    tvTitle.text = titleText
                    tvTitle.animate().alpha(1f).setDuration(150).start()
                }
                .start()

            // 2. ANIMACIÓN DEL SUBTÍTULO (CORREGIDA)
            tvSubtitle.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction {
                    tvSubtitle.text = subtitleText

                    // Lógica de visibilidad dentro de la animación
                    if (isTrending) {
                        tvSubtitle.visibility = View.GONE
                    } else {
                        tvSubtitle.visibility = View.VISIBLE
                        // Aquí estaba el error antes: animabas tvTitle en lugar de tvSubtitle
                        tvSubtitle.animate().alpha(1f).setDuration(150).start()
                    }
                }
                .start()

            // El título de la toolbar cambia directo (o puedes animarlo igual si quieres)
            tvToolbarTitle.text = titleText

            // 3. ANIMACIÓN DEL FONDO (Igual que antes)
            try {
                val targetColor1 = Color.parseColor(colorStart)
                val targetColor2 = Color.parseColor(colorEnd)

                val currentDrawable = headerBackground.background as? GradientDrawable
                val currentColors = if (currentDrawable != null) {
                    intArrayOf(
                        Color.parseColor(startColor),
                        Color.parseColor(endColor)
                    )
                } else {
                    intArrayOf(targetColor1, targetColor2)
                }

                val colorAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 400
                    interpolator = android.view.animation.DecelerateInterpolator()

                    addUpdateListener { animator ->
                        val fraction = animator.animatedValue as Float
                        val evaluator = android.animation.ArgbEvaluator()
                        val interpolatedColor1 = evaluator.evaluate(fraction, currentColors[0], targetColor1) as Int
                        val interpolatedColor2 = evaluator.evaluate(fraction, currentColors[1], targetColor2) as Int

                        val gradient = GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(interpolatedColor1, interpolatedColor2)
                        )
                        headerBackground.background = gradient
                    }
                }
                colorAnimator.start()

                startColor = colorStart
                endColor = colorEnd

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        updateButtonStates(period, isTrending)
        updateUIMode(isTrending)
        updateChartTheme(period, isTrending)

        btnDaily.setOnClickListener {
            if (isTrending || period != 0) {
                period = 0
                isTrending = false
                shouldResetScroll = true
                updateButtonStates(0, false)
                updateChartTheme(0, false)
                updateUIMode(false)
                viewModel.loadChartData(false, period)
            }
        }

        btnWeekly.setOnClickListener {
            if (isTrending || period != 1) {
                period = 1
                isTrending = false
                shouldResetScroll = true
                updateButtonStates(1, false)
                updateChartTheme(1, false)
                updateUIMode(false)
                viewModel.loadChartData(false, period)
            }
        }

        btnMonthly.setOnClickListener {
            if (isTrending || period != 2) {
                period = 2
                isTrending = false
                shouldResetScroll = true
                updateButtonStates(2, false)
                updateChartTheme(2, false)
                updateUIMode(false)
                viewModel.loadChartData(false, period)
            }
        }

        btnGlobal.setOnClickListener {
            if (isTrending || period != 3) {
                period = 3
                isTrending = false
                shouldResetScroll = true
                updateButtonStates(3, false)
                updateChartTheme(3, false)
                updateUIMode(false)
                viewModel.loadChartData(false, period)
            }
        }

        // --- INTERACTIVIDAD DE CANCIONES ---
        val queueId = if (isTrending) "CHART_TRENDING" else "CHART_PERIOD_$period"
        setupAdapterListeners(songAdapter, queueId)

        // 4. Navegación Atrás
        btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupAdapterListeners(adapter: SongAdapter, queueId: String) {
        // A. CLICK EN LA CANCIÓN (REPRODUCIR)
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

                // NOTA: Puedes crear un QueueSource.CHART si quieres diferenciarlo,
                // o usar uno genérico y diferenciar por queueId.
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE, QueueSource.HOME) // O QueueSource.PLAYLIST
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE_ID, queueId)
            }
            requireContext().startService(playIntent)
        }

        // B. FAVORITO (CORAZÓN)
        adapter.onFavoriteClick = { song, _ ->
            favoritesViewModel.toggleFavoriteSong(song)
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
                        favoritesViewModel.toggleFavoriteSong(toggledSong)
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
        lifecycleScope.launch {
            downloadViewModel.downloadedSongIds.collect { downloadedIds ->
                songAdapter.downloadedSongIds = downloadedIds
                if (songAdapter.currentList.isNotEmpty()) {
                    songAdapter.notifyDataSetChanged() // Refrescar iconos de descarga
                }
            }
        }
    }
}