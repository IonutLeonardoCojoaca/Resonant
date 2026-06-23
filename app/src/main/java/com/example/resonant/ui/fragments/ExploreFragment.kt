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
import com.example.resonant.utils.AnimationsUtils
import com.example.resonant.utils.ImageRequestHelper
import com.example.resonant.utils.ScrollHeaderBehavior
import com.example.resonant.utils.Utils
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

class ExploreFragment : Fragment() {

    private lateinit var viewModel: ExploreViewModel
    private lateinit var userProfileImage: ImageView
    private lateinit var scrollView: NestedScrollView

    private lateinit var playlistsSection: View
    private lateinit var artistsSection: View
    private lateinit var albumsSection: View
    private lateinit var genresSection: View

    private lateinit var playlistsPlaceholder: TextView
    private lateinit var artistsPlaceholder: TextView
    private lateinit var albumsPlaceholder: TextView
    private lateinit var genresPlaceholder: TextView

    private lateinit var collabPrimaryArtistImage: ImageView
    private lateinit var collabSecondaryArtistImage: ImageView
    private lateinit var collabPrimaryArtistName: TextView
    private lateinit var collabSecondaryArtistName: TextView
    private lateinit var collabPairMeta: TextView

    private lateinit var recyclerViewPlaylists: RecyclerView
    private lateinit var recyclerViewArtists: RecyclerView
    private lateinit var recyclerViewAlbums: RecyclerView
    private lateinit var recyclerViewGenres: RecyclerView

    private lateinit var playlistAdapter: ExplorePlaylistAdapter
    private lateinit var artistAdapter: ExploreArtistAdapter
    private lateinit var albumAdapter: ExploreAlbumAdapter
    private lateinit var genreAdapter: GenreAdapter

    private var scrollBehavior: ScrollHeaderBehavior? = null
    private var scrollChangedListener: ViewTreeObserver.OnScrollChangedListener? = null

    private var playlistsCount = 0
    private var artistsCount = 0
    private var albumsCount = 0
    private var genresCount = 0

    private var playlistsRequested = false
    private var artistsRequested = false
    private var albumsRequested = false
    private var genresRequested = false
    private var progressiveLoadPending = false
    private var lastQuickActionChipId = View.NO_ID

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
        artistsSection = view.findViewById(R.id.artistsSection)
        albumsSection = view.findViewById(R.id.albumsSection)
        genresSection = view.findViewById(R.id.genresSection)

        playlistsPlaceholder = view.findViewById(R.id.playlistsPlaceholder)
        artistsPlaceholder = view.findViewById(R.id.artistsPlaceholder)
        albumsPlaceholder = view.findViewById(R.id.albumsPlaceholder)
        genresPlaceholder = view.findViewById(R.id.genresPlaceholder)

        collabPrimaryArtistImage = view.findViewById(R.id.collabPrimaryArtistImage)
        collabSecondaryArtistImage = view.findViewById(R.id.collabSecondaryArtistImage)
        collabPrimaryArtistName = view.findViewById(R.id.collabPrimaryArtistName)
        collabSecondaryArtistName = view.findViewById(R.id.collabSecondaryArtistName)
        collabPairMeta = view.findViewById(R.id.collabPairMeta)

        recyclerViewPlaylists = view.findViewById(R.id.recyclerViewPlaylists)
        recyclerViewArtists = view.findViewById(R.id.recyclerViewArtists)
        recyclerViewAlbums = view.findViewById(R.id.recyclerViewAlbums)
        recyclerViewGenres = view.findViewById(R.id.recyclerViewGenres)

        Utils.loadUserProfile(requireContext(), userProfileImage)

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

