package com.example.resonant.ui.fragments

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
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


    private lateinit var heroTitle: TextView
    private lateinit var legacyContainer: ViewGroup
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
    private var quickCardsAnimated = false

    // Bloques fijos intercalados entre las secciones (En el momento, Esto es…, Descubre,
    // Radios). En el Explorar fijo viven en el XML; en Explorar v2 los recoloca el renderer.
    private lateinit var liveNowBlock: View
    private lateinit var artistEssentialsBlock: View
    private lateinit var discoverBlock: View
    private lateinit var artistRadiosBlock: View
    private var legacyBlockAnchors: List<Pair<View, View>> = emptyList()
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
        viewModel = ViewModelProvider(this)[ExploreViewModel::class.java]

        initViews(view)
        setupQuickActionCards(view)
        setupSectionHeaders(view)
        setupRecyclerViews()
        setupObservers()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupFloatingSearchHeader(view)
        setupProgressiveLoading()
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
        if (::scrollView.isInitialized) {
            scrollChangedListener?.let { listener ->
                if (scrollView.viewTreeObserver.isAlive) {
                    scrollView.viewTreeObserver.removeOnScrollChangedListener(listener)
                }
            }
        }
        scrollChangedListener = null
        sectionsRenderer = null
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


        heroTitle = view.findViewById(R.id.exploreHeroTitle)
        defaultHeroTitle = heroTitle.text
        initStreakViews(view)
        initRecapBanner(view)
        legacyContainer = view.findViewById(R.id.exploreLegacyContainer)
        dynamicContainer = view.findViewById(R.id.exploreDynamicContainer)
        sectionsRenderer = ExploreSectionsRenderer(dynamicContainer, sectionCallbacks)

        recyclerViewPlaylists = view.findViewById(R.id.recyclerViewPlaylists)
        recyclerViewAlbums = view.findViewById(R.id.recyclerViewAlbums)
        recyclerViewGenres = view.findViewById(R.id.recyclerViewGenres)
        recyclerViewRecommendedAlbums = view.findViewById(R.id.recyclerViewRecommendedAlbums)
        recyclerViewRecommendedArtists = view.findViewById(R.id.recyclerViewRecommendedArtists)
        recyclerViewGenresMore = view.findViewById(R.id.recyclerViewGenresMore)

        liveNowBlock = view.findViewById(R.id.liveNowBlock)
        artistEssentialsBlock = view.findViewById(R.id.artistEssentialsBlock)
        discoverBlock = view.findViewById(R.id.discoverBlock)
        artistRadiosBlock = view.findViewById(R.id.artistRadiosBlock)
        // Cada bloque recuerda la sección que lo precede en el XML para volver a su
        // sitio si Explorar pasa de v2 al modo fijo.
        legacyBlockAnchors = listOf(liveNowBlock, artistEssentialsBlock, discoverBlock, artistRadiosBlock).map { block ->
            block to legacyContainer.getChildAt(legacyContainer.indexOfChild(block) - 1)
        }

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
                "Los más famosos", "Artistas", ResonantAccent.ARTISTS
            ), { findNavController().navigate(R.id.topArtistsFragment) }),
            Triple(view.findViewById<View>(R.id.btnAlbumes), ExploreQuickCard.Spec(
                "Lo más escuchado", "Álbumes", ResonantAccent.ALBUMS
            ), { findNavController().navigate(R.id.action_exploreFragment_to_topAlbumsFragment) }),
            Triple(view.findViewById<View>(R.id.btnCanciones), ExploreQuickCard.Spec(
                "Top del día", "Canciones", ResonantAccent.SONGS
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
            ExploreQuickCard.bind(card, spec, showEyebrow = false, onClick = onClick)
            if (!quickCardsAnimated) ExploreQuickCard.animateIn(card, index)
        }
        quickCardsAnimated = true
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
                findNavController().navigate(
                    R.id.action_global_to_playmixDetailFragment,
                    Bundle().apply { putString("playmixId", playmix.id) }
                )
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
        playlistAdapter = ExplorePlaylistAdapter { navigateToPlaylist(it) }
            .apply { onItemLongClick = { ItemOptions.playlist(this@ExploreFragment, it) } }
        albumAdapter = ExploreAlbumAdapter { navigateToAlbum(it) }
            .apply { onItemLongClick = { ItemOptions.album(this@ExploreFragment, it) } }
        recommendedAlbumAdapter = ExploreAlbumAdapter { navigateToAlbum(it) }
            .apply { onItemLongClick = { ItemOptions.album(this@ExploreFragment, it) } }
        recommendedArtistAdapter = ExploreArtistAdapter { navigateToArtist(it) }
            .apply { onItemLongClick = { ItemOptions.artist(this@ExploreFragment, it) } }

        recyclerViewPlaylists.setupHorizontalExploreList(playlistAdapter)
        recyclerViewAlbums.setupHorizontalExploreList(albumAdapter)
        recyclerViewRecommendedAlbums.setupHorizontalExploreList(recommendedAlbumAdapter)
        recyclerViewRecommendedArtists.setupHorizontalExploreList(recommendedArtistAdapter)

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

        val onGenreClick: (Genre) -> Unit = ::navigateToGenre
        genreAdapter = GenreAdapter(emptyList(), onGenreClick = onGenreClick)
            .apply { onItemLongClick = { ItemOptions.genre(this@ExploreFragment, it) } }
        genreAdapterMore = GenreAdapter(emptyList(), onGenreClick = onGenreClick)
            .apply { onItemLongClick = { ItemOptions.genre(this@ExploreFragment, it) } }
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
            if (errorMsg != null) showResonantSnackbar(errorMsg, SnackType.ERROR)
        }
    }

    /**
     * Explore v2 when the server offers it; otherwise (flag off, failure, nothing to show) the
     * fixed screen, whose loads only start once that fallback is decided.
     */
    private fun applyMode(mode: ExploreMode) {
        when (mode) {
            ExploreMode.Loading -> {
                legacyContainer.isVisible = false
                dynamicContainer.isVisible = false
            }
            ExploreMode.Legacy -> {
                sectionsRenderer?.clear()
                placeBlocksInLegacy()
                dynamicContainer.isVisible = false
                legacyContainer.isVisible = true
                heroTitle.text = defaultHeroTitle
                if (!legacyStarted) {
                    legacyStarted = true
                    scrollView.post { loadVisibleSections() }
                }
            }
            is ExploreMode.Dynamic -> {
                legacyContainer.isVisible = false
                dynamicContainer.isVisible = true
                // El saludo con el nombre ya está en Inicio: Explorar mantiene su propio título.
                heroTitle.text = defaultHeroTitle
                val sections = mode.state.visibleSections
                renderedSections = sections
                sectionsRenderer?.render(sections, interleavedBlocks(sections))
                scrollView.post { loadVisibleSections() }
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
        scrollBehavior?.attachToNestedScrollView(scrollView)
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

    private fun placeBlocksInLegacy() {
        legacyBlockAnchors.forEach { (block, anchor) ->
            if (block.parent === legacyContainer && legacyContainer.indexOfChild(block) == legacyContainer.indexOfChild(anchor) + 1) {
                return@forEach
            }
            (block.parent as? ViewGroup)?.removeView(block)
            legacyContainer.addView(block, legacyContainer.indexOfChild(anchor) + 1)
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

    private fun setupProgressiveLoading() {
        val listener = ViewTreeObserver.OnScrollChangedListener {
            loadVisibleSections()
            revealCollabCardIfVisible()
        }
        scrollChangedListener = listener
        scrollView.viewTreeObserver.addOnScrollChangedListener(listener)
    }

    /** La tarjeta de Collab Finder anima su entrada cuando llega a la pantalla, no al cargar. */
    private fun revealCollabCardIfVisible() {
        if (collabCardAnimated || !::collabFinderCard.isInitialized || !collabFinderCard.isShown) return
        val rect = android.graphics.Rect()
        if (collabFinderCard.getLocalVisibleRect(rect) && rect.height() > collabFinderCard.height / 3) {
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
        if (progressiveLoadPending) return

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
