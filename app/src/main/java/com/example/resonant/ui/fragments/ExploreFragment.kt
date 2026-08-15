package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.resonant.R
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.Playlist
import com.example.resonant.data.models.StatsPeriod
import com.example.resonant.ui.adapters.ExploreAlbumAdapter
import com.example.resonant.ui.adapters.ExploreArtistAdapter
import com.example.resonant.ui.adapters.ExplorePlaylistAdapter
import com.example.resonant.ui.adapters.GenreAdapter
import com.example.resonant.ui.viewmodels.ExploreViewModel
import com.example.resonant.utils.ImageRequestHelper
import com.example.resonant.utils.ScrollHeaderBehavior
import com.example.resonant.utils.Utils
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch

class ExploreFragment : Fragment() {

    companion object {
        private const val GENRES_PER_BATCH = 6
    }

    private lateinit var viewModel: ExploreViewModel
    private lateinit var userProfileImage: ImageView
    private lateinit var scrollView: NestedScrollView

    private lateinit var playlistsSection: View
    private lateinit var albumsSection: View
    private lateinit var genresSection: View
    private lateinit var recommendedAlbumsSection: View
    private lateinit var recommendedArtistsSection: View
    private lateinit var genresMoreSection: View

    private lateinit var playlistsPlaceholder: TextView
    private lateinit var albumsPlaceholder: TextView
    private lateinit var genresPlaceholder: TextView
    private lateinit var recommendedAlbumsPlaceholder: TextView
    private lateinit var recommendedArtistsPlaceholder: TextView
    private lateinit var genresMorePlaceholder: TextView

    private lateinit var playlistsShimmer: ShimmerFrameLayout
    private lateinit var albumsShimmer: ShimmerFrameLayout
    private lateinit var genresShimmer: ShimmerFrameLayout
    private lateinit var recommendedAlbumsShimmer: ShimmerFrameLayout
    private lateinit var recommendedArtistsShimmer: ShimmerFrameLayout
    private lateinit var genresMoreShimmer: ShimmerFrameLayout

    private lateinit var collabPrimaryArtistImage: ImageView
    private lateinit var collabSecondaryArtistImage: ImageView
    private lateinit var collabPrimaryArtistName: TextView
    private lateinit var collabSecondaryArtistName: TextView
    private lateinit var collabPairMeta: TextView

    private lateinit var recyclerViewPlaylists: RecyclerView
    private lateinit var recyclerViewAlbums: RecyclerView
    private lateinit var recyclerViewGenres: RecyclerView
    private lateinit var recyclerViewRecommendedAlbums: RecyclerView
    private lateinit var recyclerViewRecommendedArtists: RecyclerView
    private lateinit var recyclerViewGenresMore: RecyclerView

    private lateinit var playlistAdapter: ExplorePlaylistAdapter
    private lateinit var albumAdapter: ExploreAlbumAdapter
    private lateinit var genreAdapter: GenreAdapter
    private lateinit var recommendedAlbumAdapter: ExploreAlbumAdapter
    private lateinit var recommendedArtistAdapter: ExploreArtistAdapter
    private lateinit var genreAdapterMore: GenreAdapter

    private var scrollBehavior: ScrollHeaderBehavior? = null
    private var scrollChangedListener: ViewTreeObserver.OnScrollChangedListener? = null

    private var playlistsCount = 0
    private var albumsCount = 0
    private var genresCount = 0
    private var recommendedAlbumsCount = 0
    private var recommendedArtistsCount = 0
    private var genresMoreCount = 0