    private fun setupQuickActionChipStyles(view: View) {
        val quickActionsGroup = view.findViewById<ChipGroup>(R.id.quickActionsGroup)
        val font = ResourcesCompat.getFont(requireContext(), R.font.unageo_medium)
        val checkedId = quickActionsGroup.checkedChipId
        lastQuickActionChipId = checkedId

        quickActionsGroup.children.forEach { chipView ->
            if (chipView is Chip) {
                chipView.typeface = font
                AnimationsUtils.animateChipColor(chipView, chipView.id == checkedId)
            }
        }

        quickActionsGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val newCheckedId = checkedIds.firstOrNull() ?: View.NO_ID
            if (newCheckedId == lastQuickActionChipId) return@setOnCheckedStateChangeListener

            if (lastQuickActionChipId != View.NO_ID) {
                group.findViewById<Chip>(lastQuickActionChipId)?.let { chip ->
                    AnimationsUtils.animateChip(chip, false)
                    AnimationsUtils.animateChipColor(chip, false)
                }
            }

            if (newCheckedId != View.NO_ID) {
                group.findViewById<Chip>(newCheckedId)?.let { chip ->
                    AnimationsUtils.animateChip(chip, true)
                    AnimationsUtils.animateChipColor(chip, true)
                }
            }

            lastQuickActionChipId = newCheckedId
        }
    }

    private fun setupSectionHeaders(view: View) {
        configureHeader(
            view.findViewById(R.id.playlistsHeader),
            title = "Explorar playlists",
            onActionClick = { findNavController().navigate(R.id.action_exploreFragment_to_publicPlaylistsFragment) }
        )
        configureHeader(
            view.findViewById(R.id.artistsHeader),
            title = "Artistas en movimiento",
            onActionClick = { findNavController().navigate(R.id.topArtistsFragment) }
        )
        configureHeader(
            view.findViewById(R.id.albumsHeader),
            title = "Albumes recientes",
            onActionClick = { findNavController().navigate(R.id.action_exploreFragment_to_topAlbumsFragment) }
        )
        configureHeader(
            view.findViewById(R.id.genresHeader),
            title = "Generos populares",
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
        artistAdapter = ExploreArtistAdapter { navigateToArtist(it) }
        albumAdapter = ExploreAlbumAdapter { navigateToAlbum(it) }

        recyclerViewPlaylists.setupPlaylistGrid(playlistAdapter)
        recyclerViewArtists.setupHorizontalExploreList(artistAdapter)
        recyclerViewAlbums.setupExploreGrid(albumAdapter)

        genreAdapter = GenreAdapter(emptyList()) { selectedGenre ->
            val bundle = Bundle().apply {
                putString("genreId", selectedGenre.id)
                putString("genreName", selectedGenre.name)
                putString("genreGradientColors", selectedGenre.gradientColors)
            }
            findNavController().navigate(R.id.action_exploreFragment_to_genreArtistsFragment, bundle)
        }
        recyclerViewGenres.layoutManager = GridLayoutManager(requireContext(), 2)
        recyclerViewGenres.adapter = genreAdapter
        recyclerViewGenres.itemAnimator = null
    }

    private fun RecyclerView.setupPlaylistGrid(adapter: ExplorePlaylistAdapter) {
        layoutManager = GridLayoutManager(requireContext(), 2).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (position == 0) spanCount else 1
                }
            }
        }
        this.adapter = adapter
        itemAnimator = null
        setHasFixedSize(false)
    }

    private fun RecyclerView.setupExploreGrid(adapter: RecyclerView.Adapter<*>) {
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
                viewModel.publicPlaylistsLoading.value == true,
                playlistsCount,
                "Cargando playlists...",
                "Aun no hay playlists publicas."
            )
        }

        viewModel.publicPlaylistsLoading.observe(viewLifecycleOwner) { isLoading ->
            renderSection(
                recyclerViewPlaylists,
                playlistsPlaceholder,
                isLoading,
                playlistsCount,
                "Cargando playlists...",
                "Aun no hay playlists publicas."
            )
        }

        viewModel.recentArtists.observe(viewLifecycleOwner) { artists ->
            artistsCount = artists.size
            artistAdapter.submitList(artists)
            renderSection(
                recyclerViewArtists,
                artistsPlaceholder,
                viewModel.recentArtistsLoading.value == true,
                artistsCount,
                "Cargando artistas...",
                "No hay artistas recientes."
            )
        }

        viewModel.recentArtistsLoading.observe(viewLifecycleOwner) { isLoading ->
            renderSection(
                recyclerViewArtists,
                artistsPlaceholder,
                isLoading,
                artistsCount,
                "Cargando artistas...",
                "No hay artistas recientes."
            )
        }

        viewModel.newReleaseAlbums.observe(viewLifecycleOwner) { albums ->
            albumsCount = albums.size
            albumAdapter.submitList(albums)
            renderSection(
                recyclerViewAlbums,
                albumsPlaceholder,
                viewModel.newReleaseAlbumsLoading.value == true,
                albumsCount,
                "Cargando albumes...",
                "No hay albumes recientes."
            )
        }

        viewModel.newReleaseAlbumsLoading.observe(viewLifecycleOwner) { isLoading ->
            renderSection(
                recyclerViewAlbums,
                albumsPlaceholder,
                isLoading,
                albumsCount,
                "Cargando albumes...",
                "No hay albumes recientes."
            )
        }

        viewModel.genres.observe(viewLifecycleOwner) { genreList ->
            genresCount = genreList.size
            genreAdapter.updateList(genreList)
            renderSection(
                recyclerViewGenres,
                genresPlaceholder,
                viewModel.isLoading.value == true,
                genresCount,
                "Cargando generos...",
                "No hay generos disponibles."
            )
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            renderSection(
                recyclerViewGenres,
                genresPlaceholder,
                isLoading,
                genresCount,
                "Cargando generos...",
                "No hay generos disponibles."
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
        isLoading: Boolean,
        itemCount: Int,
        loadingText: String,
        emptyText: String
    ) {
        recyclerView.isVisible = itemCount > 0
        placeholder.isVisible = itemCount == 0
        placeholder.text = if (isLoading) loadingText else emptyText
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
            !playlistsRequested && playlistsSection.isNearViewport(80) -> {
                playlistsRequested = true
                viewModel.loadPublicPlaylists()
                true
            }
            !artistsRequested && artistsSection.isNearViewport(120) -> {
                artistsRequested = true
                viewModel.loadRecentArtists()
                true
            }
            !albumsRequested && albumsSection.isNearViewport(160) -> {
                albumsRequested = true
                viewModel.loadNewReleaseAlbums()
                true
            }
            !genresRequested && genresSection.isNearViewport(220) -> {
                genresRequested = true
                viewModel.loadPopularGenres()
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
