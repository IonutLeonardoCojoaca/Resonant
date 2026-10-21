package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.chip.ChipGroup
import com.example.resonant.R
import com.example.resonant.ui.bottomsheets.ItemOptions
import com.example.resonant.ui.components.ChartHero
import com.example.resonant.ui.components.ResonantAccent
import com.example.resonant.ui.adapters.PublicPlaylistAdapter
import com.example.resonant.ui.viewmodels.PublicPlaylistsViewModel
import com.example.resonant.ui.viewmodels.PublicPlaylistsViewModel.Filter
import com.example.resonant.utils.SnackType
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar

class PublicPlaylistsFragment : Fragment() {

    private lateinit var viewModel: PublicPlaylistsViewModel
    private lateinit var playlistAdapter: PublicPlaylistAdapter

    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var rvPlaylists: RecyclerView
    private lateinit var shimmerLoading: ShimmerFrameLayout
    private lateinit var stateContainer: View
    private lateinit var tvStateTitle: TextView
    private lateinit var tvStateSubtitle: TextView
    private lateinit var btnStateAction: View
    private lateinit var hero: View
    private lateinit var chipGroupFilter: ChipGroup
    private lateinit var appBarLayout: AppBarLayout

    /** true solo mientras dura un pull-to-refresh lanzado por el usuario. */
    private var userRefreshing = false

    /** La entrada del hero solo la primera vez, no al volver del back stack. */
    private var heroAnimated = false

    /** Reproduce la entrada escalonada de filas en la próxima lista no vacía. */
    private var animateNextList = true

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_public_playlists, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupViewModel()
        setupRecyclerView()
        setupFilters()
        setupObservers()
        setupHero()

