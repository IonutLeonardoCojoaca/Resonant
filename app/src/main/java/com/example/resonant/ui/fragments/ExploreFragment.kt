package com.example.resonant.ui.fragments

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.ui.bottomsheets.ItemOptions
import com.example.resonant.ui.bottomsheets.QuickOptionsBottomSheet
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.Genre
import com.example.resonant.data.models.Playlist
import com.example.resonant.data.models.Song
import com.example.resonant.data.models.StatsPeriod
import com.example.resonant.explore.AriaPick
import com.example.resonant.explore.ExploreMode
import com.example.resonant.explore.ExploreSectionUi
import com.example.resonant.live.LiveCardUi
import com.example.resonant.playback.QueueSource
import com.example.resonant.playback.PlaySourceMapper
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.adapters.ExploreAlbumAdapter
import com.example.resonant.ui.adapters.ExploreArtistAdapter
import com.example.resonant.ui.adapters.ExplorePlaylistAdapter
import com.example.resonant.ui.adapters.ArtistMixAdapter
import com.example.resonant.ui.adapters.GenreAdapter
import com.example.resonant.ui.components.ResonantAccent
import com.example.resonant.ui.components.ResonantMotion
import com.example.resonant.ui.explore.ExploreCollabFinderCard
import com.example.resonant.ui.explore.ExploreQuickCard
import com.example.resonant.ui.explore.PublicPlaymixShelf
import com.example.resonant.ui.explore.ExploreSectionsRenderer
import com.example.resonant.ui.viewmodels.ExploreViewModel
import com.example.resonant.ui.activities.MainActivity
import com.bumptech.glide.Glide
import com.example.resonant.data.network.StreakStatusDTO
import com.example.resonant.streak.SongOfDayUi
import com.example.resonant.streak.StreakEventBus
import com.example.resonant.streak.StreakTexts
import com.example.resonant.ui.bottomsheets.StreakDetailsBottomSheet
import com.example.resonant.utils.ImageRequestHelper
import com.example.resonant.utils.ScrollHeaderBehavior
import com.example.resonant.utils.Utils
import com.facebook.shimmer.ShimmerFrameLayout
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import com.example.resonant.utils.SnackType
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar

class ExploreFragment : Fragment() {

    companion object {
        private const val GENRES_PER_BATCH = 6
        private const val DEFERRED_SECTION_MARGIN_DP = 200
        private const val LIVE_CARD_TAG = "live_card"
        private const val SONG_OF_DAY_SECTION_ID = "song_of_day"
    }

    private lateinit var viewModel: ExploreViewModel
    private lateinit var userProfileImage: ImageView
    private lateinit var scrollView: NestedScrollView
    private lateinit var collabFinderCard: View
    private var collabCardAnimated = false

    private lateinit var heroTitle: TextView
    /** Explorar fijo: se infla (ViewStub) la primera vez que hace falta y se suelta con la vista. */
    private var legacy: LegacySections? = null
    private lateinit var legacyStub: ViewStub
    private lateinit var blocksHolder: ViewGroup
    private lateinit var dynamicContainer: LinearLayout
    private var sectionsRenderer: ExploreSectionsRenderer? = null
    private var defaultHeroTitle: CharSequence = ""

    private lateinit var streakChip: TextView
    private lateinit var recapBanner: View
    private lateinit var recapBannerTitle: TextView
    private lateinit var songOfDayCard: View
    private lateinit var songOfDayContent: View
    private lateinit var songOfDayShimmer: ShimmerFrameLayout
    private lateinit var songOfDayImage: ImageView
    private lateinit var songOfDayTitle: TextView
    private lateinit var songOfDayArtist: TextView
    private lateinit var songOfDayReason: TextView
    private lateinit var songOfDayAriaBadge: View
    private var legacyStarted = false

    private var scrollBehavior: ScrollHeaderBehavior? = null
    private var loadVisiblePosted = false
    private val visibleRect = android.graphics.Rect()