    private var playlistsRequested = false
    private var albumsRequested = false
    private var genresRequested = false
    private var recommendedAlbumsRequested = false
    private var recommendedArtistsRequested = false
    private var progressiveLoadPending = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_explore, container, false)
        viewModel = ViewModelProvider(this)[ExploreViewModel::class.java]

        initViews(view)
        setupQuickActionChipStyles(view)
        setupSectionHeaders(view)
        setupRecyclerViews()
        setupObservers()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupFloatingSearchHeader(view)
        setupProgressiveLoading()

        viewModel.loadMostListenedArtists()
        scrollView.post { loadVisibleSections() }
    }

    override fun onDestroyView() {
        if (::scrollView.isInitialized) {
            scrollChangedListener?.let { listener ->
                if (scrollView.viewTreeObserver.isAlive) {
                    scrollView.viewTreeObserver.removeOnScrollChangedListener(listener)
                }
            }
        }
        scrollChangedListener = null
        scrollBehavior?.reset()
        scrollBehavior = null
        super.onDestroyView()
    }

    private fun initViews(view: View) {
        userProfileImage = view.findViewById(R.id.userProfile)
        scrollView = view.findViewById(R.id.exploreScrollView)

        playlistsSection = view.findViewById(R.id.playlistsSection)
        albumsSection = view.findViewById(R.id.albumsSection)
        genresSection = view.findViewById(R.id.genresSection)
        recommendedAlbumsSection = view.findViewById(R.id.recommendedAlbumsSection)
        recommendedArtistsSection = view.findViewById(R.id.recommendedArtistsSection)
        genresMoreSection = view.findViewById(R.id.genresMoreSection)

        playlistsPlaceholder = view.findViewById(R.id.playlistsPlaceholder)
        albumsPlaceholder = view.findViewById(R.id.albumsPlaceholder)
        genresPlaceholder = view.findViewById(R.id.genresPlaceholder)
        recommendedAlbumsPlaceholder = view.findViewById(R.id.recommendedAlbumsPlaceholder)
        recommendedArtistsPlaceholder = view.findViewById(R.id.recommendedArtistsPlaceholder)
        genresMorePlaceholder = view.findViewById(R.id.genresMorePlaceholder)

        playlistsShimmer = view.findViewById(R.id.playlistsShimmer)
        albumsShimmer = view.findViewById(R.id.albumsShimmer)
        genresShimmer = view.findViewById(R.id.genresShimmer)
        recommendedAlbumsShimmer = view.findViewById(R.id.recommendedAlbumsShimmer)
        recommendedArtistsShimmer = view.findViewById(R.id.recommendedArtistsShimmer)
        genresMoreShimmer = view.findViewById(R.id.genresMoreShimmer)

        collabPrimaryArtistImage = view.findViewById(R.id.collabPrimaryArtistImage)
        collabSecondaryArtistImage = view.findViewById(R.id.collabSecondaryArtistImage)
        collabPrimaryArtistName = view.findViewById(R.id.collabPrimaryArtistName)
        collabSecondaryArtistName = view.findViewById(R.id.collabSecondaryArtistName)
        collabPairMeta = view.findViewById(R.id.collabPairMeta)

        recyclerViewPlaylists = view.findViewById(R.id.recyclerViewPlaylists)
        recyclerViewAlbums = view.findViewById(R.id.recyclerViewAlbums)
        recyclerViewGenres = view.findViewById(R.id.recyclerViewGenres)
        recyclerViewRecommendedAlbums = view.findViewById(R.id.recyclerViewRecommendedAlbums)
        recyclerViewRecommendedArtists = view.findViewById(R.id.recyclerViewRecommendedArtists)
        recyclerViewGenresMore = view.findViewById(R.id.recyclerViewGenresMore)

        viewLifecycleOwner.lifecycleScope.launch { Utils.loadUserProfile(requireContext(), userProfileImage) }

        view.findViewById<View>(R.id.searchButton).setOnClickListener {
            findNavController().navigate(R.id.action_exploreFragment_to_searchFragment)
        }
        val openCollabFinder = View.OnClickListener {
            findNavController().navigate(R.id.action_exploreFragment_to_collab_finder_nav)
        }
        view.findViewById<View>(R.id.collabFinderSection).setOnClickListener(openCollabFinder)
        view.findViewById<View>(R.id.collabFinderButton).setOnClickListener(openCollabFinder)
        view.findViewById<View>(R.id.btnPopulares).setOnClickListener {
            navigateToChart("Top Diario", StatsPeriod.DAILY.value, false, "#FF9F40", "#F53B57")
        }
        view.findViewById<View>(R.id.btnTrending).setOnClickListener {
            navigateToChart("Tendencias", 0, true, "#eb3b5a", "#fa8231")
        }
        view.findViewById<View>(R.id.btnArtistas).setOnClickListener {
            findNavController().navigate(R.id.topArtistsFragment)
        }
        view.findViewById<View>(R.id.btnAlbumes).setOnClickListener {
            findNavController().navigate(R.id.action_exploreFragment_to_topAlbumsFragment)
        }
    }

    /**
     * Los chips ya no son un filtro seleccionable (nunca filtraban nada en la
     * propia pantalla: cada uno navega de inmediato a otra pantalla), así que
     * se quita el estado "checked"/animación de color asociada — ahora son
     * simples pills de acción con su ripple normal.
     */
    private fun setupQuickActionChipStyles(view: View) {
        val quickActionsGroup = view.findViewById<ChipGroup>(R.id.quickActionsGroup)
        val font = ResourcesCompat.getFont(requireContext(), R.font.unageo_medium)
        quickActionsGroup.children.forEach { chipView ->
            if (chipView is Chip) {
                chipView.typeface = font
            }
        }
    }

    private fun setupSectionHeaders(view: View) {
        configureHeader(
            view.findViewById(R.id.recommendedAlbumsHeader),
            title = "Álbumes para ti",
            onActionClick = { findNavController().navigate(R.id.action_exploreFragment_to_topAlbumsFragment) }
        )
        configureHeader(
            view.findViewById(R.id.albumsHeader),
            title = "Albumes nuevos",
            onActionClick = { findNavController().navigate(R.id.action_exploreFragment_to_topAlbumsFragment) }
        )
        configureHeader(
            view.findViewById(R.id.recommendedArtistsHeader),
            title = "Artistas recién añadidos",
            onActionClick = { findNavController().navigate(R.id.topArtistsFragment) }
        )
        configureHeader(
            view.findViewById(R.id.genresHeader),
            title = "Generos populares",
            onActionClick = { findNavController().navigate(R.id.action_exploreFragment_to_allGenresFragment) }
        )
        configureHeader(
            view.findViewById(R.id.playlistsHeader),
            title = "Explorar playlists",
            onActionClick = { findNavController().navigate(R.id.action_exploreFragment_to_publicPlaylistsFragment) }
        )
        configureHeader(
            view.findViewById(R.id.genresMoreHeader),
            title = "Menos escuchados",
            onActionClick = { findNavController().navigate(R.id.action_exploreFragment_to_allGenresFragment) }
        )
    }

    private fun configureHeader(header: View, title: String, onActionClick: () -> Unit) {
        header.findViewById<TextView>(R.id.sectionHeaderTitle).text = title
        header.findViewById<TextView>(R.id.sectionHeaderAction).setOnClickListener {
            onActionClick()
        }
    }

    private fun setupRecyclerViews() {
        playlistAdapter = ExplorePlaylistAdapter { navigateToPlaylist(it) }
        albumAdapter = ExploreAlbumAdapter { navigateToAlbum(it) }
        recommendedAlbumAdapter = ExploreAlbumAdapter { navigateToAlbum(it) }
        recommendedArtistAdapter = ExploreArtistAdapter { navigateToArtist(it) }

        recyclerViewPlaylists.setupHorizontalExploreList(playlistAdapter)
        recyclerViewAlbums.setupHorizontalExploreList(albumAdapter)
        recyclerViewRecommendedAlbums.setupHorizontalExploreList(recommendedAlbumAdapter)
        recyclerViewRecommendedArtists.setupHorizontalExploreList(recommendedArtistAdapter)

        val onGenreClick: (com.example.resonant.data.models.Genre) -> Unit = { selectedGenre ->
            val bundle = Bundle().apply {
                putString("genreId", selectedGenre.id)
                putString("genreName", selectedGenre.name)
                putString("genreGradientColors", selectedGenre.gradientColors)
            }
            findNavController().navigate(R.id.action_exploreFragment_to_genreArtistsFragment, bundle)
        }
        genreAdapter = GenreAdapter(emptyList(), onGenreClick = onGenreClick)
        genreAdapterMore = GenreAdapter(emptyList(), onGenreClick = onGenreClick)
        recyclerViewGenres.setupGenreGrid(genreAdapter)
        recyclerViewGenresMore.setupGenreGrid(genreAdapterMore)
    }

    private fun RecyclerView.setupGenreGrid(adapter: RecyclerView.Adapter<*>) {
        layoutManager = GridLayoutManager(requireContext(), 2)
        this.adapter = adapter
        itemAnimator = null
        setHasFixedSize(false)
    }

    private fun RecyclerView.setupHorizontalExploreList(adapter: RecyclerView.Adapter<*>) {
        layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        this.adapter = adapter
        itemAnimator = null
        setHasFixedSize(false)
    }

    private fun setupObservers() {
        viewModel.mostListenedArtists.observe(viewLifecycleOwner) { artists ->
            bindCollabFinderArtists(artists)
        }

        viewModel.publicPlaylists.observe(viewLifecycleOwner) { playlists ->
            playlistsCount = playlists.size
            playlistAdapter.submitList(playlists)
            renderSection(
                recyclerViewPlaylists,
                playlistsPlaceholder,
                playlistsShimmer,
                viewModel.publicPlaylistsLoading.value == true,
                playlistsCount
            )
        }

        viewModel.publicPlaylistsLoading.observe(viewLifecycleOwner) { isLoading ->
            renderSection(
                recyclerViewPlaylists,
                playlistsPlaceholder,
                playlistsShimmer,
                isLoading,
                playlistsCount
            )
        }

        viewModel.recommendedAlbums.observe(viewLifecycleOwner) { albums ->
            recommendedAlbumsCount = albums.size
            recommendedAlbumAdapter.submitList(albums)
            renderSection(
                recyclerViewRecommendedAlbums,
                recommendedAlbumsPlaceholder,
                recommendedAlbumsShimmer,
                viewModel.recommendedAlbumsLoading.value == true,
                recommendedAlbumsCount
            )
        }

        viewModel.recommendedAlbumsLoading.observe(viewLifecycleOwner) { isLoading ->
            renderSection(
                recyclerViewRecommendedAlbums,
                recommendedAlbumsPlaceholder,
                recommendedAlbumsShimmer,
                isLoading,
                recommendedAlbumsCount
            )
        }

        viewModel.recentArtists.observe(viewLifecycleOwner) { artists ->
            recommendedArtistsCount = artists.size
            recommendedArtistAdapter.submitList(artists)
            renderSection(
                recyclerViewRecommendedArtists,
                recommendedArtistsPlaceholder,
                recommendedArtistsShimmer,
                viewModel.recentArtistsLoading.value == true,
                recommendedArtistsCount
            )
        }

        viewModel.recentArtistsLoading.observe(viewLifecycleOwner) { isLoading ->
            renderSection(
                recyclerViewRecommendedArtists,
                recommendedArtistsPlaceholder,
                recommendedArtistsShimmer,
                isLoading,
                recommendedArtistsCount
            )
        }

        viewModel.newReleaseAlbums.observe(viewLifecycleOwner) { albums ->
            albumsCount = albums.size
            albumAdapter.submitList(albums)
            renderSection(
                recyclerViewAlbums,
                albumsPlaceholder,
                albumsShimmer,
                viewModel.newReleaseAlbumsLoading.value == true,
                albumsCount
            )
        }

        viewModel.newReleaseAlbumsLoading.observe(viewLifecycleOwner) { isLoading ->
            renderSection(
                recyclerViewAlbums,
                albumsPlaceholder,
                albumsShimmer,
                isLoading,
                albumsCount
            )
        }

        // Los géneros ya llegan ordenados por popularidad — se reparten en dos
        // tandas cortas (2x3 cada una) en vez de un único grid sin límite, y
        // se intercalan con otras secciones para que la pantalla no se sienta
        // "agrupada por tipo".
        viewModel.genres.observe(viewLifecycleOwner) { genreList ->
            val topGenres = genreList.take(GENRES_PER_BATCH)
            val moreGenres = genreList.drop(GENRES_PER_BATCH).take(GENRES_PER_BATCH)

            genresCount = topGenres.size
            genreAdapter.updateList(topGenres)
            renderSection(
                recyclerViewGenres,
                genresPlaceholder,
                genresShimmer,
                viewModel.isLoading.value == true,
                genresCount
            )

            genresMoreCount = moreGenres.size
            genreAdapterMore.updateList(moreGenres)
            renderSection(
                recyclerViewGenresMore,
                genresMorePlaceholder,
                genresMoreShimmer,
                viewModel.isLoading.value == true,
                genresMoreCount
            )
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            renderSection(
                recyclerViewGenres,
                genresPlaceholder,
                genresShimmer,
                isLoading,
                genresCount
            )
            renderSection(
                recyclerViewGenresMore,
                genresMorePlaceholder,
                genresMoreShimmer,
                isLoading,
                genresMoreCount
            )
        }

        viewModel.error.observe(viewLifecycleOwner) { errorMsg ->
            if (errorMsg != null) Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_LONG).show()
        }
    }

    private fun bindCollabFinderArtists(artists: List<Artist>) {
        val primary = artists.getOrNull(0)
        val secondary = artists.getOrNull(1)

        collabPrimaryArtistName.text = primary?.name?.takeIf { it.isNotBlank() } ?: "Tu artista top"
        collabSecondaryArtistName.text = secondary?.name?.takeIf { it.isNotBlank() } ?: "Segundo artista"
        collabPairMeta.text = when {
            primary != null && secondary != null -> "Top 1 + Top 2 segun tus escuchas"
            primary != null -> "Desde tu artista mas escuchado"
            else -> "Basado en tus escuchas"
        }

        loadCollabArtistImage(collabPrimaryArtistImage, primary?.url)
        loadCollabArtistImage(collabSecondaryArtistImage, secondary?.url)
    }

    private fun loadCollabArtistImage(imageView: ImageView, url: String?) {
        Glide.with(imageView)
            .load(url?.takeIf { it.isNotBlank() }?.let {
                ImageRequestHelper.buildGlideModel(imageView.context, it)
            })
            .override(240, 240)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .timeout(10_000)
            .placeholder(R.drawable.ic_user)
            .error(R.drawable.ic_user)
            .circleCrop()
            .dontAnimate()
            .into(imageView)
    }

    private fun renderSection(
        recyclerView: RecyclerView,
        placeholder: TextView,
        shimmer: ShimmerFrameLayout,
        isLoading: Boolean,
        itemCount: Int
    ) {
        val hasItems = itemCount > 0
        recyclerView.isVisible = hasItems
        if (isLoading && !hasItems) {
            placeholder.isVisible = false
            shimmer.isVisible = true
            shimmer.startShimmer()
        } else {
            shimmer.stopShimmer()
            shimmer.isVisible = false
            placeholder.isVisible = !hasItems
        }
    }

    private fun setupFloatingSearchHeader(view: View) {
        val normalHeader = view.findViewById<View>(R.id.superiorToolbar)
        val searchHeader = view.findViewById<View>(R.id.searchHeader)
        scrollBehavior = ScrollHeaderBehavior(
            normalHeader = normalHeader,
            searchHeader = searchHeader,
            onSearchClick = { findNavController().navigate(R.id.action_exploreFragment_to_searchFragment) }
        )
        scrollBehavior?.attachToNestedScrollView(scrollView)
    }

    private fun setupProgressiveLoading() {
        val listener = ViewTreeObserver.OnScrollChangedListener { loadVisibleSections() }
        scrollChangedListener = listener
        scrollView.viewTreeObserver.addOnScrollChangedListener(listener)
    }

    private fun loadVisibleSections() {
        if (!isAdded || progressiveLoadPending) return

        val requested = when {
            !recommendedAlbumsRequested && recommendedAlbumsSection.isNearViewport(80) -> {
                recommendedAlbumsRequested = true
                viewModel.loadRecommendedAlbums()
                true
            }
            !albumsRequested && albumsSection.isNearViewport(120) -> {
                albumsRequested = true
                viewModel.loadNewReleaseAlbums()
                true
            }
            !recommendedArtistsRequested && recommendedArtistsSection.isNearViewport(160) -> {
                recommendedArtistsRequested = true
                viewModel.loadRecentArtists()
                true
            }
            !genresRequested && genresSection.isNearViewport(200) -> {
                // Una sola carga alimenta las dos tandas de géneros (ver
                // setupObservers) — el segundo bloque no necesita su propio
                // trigger de scroll.
                genresRequested = true
                viewModel.loadPopularGenres()
                true
            }
            !playlistsRequested && playlistsSection.isNearViewport(240) -> {
                playlistsRequested = true
                viewModel.loadPublicPlaylists()
                true
            }
            else -> false
        }

        if (requested) {
            progressiveLoadPending = true
            scrollView.postDelayed({
                progressiveLoadPending = false
                loadVisibleSections()
            }, 420L)
        }
    }

    private fun View.isNearViewport(extraDp: Int): Boolean {
        val triggerY = scrollView.scrollY + scrollView.height + extraDp.dp()
        return top <= triggerY
    }

    private fun Int.dp(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    private fun navigateToChart(
        title: String,
        period: Int,
        isTrending: Boolean,
        startColor: String,
        endColor: String
    ) {
        val bundle = Bundle().apply {
            putString("TITLE", title)
            putInt("PERIOD", period)
            putBoolean("IS_TRENDING", isTrending)
            putString("START_COLOR", startColor)
            putString("END_COLOR", endColor)
        }
        findNavController().navigate(R.id.action_exploreFragment_to_topChartsFragment, bundle)
    }

    private fun navigateToPlaylist(playlist: Playlist) {
        val playlistId = playlist.id ?: return
        val bundle = Bundle().apply {
            putParcelable("playlist", playlist)
            putString("playlistId", playlistId)
            putBoolean("isReadOnly", true)
        }
        findNavController().navigate(R.id.action_global_to_playlistFragment, bundle)
    }

    private fun navigateToArtist(artist: Artist) {
        if (artist.id.isBlank()) return
        val bundle = Bundle().apply {
            putString("artistId", artist.id)
            putString("artistName", artist.name)
            putString("artistImageUrl", artist.url)
        }
        findNavController().navigate(R.id.action_exploreFragment_to_artistFragment, bundle)
    }

    private fun navigateToAlbum(album: Album) {
        if (album.id.isBlank()) return
        val bundle = Bundle().apply {
            putString("albumId", album.id)
        }
        findNavController().navigate(R.id.action_exploreFragment_to_albumFragment, bundle)
    }
}