        viewModel.loadPublicPlaylists()
    }

    private fun initViews(view: View) {
        appBarLayout = view.findViewById(R.id.appBarLayout)
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)
        rvPlaylists = view.findViewById(R.id.rvPublicPlaylists)
        shimmerLoading = view.findViewById(R.id.shimmerLoading)
        stateContainer = view.findViewById(R.id.stateContainer)
        tvStateTitle = view.findViewById(R.id.tvStateTitle)
        tvStateSubtitle = view.findViewById(R.id.tvStateSubtitle)
        btnStateAction = view.findViewById(R.id.btnStateAction)
        hero = view.findViewById(R.id.chartHero)
        chipGroupFilter = view.findViewById(R.id.chipGroupFilter)

        swipeRefreshLayout.setProgressBackgroundColorSchemeResource(R.color.primaryColorTheme)
        swipeRefreshLayout.setColorSchemeResources(R.color.secondaryColorTheme)

        swipeRefreshLayout.setOnRefreshListener {
            userRefreshing = true
            viewModel.loadPublicPlaylists(forceRefresh = true)
        }

        btnStateAction.setOnClickListener {
            viewModel.loadPublicPlaylists(forceRefresh = true)
        }

        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            findNavController().navigateUp()
        }
    }

    /** Hero por capas con el acento rosa de "Listas" (el mismo que su tarjeta en Explorar). */
    private fun setupHero() {
        ChartHero.bind(
            hero,
            ResonantAccent.PLAYLISTS,
            eyebrow = "Playlists públicas",
            title = "Comunidad",
            subtitle = "Colecciones reales, listas para descubrir música nueva."
        )
        ChartHero.attachParallax(appBarLayout, hero, requireView().findViewById(R.id.tvToolbarTitle))
        if (!heroAnimated) {
            ChartHero.animateIn(hero)
            heroAnimated = true
        }
    }

    private fun setupViewModel() {
        viewModel = ViewModelProvider(this)[PublicPlaylistsViewModel::class.java]
    }

    private fun setupRecyclerView() {
        rvPlaylists.layoutManager = LinearLayoutManager(context)
        rvPlaylists.overScrollMode = View.OVER_SCROLL_NEVER
        // La entrada/cambio de filtro ya la anima el layoutAnimation; el item
        // animator por defecto se solaparía con él.
        rvPlaylists.itemAnimator = null

        playlistAdapter = PublicPlaylistAdapter { selectedPlaylist ->
            val bundle = Bundle().apply {
                putParcelable("playlist", selectedPlaylist)
                putString("playlistId", selectedPlaylist.id)
                putBoolean("isReadOnly", true)
            }
            findNavController().navigate(
                R.id.action_publicPlaylistsFragment_to_playlistFragment,
                bundle
            )
        }
        playlistAdapter.onItemLongClick = { ItemOptions.playlist(this, it) }
        rvPlaylists.adapter = playlistAdapter
    }

    private fun setupFilters() {
        chipGroupFilter.check(chipIdFor(viewModel.filter.value ?: Filter.ALL))
        chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when (checkedIds.firstOrNull()) {
                R.id.chipFilterOfficial -> Filter.OFFICIAL
                R.id.chipFilterCommunity -> Filter.COMMUNITY
                else -> Filter.ALL
            }
            if (filter != viewModel.filter.value) {
                animateNextList = true
                viewModel.setFilter(filter)
            }
        }
    }

    private fun chipIdFor(filter: Filter): Int = when (filter) {
        Filter.ALL -> R.id.chipFilterAll
        Filter.OFFICIAL -> R.id.chipFilterOfficial
        Filter.COMMUNITY -> R.id.chipFilterCommunity
    }

    private fun setupObservers() {
        viewModel.publicPlaylists.observe(viewLifecycleOwner) { playlists ->
            playlistAdapter.submitList(playlists) {
                if (animateNextList && playlists.isNotEmpty()) {
                    animateNextList = false
                    rvPlaylists.scrollToPosition(0)
                    rvPlaylists.scheduleLayoutAnimation()
                }
            }
            render()
        }

        viewModel.stats.observe(viewLifecycleOwner) { stats ->
            val playlists = if (stats.playlists == 1) "1 playlist" else "${stats.playlists} playlists"
            val creators = if (stats.creators == 1) "1 creador" else "${stats.creators} creadores"
            ChartHero.setMeta(hero, "$playlists · $creators")
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            if (!isLoading) {
                if (userRefreshing && viewModel.error.value != null &&
                    !viewModel.publicPlaylists.value.isNullOrEmpty()
                ) {
                    showResonantSnackbar("No se pudo actualizar la lista", SnackType.ERROR)
                }
                userRefreshing = false
            }
            render()
        }

        viewModel.error.observe(viewLifecycleOwner) { render() }
    }

    /**
     * Un único punto decide qué se ve: skeleton en la primera carga, spinner
     * solo en pull-to-refresh, y estado vacío o de error (con reintentar)
     * cuando no hay filas que enseñar.
     */
    private fun render() {
        val playlists = viewModel.publicPlaylists.value
        val isLoading = viewModel.isLoading.value == true
        val hasRows = !playlists.isNullOrEmpty()

        swipeRefreshLayout.isRefreshing = isLoading && userRefreshing

        val showSkeleton = isLoading && !userRefreshing && !hasRows
        if (showSkeleton != shimmerLoading.isVisible) {
            shimmerLoading.isVisible = showSkeleton
            if (showSkeleton) shimmerLoading.startShimmer() else shimmerLoading.stopShimmer()
        }

        val hasError = viewModel.error.value != null
        val showState = !isLoading && !hasRows && (playlists != null || hasError)
        stateContainer.isVisible = showState
        if (!showState) return

        if (hasError) {
            tvStateTitle.text = "No se pudieron cargar las playlists"
            tvStateSubtitle.text = "Revisa tu conexión e inténtalo de nuevo"
            btnStateAction.isVisible = true
            return
        }
        btnStateAction.isVisible = false
        when (viewModel.filter.value) {
            Filter.OFFICIAL -> {
                tvStateTitle.text = "Resonant aún no tiene playlists públicas"
                tvStateSubtitle.text = "Vuelve pronto, estamos preparando nuevas selecciones"
            }
            Filter.COMMUNITY -> {
                tvStateTitle.text = "Nadie ha compartido playlists todavía"
                tvStateSubtitle.text = "Sé el primero en compartir la tuya"
            }
            else -> {
                tvStateTitle.text = "No hay playlists públicas"
                tvStateSubtitle.text = "Sé el primero en compartir la tuya"
            }
        }
    }
}