    // Bloques fijos intercalados entre las secciones (En el momento, Esto es…, Descubre,
    // Radios). En el Explorar fijo viven en el XML; en Explorar v2 los recoloca el renderer.
    private lateinit var liveNowBlock: View
    private lateinit var artistEssentialsBlock: View
    private lateinit var discoverBlock: View
    private lateinit var artistRadiosBlock: View
    private lateinit var recyclerViewArtistEssentials: RecyclerView
    private lateinit var recyclerViewArtistRadios: RecyclerView
    private lateinit var artistEssentialsAdapter: ArtistMixAdapter
    private lateinit var artistRadiosAdapter: ArtistMixAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_explore, container, false)
        // Activity-scoped (como HomeViewModel): sobrevive al cambio de pestaña, que destruye este fragment.
        viewModel = ViewModelProvider(requireActivity())[ExploreViewModel::class.java]

        initViews(view)
        setupQuickActionCards(view)
        setupRecyclerViews()
        setupObservers()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupFloatingSearchHeader(view)
        bindPublicPlaymixShelf(view)

        viewModel.loadExplore()
        viewModel.loadArtistMixes()
        viewModel.resetSongOfDay()
        observeStreakEvents()
    }

    override fun onStart() {
        super.onStart()
        viewModel.startSongOfDay()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadLiveChannels()
        viewModel.loadStreak()
        viewModel.loadRecapBanner()
    }

    override fun onStop() {
        viewModel.stopSongOfDay()
        super.onStop()
    }

    override fun onDestroyView() {
        // Las vistas del Explorar fijo y sus adapters se sueltan con la vista: no se retienen en el back stack.
        legacy = null
        sectionsRenderer = null
        scrollBehavior?.reset()
        scrollBehavior = null
        super.onDestroyView()
    }

    private fun initViews(view: View) {
        userProfileImage = view.findViewById(R.id.userProfile)
        scrollView = view.findViewById(R.id.exploreScrollView)

        heroTitle = view.findViewById(R.id.exploreHeroTitle)
        defaultHeroTitle = heroTitle.text
        initStreakViews(view)
        initRecapBanner(view)
        legacyStub = view.findViewById(R.id.exploreLegacyStub)
        blocksHolder = view.findViewById(R.id.exploreBlocksHolder)
        dynamicContainer = view.findViewById(R.id.exploreDynamicContainer)
        sectionsRenderer = ExploreSectionsRenderer(dynamicContainer, sectionCallbacks)

        liveNowBlock = view.findViewById(R.id.liveNowBlock)
        artistEssentialsBlock = view.findViewById(R.id.artistEssentialsBlock)
        discoverBlock = view.findViewById(R.id.discoverBlock)
        artistRadiosBlock = view.findViewById(R.id.artistRadiosBlock)

        recyclerViewArtistEssentials = view.findViewById(R.id.recyclerViewArtistEssentials)
        recyclerViewArtistRadios = view.findViewById(R.id.recyclerViewArtistRadios)

        bindDiscoverPills(view)

        viewLifecycleOwner.lifecycleScope.launch { Utils.loadUserProfile(requireContext(), userProfileImage) }

        view.findViewById<View>(R.id.searchButton).setOnClickListener {
            findNavController().navigate(R.id.action_exploreFragment_to_searchFragment)
        }
    }

    /**
     * Tarjetas 1x1 de acceso rápido (sustituyen a los antiguos chips) más la
     * de Tendencias en la sección "En el momento". Cada una
     * navega de inmediato a su pantalla. La animación de entrada solo se reproduce
     * la primera vez, no al volver a Explorar desde el back stack.
     */
    private fun setupQuickActionCards(view: View) {
        val cards = listOf(
            Triple(view.findViewById<View>(R.id.btnArtistas), ExploreQuickCard.Spec(
                "Los más famosos", "Artistas", ResonantAccent.CHART_ARTISTS
            ), { findNavController().navigate(R.id.topArtistsFragment) }),
            Triple(view.findViewById<View>(R.id.btnAlbumes), ExploreQuickCard.Spec(
                "Lo más escuchado", "Álbumes", ResonantAccent.CHART_ALBUMS
            ), { findNavController().navigate(R.id.action_exploreFragment_to_topAlbumsFragment) }),
            Triple(view.findViewById<View>(R.id.btnCanciones), ExploreQuickCard.Spec(
                "Top del día", "Canciones", ResonantAccent.CHART_SONGS
            ), { navigateToChart("Top Diario", StatsPeriod.DAILY.value, false, "#FF9F40", "#F53B57") }),
            Triple(view.findViewById<View>(R.id.btnListas), ExploreQuickCard.Spec(
                "De la comunidad", "Listas", ResonantAccent.PLAYLISTS
            ), { findNavController().navigate(R.id.action_exploreFragment_to_publicPlaylistsFragment) }),
            // Sección "En el momento" (intercalada más abajo): entra la última.
            Triple(view.findViewById<View>(R.id.btnTendencias), ExploreQuickCard.Spec(
                "Lo más viral", "Tendencias", ResonantAccent.TRENDING
            ), { navigateToChart("Tendencias", 0, true, "#eb3b5a", "#fa8231") })
        )
        cards.forEachIndexed { index, (card, spec, onClick) ->
            // Todas sin caja (solo brillo y logo), Tendencias incluida: mismo diseño que las de charts.
            ExploreQuickCard.bind(card, spec, boxless = true, onClick = onClick)
            if (!viewModel.quickCardsAnimated) ExploreQuickCard.animateIn(card, index)
        }
        viewModel.quickCardsAnimated = true
    }

    /** "Descubre": tarjeta de Collab Finder con su motivo de órbitas (anima al entrar en pantalla). */
    private fun bindDiscoverPills(view: View) {
        collabFinderCard = view.findViewById(R.id.btnCollabFinder)
        ExploreCollabFinderCard.bind(collabFinderCard, ::openCollabFinder)
    }

    /** "PlayMix de la comunidad" (necesita la vista creada: carga en viewLifecycleOwner). */
    private fun bindPublicPlaymixShelf(view: View) {
        PublicPlaymixShelf.bind(
            shelf = view.findViewById(R.id.publicPlaymixesShelf),
            scope = viewLifecycleOwner.lifecycleScope,
            onOpen = { playmix ->
                findNavController().navigate(R.id.action_global_to_playmixDetailFragment, PlaymixDetailArgs.bundle(playmix))
            },
            onSeeAll = { findNavController().navigate(R.id.action_global_to_publicPlaymixesFragment) }
        )
    }

    /** Live channel cards go first in "En el momento", before Tendencias. */
    private fun renderLiveCards(cards: List<LiveCardUi>) {
        val group = view?.findViewById<LinearLayout>(R.id.liveNowGroup) ?: return
        (group.childCount - 1 downTo 0)
            .map(group::getChildAt)
            .filter { it.tag == LIVE_CARD_TAG }
            .forEach(group::removeView)
        val inflater = LayoutInflater.from(group.context)
        cards.forEachIndexed { index, card ->
            val itemView = inflater.inflate(R.layout.view_live_channel_card, group, false)
            itemView.tag = LIVE_CARD_TAG
            bindLiveCard(itemView, card)
            group.addView(itemView, index)
        }
    }

    private fun bindLiveCard(itemView: View, card: LiveCardUi) {
        itemView.findViewById<TextView>(R.id.liveCardName).text = card.name
        itemView.findViewById<TextView>(R.id.liveCardSubtitle).text = card.subtitle
        itemView.findViewById<TextView>(R.id.liveCardBadge).isVisible = card.badge != null
        val cover = itemView.findViewById<ImageView>(R.id.liveCardCover)
        if (card.imageUrl != null) Glide.with(this).load(card.imageUrl).centerCrop().into(cover)
        itemView.contentDescription = getString(R.string.live_card_description, card.name, card.subtitle)
        itemView.setOnClickListener { (activity as? MainActivity)?.openLiveChannel(card.slug) }
    }

    private fun navigateToArtistSmartPlaylist(artist: Artist, playlistType: String) {
        val bundle = Bundle().apply {
            putString("artistId", artist.id)
            putString("playlistType", playlistType)
            putString("artistName", artist.name)
            putString("artworkUrl", artist.url)
        }
        findNavController().navigate(R.id.artistSmartPlaylistFragment, bundle)
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
        artistEssentialsAdapter = ArtistMixAdapter(ArtistMixAdapter.Kind.ESSENTIALS) {
            navigateToArtistSmartPlaylist(it.artist, "Essentials")
        }.apply {
            onItemLongClick = { mix ->
                ItemOptions.artistMix(this@ExploreFragment, mix.playlist.name, "Lo esencial · ${mix.playlist.songCount} canciones",
                    mix.playlist.coverUrl, mix.artist) { navigateToArtistSmartPlaylist(mix.artist, "Essentials") }
            }
        }
        artistRadiosAdapter = ArtistMixAdapter(ArtistMixAdapter.Kind.RADIO) {
            navigateToArtistSmartPlaylist(it.artist, "Radio")
        }.apply {
            onItemLongClick = { mix ->
                ItemOptions.artistMix(this@ExploreFragment, mix.playlist.name, "Radio del artista · ${mix.playlist.songCount} canciones",
                    mix.playlist.coverUrl, mix.artist) { navigateToArtistSmartPlaylist(mix.artist, "Radio") }
            }
        }
        recyclerViewArtistEssentials.setupHorizontalExploreList(artistEssentialsAdapter)
        recyclerViewArtistRadios.setupHorizontalExploreList(artistRadiosAdapter)
    }

    private fun RecyclerView.setupGenreGrid(adapter: RecyclerView.Adapter<*>) {
        layoutManager = GridLayoutManager(requireContext(), 2)
        this.adapter = adapter
        itemAnimator = null
        setHasFixedSize(false)
    }

    /** Infla el Explorar fijo la primera vez que hace falta y vuelca en él lo que el ViewModel ya tenga. */
    private fun ensureLegacy(): LegacySections = legacy ?: LegacySections(legacyStub.inflate() as ViewGroup).also {
        legacy = it
        it.renderAll()
    }

    /**
     * Vistas, adapters y estado de petición de las secciones del Explorar fijo. Viven solo mientras existe la vista
     * del fragment (ViewStub inflado); Explorar v2 no las infla nunca.
     */
    private inner class LegacySections(val container: ViewGroup) {

        inner class Section(sectionId: Int, recyclerId: Int, placeholderId: Int, shimmerId: Int) {
            val view: View = container.findViewById(sectionId)
            val recycler: RecyclerView = container.findViewById(recyclerId)
            private val placeholder: TextView = container.findViewById(placeholderId)
            private val shimmer: ShimmerFrameLayout = container.findViewById(shimmerId)
            private var requested = false

            fun requestIfNear(extraDp: Int, load: () -> Unit) {
                if (requested || !view.isNearViewport(extraDp)) return
                requested = true
                load()
            }

            fun render(isLoading: Boolean, itemCount: Int) = renderSection(recycler, placeholder, shimmer, isLoading, itemCount)
        }

        val recommendedAlbums = Section(R.id.recommendedAlbumsSection, R.id.recyclerViewRecommendedAlbums, R.id.recommendedAlbumsPlaceholder, R.id.recommendedAlbumsShimmer)
        val albums = Section(R.id.albumsSection, R.id.recyclerViewAlbums, R.id.albumsPlaceholder, R.id.albumsShimmer)
        val recommendedArtists = Section(R.id.recommendedArtistsSection, R.id.recyclerViewRecommendedArtists, R.id.recommendedArtistsPlaceholder, R.id.recommendedArtistsShimmer)
        val genres = Section(R.id.genresSection, R.id.recyclerViewGenres, R.id.genresPlaceholder, R.id.genresShimmer)
        val playlists = Section(R.id.playlistsSection, R.id.recyclerViewPlaylists, R.id.playlistsPlaceholder, R.id.playlistsShimmer)
        val genresMore = Section(R.id.genresMoreSection, R.id.recyclerViewGenresMore, R.id.genresMorePlaceholder, R.id.genresMoreShimmer)

        private val playlistAdapter = ExplorePlaylistAdapter { navigateToPlaylist(it) }
            .apply { onItemLongClick = { ItemOptions.playlist(this@ExploreFragment, it) } }
        private val albumAdapter = ExploreAlbumAdapter { navigateToAlbum(it) }
            .apply { onItemLongClick = { ItemOptions.album(this@ExploreFragment, it) } }
        private val recommendedAlbumAdapter = ExploreAlbumAdapter { navigateToAlbum(it) }
            .apply { onItemLongClick = { ItemOptions.album(this@ExploreFragment, it) } }
        private val recommendedArtistAdapter = ExploreArtistAdapter { navigateToArtist(it) }
            .apply { onItemLongClick = { ItemOptions.artist(this@ExploreFragment, it) } }
        private val genreAdapter = GenreAdapter(emptyList(), onGenreClick = ::navigateToGenre)
            .apply { onItemLongClick = { ItemOptions.genre(this@ExploreFragment, it) } }
        private val genreAdapterMore = GenreAdapter(emptyList(), onGenreClick = ::navigateToGenre)
            .apply { onItemLongClick = { ItemOptions.genre(this@ExploreFragment, it) } }

        /** Bloques intercalados y la sección tras la que van en el Explorar fijo (el orden que tenía el XML). */
        val blockAnchors: List<Pair<View, View>> = listOf(
            liveNowBlock to recommendedAlbums.view,
            artistEssentialsBlock to albums.view,
            discoverBlock to genres.view,
            artistRadiosBlock to playlists.view
        )

        init {
            setupSectionHeaders(container)
            playlists.recycler.setupHorizontalExploreList(playlistAdapter)
            albums.recycler.setupHorizontalExploreList(albumAdapter)
            recommendedAlbums.recycler.setupHorizontalExploreList(recommendedAlbumAdapter)
            recommendedArtists.recycler.setupHorizontalExploreList(recommendedArtistAdapter)
            genres.recycler.setupGenreGrid(genreAdapter)
            genresMore.recycler.setupGenreGrid(genreAdapterMore)
        }

        fun renderAll() {
            renderPlaylists()
            renderRecommendedAlbums()
            renderRecentArtists()
            renderNewReleases()
            renderGenres()
        }

        fun renderPlaylists() {
            val list = viewModel.publicPlaylists.value
            list?.let(playlistAdapter::submitList)
            playlists.render(viewModel.publicPlaylistsLoading.value == true, list?.size ?: 0)
            postLoadVisibleSections()
        }

        fun renderRecommendedAlbums() {
            val list = viewModel.recommendedAlbums.value
            list?.let(recommendedAlbumAdapter::submitList)
            recommendedAlbums.render(viewModel.recommendedAlbumsLoading.value == true, list?.size ?: 0)
            postLoadVisibleSections()
        }

        fun renderRecentArtists() {
            val list = viewModel.recentArtists.value
            list?.let(recommendedArtistAdapter::submitList)
            recommendedArtists.render(viewModel.recentArtistsLoading.value == true, list?.size ?: 0)
            postLoadVisibleSections()
        }

        fun renderNewReleases() {
            val list = viewModel.newReleaseAlbums.value
            list?.let(albumAdapter::submitList)
            albums.render(viewModel.newReleaseAlbumsLoading.value == true, list?.size ?: 0)
            postLoadVisibleSections()
        }

        fun renderGenres() {
            val all = viewModel.genres.value
            val top = all?.take(GENRES_PER_BATCH).orEmpty()
            val more = all?.drop(GENRES_PER_BATCH)?.take(GENRES_PER_BATCH).orEmpty()
            genreAdapter.updateList(top)
            genreAdapterMore.updateList(more)
            val loading = viewModel.isLoading.value == true
            genres.render(loading, top.size)
            genresMore.render(loading, more.size)
            postLoadVisibleSections()
        }
    }

    private fun RecyclerView.setupHorizontalExploreList(adapter: RecyclerView.Adapter<*>) {
        layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        this.adapter = adapter
        itemAnimator = null
        setHasFixedSize(false)
    }

    private fun setupObservers() {
        viewModel.mode.observe(viewLifecycleOwner, ::applyMode)
        viewModel.streak.observe(viewLifecycleOwner, ::renderStreakChip)
        viewModel.songOfDay.observe(viewLifecycleOwner, ::renderSongOfDay)
        viewModel.recapBanner.observe(viewLifecycleOwner, ::renderRecapBanner)
        viewModel.liveChannels.observe(viewLifecycleOwner, ::renderLiveCards)

        viewModel.ariaMessage.observe(viewLifecycleOwner) { message ->
            if (message != null) {
                showResonantSnackbar(message, SnackType.INFO)
                viewModel.consumeAriaMessage()
            }
        }

        viewModel.artistEssentials.observe(viewLifecycleOwner) { mixes ->
            artistEssentialsAdapter.submitList(mixes)
            revealBlock(artistEssentialsBlock, mixes.isNotEmpty())
        }
        viewModel.artistRadios.observe(viewLifecycleOwner) { mixes ->
            artistRadiosAdapter.submitList(mixes)
            revealBlock(artistRadiosBlock, mixes.isNotEmpty())
        }

        // Secciones del Explorar fijo: solo existen tras inflarlo; al inflar se vuelcan los valores actuales.
        viewModel.publicPlaylists.observe(viewLifecycleOwner) { legacy?.renderPlaylists() }
        viewModel.publicPlaylistsLoading.observe(viewLifecycleOwner) { legacy?.renderPlaylists() }
        viewModel.recommendedAlbums.observe(viewLifecycleOwner) { legacy?.renderRecommendedAlbums() }
        viewModel.recommendedAlbumsLoading.observe(viewLifecycleOwner) { legacy?.renderRecommendedAlbums() }
        viewModel.recentArtists.observe(viewLifecycleOwner) { legacy?.renderRecentArtists() }
        viewModel.recentArtistsLoading.observe(viewLifecycleOwner) { legacy?.renderRecentArtists() }
        viewModel.newReleaseAlbums.observe(viewLifecycleOwner) { legacy?.renderNewReleases() }
        viewModel.newReleaseAlbumsLoading.observe(viewLifecycleOwner) { legacy?.renderNewReleases() }
        // Los géneros ya llegan ordenados por popularidad: se reparten en dos tandas cortas (2x3) intercaladas.
        viewModel.genres.observe(viewLifecycleOwner) { legacy?.renderGenres() }
        viewModel.isLoading.observe(viewLifecycleOwner) { legacy?.renderGenres() }

        viewModel.error.observe(viewLifecycleOwner) { errorMsg ->
            if (errorMsg != null) {
                showResonantSnackbar(errorMsg, SnackType.ERROR)
                viewModel.consumeError()
            }
        }
    }

    /**
     * Explore v2 when the server offers it; otherwise (flag off, failure, nothing to show) the
     * fixed screen, whose loads only start once that fallback is decided.
     */
    private fun applyMode(mode: ExploreMode) {
        when (mode) {
            ExploreMode.Loading -> {
                legacy?.container?.isVisible = false
                dynamicContainer.isVisible = false
            }
            ExploreMode.Legacy -> {
                val legacy = ensureLegacy()
                sectionsRenderer?.clear()
                placeBlocksInLegacy(legacy)
                dynamicContainer.isVisible = false
                legacy.container.isVisible = true
                heroTitle.text = defaultHeroTitle
                if (!legacyStarted) {
                    legacyStarted = true
                    postLoadVisibleSections()
                }
            }
            is ExploreMode.Dynamic -> {
                legacy?.container?.isVisible = false
                dynamicContainer.isVisible = true
                // El saludo con el nombre ya está en Inicio: Explorar mantiene su propio título.
                heroTitle.text = defaultHeroTitle
                val sections = mode.state.visibleSections
                renderedSections = sections
                sectionsRenderer?.render(sections, interleavedBlocks(sections))
                postLoadVisibleSections()
            }
        }
    }

    private val sectionCallbacks = object : ExploreSectionsRenderer.Callbacks {
        override fun onSongClick(sectionId: String, songs: List<Song>, index: Int) =
            playFromExplore(songs, index, QueueSource.EXPLORE, sectionId, sectionRequestId(sectionId))

        override fun onAriaPickClick(sectionId: String, picks: List<AriaPick>, index: Int) =
            playFromExplore(picks.map { it.song }, index, QueueSource.ARIA_PICK, sectionId, sectionRequestId(sectionId))

        override fun onAriaPickLongClick(pick: AriaPick, anchor: View) = showAriaPickMenu(pick, anchor)
        override fun onAlbumClick(album: Album) = navigateToAlbum(album)
        override fun onArtistClick(artist: Artist) = navigateToArtist(artist)
        override fun onPlaylistClick(playlist: Playlist) = navigateToPlaylist(playlist)
        override fun onGenreClick(genre: Genre) = navigateToGenre(genre)

        override fun onSongLongPress(song: Song) = ItemOptions.song(this@ExploreFragment, song)
        override fun onAlbumLongPress(album: Album) = ItemOptions.album(this@ExploreFragment, album)
        override fun onArtistLongPress(artist: Artist) = ItemOptions.artist(this@ExploreFragment, artist)
        override fun onPlaylistLongPress(playlist: Playlist) = ItemOptions.playlist(this@ExploreFragment, playlist)
        override fun onGenreLongPress(genre: Genre) = ItemOptions.genre(this@ExploreFragment, genre)

        override fun onSeeAllGenres() {
            findNavController().navigate(R.id.action_exploreFragment_to_allGenresFragment)
        }

        override fun onSeeAllPlaylists() {
            findNavController().navigate(R.id.action_exploreFragment_to_publicPlaylistsFragment)
        }

    }

    /** Recomendación de Aria: valorarla o ir a las opciones completas de la canción. */
    private fun showAriaPickMenu(pick: AriaPick, @Suppress("UNUSED_PARAMETER") anchor: View) {
        val song = pick.song
        QuickOptionsBottomSheet(
            title = song.title,
            subtitle = song.artistName ?: song.artists.joinToString(", ") { it.name },
            imageUrl = song.coverUrl ?: song.album?.url,
            placeholder = R.drawable.ic_disc,
            options = listOf(
                QuickOptionsBottomSheet.Option(R.drawable.ic_add_favorite, getString(R.string.aria_pick_like)) {
                    viewModel.sendAriaFeedback(song.id, liked = true)
                },
                QuickOptionsBottomSheet.Option(R.drawable.ic_cancel, getString(R.string.aria_pick_dismiss)) {
                    viewModel.sendAriaFeedback(song.id, liked = false)
                },
                QuickOptionsBottomSheet.Option(R.drawable.ic_item_settings, "Más opciones de la canción") {
                    ItemOptions.song(this, song)
                }
            )
        ).show(parentFragmentManager, "AriaPickOptions")
    }

    /** Secciones dinámicas pintadas por última vez (para leer el `requestId` de la sección pulsada). */
    private var renderedSections: List<ExploreSectionUi> = emptyList()

    private fun sectionRequestId(sectionId: String): String? =
        when (val section = renderedSections.firstOrNull { it.id == sectionId }) {
            is ExploreSectionUi.Songs -> section.requestId
            is ExploreSectionUi.AriaPicks -> section.requestId
            else -> null
        }

    /**
     * Plays [songs] as the queue starting at [index], like Home does with its rows. [requestId] es el de la
     * sección (telemetría de recomendaciones) y se reporta con el `playSource` canónico de [source].
     */
    private fun playFromExplore(
        songs: List<Song>,
        index: Int,
        source: QueueSource,
        sectionId: String,
        requestId: String? = null
    ) {
        val song = songs.getOrNull(index) ?: return
        val playIntent = Intent(context, MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_PLAY
            putExtra(MusicPlaybackService.EXTRA_CURRENT_SONG, song)
            putExtra(MusicPlaybackService.EXTRA_CURRENT_INDEX, index)
            putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, ArrayList(songs))
            putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE, source)
            putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE_ID, "explore:$sectionId")
            putExtra(MusicPlaybackService.EXTRA_PLAY_SOURCE_CANONICAL, PlaySourceMapper.canonical(source))
            requestId?.let { putExtra(MusicPlaybackService.EXTRA_RECOMMENDATION_REQUEST_ID, it) }
        }
        requireContext().startService(playIntent)
    }

    // region Racha y canción del día

    private fun initRecapBanner(view: View) {
        recapBanner = view.findViewById(R.id.recapBanner)
        recapBannerTitle = view.findViewById(R.id.recapBannerTitle)
        view.findViewById<View>(R.id.recapBannerContent).setOnClickListener {
            val periodKey = viewModel.recapBanner.value?.periodKey ?: return@setOnClickListener
            findNavController().navigate(
                R.id.recapStoriesFragment,
                Bundle().apply { putString(RecapStoriesFragment.ARG_PERIOD_KEY, periodKey) }
            )
        }
        view.findViewById<View>(R.id.recapBannerDismiss).setOnClickListener { viewModel.dismissRecapBanner() }
    }

    private fun renderRecapBanner(recap: com.example.resonant.data.network.RecapSummaryDTO?) {
        recapBanner.isVisible = recap != null
        if (recap != null) recapBannerTitle.text = com.example.resonant.recap.RecapTexts.bannerTitle(recap.periodKey)
    }

    private fun initStreakViews(view: View) {
        streakChip = view.findViewById(R.id.streakChip)
        songOfDayCard = view.findViewById(R.id.songOfDayCard)
        songOfDayContent = view.findViewById(R.id.songOfDayContent)
        songOfDayShimmer = view.findViewById(R.id.songOfDayShimmer)
        songOfDayImage = view.findViewById(R.id.songOfDayImage)
        songOfDayTitle = view.findViewById(R.id.songOfDayTitle)
        songOfDayArtist = view.findViewById(R.id.songOfDayArtist)
        songOfDayReason = view.findViewById(R.id.songOfDayReason)
        songOfDayAriaBadge = view.findViewById(R.id.songOfDayAriaBadge)

        streakChip.setOnClickListener {
            val status = viewModel.streak.value ?: return@setOnClickListener
            if (childFragmentManager.findFragmentByTag(StreakDetailsBottomSheet.TAG) != null) return@setOnClickListener
            StreakDetailsBottomSheet.newInstance(status).show(childFragmentManager, StreakDetailsBottomSheet.TAG)
        }
        songOfDayContent.setOnClickListener { playSongOfDay() }
        view.findViewById<View>(R.id.songOfDayPlay).setOnClickListener { playSongOfDay() }
    }

    /** A streak event refreshes the chip at once; MainActivity is the one that consumes it. */
    private fun observeStreakEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                StreakEventBus.pending.filterNotNull().collect { viewModel.loadStreak(force = true) }
            }
        }
    }

    private fun renderStreakChip(status: StreakStatusDTO?) {
        streakChip.isVisible = status != null
        if (status == null) return
        streakChip.text = StreakTexts.chip(status)
        streakChip.contentDescription = StreakTexts.chipDescription(status)
        if (status.todayQualified) {
            streakChip.setBackgroundResource(R.drawable.bg_streak_chip_qualified)
            streakChip.setTextColor(Color.WHITE)
        } else {
            streakChip.setBackgroundResource(R.drawable.bg_streak_chip_pending)
            streakChip.setTextColor(ContextCompat.getColor(requireContext(), R.color.secondaryColorTheme))
        }
    }

    private fun renderSongOfDay(ui: SongOfDayUi) {
        songOfDayCard.isVisible = ui != SongOfDayUi.Hidden
        val loading = ui == SongOfDayUi.Loading
        songOfDayShimmer.isVisible = loading
        if (loading) songOfDayShimmer.startShimmer() else songOfDayShimmer.stopShimmer()
        songOfDayContent.isVisible = ui is SongOfDayUi.Ready
        if (ui !is SongOfDayUi.Ready) return

        songOfDayTitle.text = ui.song.title
        songOfDayArtist.text = ui.song.artistName.orEmpty()
        songOfDayReason.text = ui.reason
        songOfDayReason.isVisible = ui.reason.isNotBlank()
        songOfDayAriaBadge.isVisible = ui.isAria
        val cover = (ui.song.coverUrl ?: ui.song.album?.url)?.takeIf { it.isNotBlank() }
        Glide.with(this)
            .load(cover?.let { ImageRequestHelper.buildGlideModel(requireContext(), it) })
            .placeholder(R.drawable.ic_disc)
            .error(R.drawable.ic_disc)
            .into(songOfDayImage)
    }

    private fun playSongOfDay() {
        val ready = viewModel.songOfDay.value as? SongOfDayUi.Ready ?: return
        playFromExplore(listOf(ready.song), 0, QueueSource.SONG_OF_DAY, SONG_OF_DAY_SECTION_ID, ready.requestId)
    }

    // endregion

    private fun openCollabFinder() {
        findNavController().navigate(R.id.action_exploreFragment_to_collab_finder_nav)
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
        scrollBehavior?.attachToNestedScrollView(scrollView) { onContentScrolled() }
    }

    /**
     * Dónde van los bloques fijos entre las secciones de Explorar v2 (nº de secciones
     * delante): contenido real primero y las tarjetas mezcladas; "Descubre" justo
     * debajo de los géneros si el servidor los envía.
     */
    private fun interleavedBlocks(sections: List<ExploreSectionUi>): List<Pair<Int, View>> {
        val genres = sections.indexOfFirst { it is ExploreSectionUi.Genres }
        val discoverSlot = if (genres >= 0) maxOf(genres + 1, 4) else 5
        return listOf(
            1 to liveNowBlock,
            3 to artistEssentialsBlock,
            discoverSlot to discoverBlock,
            discoverSlot + 2 to artistRadiosBlock
        )
    }

    private fun placeBlocksInLegacy(legacy: LegacySections) {
        val container = legacy.container
        legacy.blockAnchors.forEach { (block, anchor) ->
            if (block.parent === container && container.indexOfChild(block) == container.indexOfChild(anchor) + 1) {
                return@forEach
            }
            (block.parent as? ViewGroup)?.removeView(block)
            container.addView(block, container.indexOfChild(anchor) + 1)
        }
    }

    /** Muestra un bloque que depende de datos con un fundido, solo la primera vez. */
    private fun revealBlock(block: View, show: Boolean) {
        if (!show) {
            block.isVisible = false
            return
        }
        if (block.isVisible) return
        block.alpha = 0f
        block.isVisible = true
        block.animate().alpha(1f).setDuration(320).setInterpolator(ResonantMotion.expoOut).start()
    }

    /**
     * Scroll vertical del contenido (lo entrega ScrollHeaderBehavior, único listener del NestedScrollView). Antes
     * era un OnScrollChangedListener global que saltaba también con cada fila horizontal.
     */
    private fun onContentScrolled() {
        loadVisibleSections()
        revealCollabCardIfVisible()
    }

    /** Una sola comprobación por frame aunque varias secciones lleguen a la vez. */
    private fun postLoadVisibleSections() {
        if (loadVisiblePosted) return
        loadVisiblePosted = true
        scrollView.post {
            loadVisiblePosted = false
            loadVisibleSections()
        }
    }

    /** La tarjeta de Collab Finder anima su entrada cuando llega a la pantalla, no al cargar. */
    private fun revealCollabCardIfVisible() {
        if (collabCardAnimated || !::collabFinderCard.isInitialized || !collabFinderCard.isShown) return
        if (collabFinderCard.getLocalVisibleRect(visibleRect) && visibleRect.height() > collabFinderCard.height / 3) {
            collabCardAnimated = true
            ExploreCollabFinderCard.animateIn(collabFinderCard)
        }
    }

    private fun loadVisibleSections() {
        if (!isAdded) return
        if (viewModel.mode.value is ExploreMode.Dynamic) {
            sectionsRenderer
                ?.pendingIdsNear { it.isNearViewport(DEFERRED_SECTION_MARGIN_DP) }
                ?.forEach(viewModel::requestDeferred)
            return
        }
        val legacy = legacy ?: return
        // Todas las secciones cercanas se piden en una sola pasada (antes, una cada 420 ms): cada petición es
        // barata y OkHttp ya limita la concurrencia por host.
        legacy.recommendedAlbums.requestIfNear(80, viewModel::loadRecommendedAlbums)
        legacy.albums.requestIfNear(120, viewModel::loadNewReleaseAlbums)
        legacy.recommendedArtists.requestIfNear(160, viewModel::loadRecentArtists)
        // Una sola carga alimenta las dos tandas de géneros (ver renderGenres).
        legacy.genres.requestIfNear(200, viewModel::loadPopularGenres)
        legacy.playlists.requestIfNear(240, viewModel::loadPublicPlaylists)
    }

    private fun View.isNearViewport(extraDp: Int): Boolean {
        val triggerY = scrollView.scrollY + scrollView.height + extraDp.dp()
        return topInScrollContent() <= triggerY
    }

    /** Sections live inside the legacy/dynamic containers, so add the offsets up to the scroll content. */
    private fun View.topInScrollContent(): Int {
        var offset = top
        var parentView = parent as? View
        while (parentView != null && parentView.parent !== scrollView && parentView !== scrollView) {
            offset += parentView.top
            parentView = parentView.parent as? View
        }
        return offset
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

    private fun navigateToGenre(genre: Genre) {
        val bundle = Bundle().apply {
            putString("genreId", genre.id)
            putString("genreName", genre.name)
            putString("genreGradientColors", genre.gradientColors)
        }
        findNavController().navigate(R.id.action_exploreFragment_to_genreArtistsFragment, bundle)
    }

    private fun navigateToAlbum(album: Album) {
        if (album.id.isBlank()) return
        val bundle = Bundle().apply {
            putString("albumId", album.id)
        }
        findNavController().navigate(R.id.action_exploreFragment_to_albumFragment, bundle)
    }
}
