package com.example.resonant.ui.fragments

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.resonant.R
import com.example.resonant.ui.bottomsheets.ItemOptions
import com.example.resonant.data.models.Playlist
import com.example.resonant.data.models.Song
import com.example.resonant.managers.PlaylistManager
import com.example.resonant.managers.AiTrackManager
import com.example.resonant.managers.CapabilitiesManager
import com.example.resonant.ui.adapters.AiTrackAdapter
import com.example.resonant.ui.viewmodels.AiTrackListViewModel
import com.example.resonant.ui.viewmodels.AiTrackListViewModelFactory
import com.example.resonant.managers.PlaymixManager
import com.example.resonant.managers.UserManager
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.adapters.HeaderViewAdapter
import com.example.resonant.ui.adapters.PlaylistAdapter
import com.example.resonant.ui.adapters.PlaymixListAdapter
import com.example.resonant.ui.adapters.SearchResult
import com.example.resonant.ui.adapters.SearchResultAdapter
import com.example.resonant.ui.bottomsheets.AlbumOptionsBottomSheet
import com.example.resonant.ui.bottomsheets.ArtistOptionsBottomSheet
import com.example.resonant.ui.bottomsheets.ArtistSelectorBottomSheet
import com.example.resonant.ui.bottomsheets.PlaylistOptionsBottomSheet
import com.example.resonant.ui.bottomsheets.PlaymixListOptionsBottomSheet
import com.example.resonant.ui.bottomsheets.SelectPlaylistBottomSheet
import com.example.resonant.ui.components.ChartHero
import com.example.resonant.ui.components.CollectionActions
import com.example.resonant.ui.components.CollectionState
import com.example.resonant.ui.components.ResonantAccent
import com.example.resonant.ui.components.SlidingPillTabs
import com.example.resonant.ui.dialogs.ResonantDialog
import com.example.resonant.ui.viewmodels.DownloadViewModel
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.ui.viewmodels.PlaylistsListViewModel
import com.example.resonant.ui.viewmodels.PlaylistsListViewModelFactory
import com.example.resonant.ui.viewmodels.PlaymixListViewModel
import com.example.resonant.ui.viewmodels.PlaymixListViewModelFactory
import com.example.resonant.ui.viewmodels.SongViewModel
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.example.resonant.utils.Utils
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch
import java.util.ArrayList
import com.example.resonant.utils.SnackbarUtils.favoriteSongFeedback
import com.example.resonant.utils.SnackType

/**
 * Biblioteca. Una lista real por pestaña dentro de un ViewPager2: cambiar de pestaña
 * es instantáneo (nada se vacía ni se reconstruye), cada una conserva su scroll y
 * todas reciclan filas. Los datos se cargan/precargan al entrar y cada observer
 * actualiza siempre su propia lista, esté visible o no.
 */
class SavedFragment : BaseFragment(R.layout.fragment_saved) {

    private enum class Tab(val label: String, val accent: ResonantAccent) {
        PLAYLISTS("Listas", ResonantAccent.PLAYLISTS),
        SONGS("Canciones", ResonantAccent.SONGS),
        ARTISTS("Artistas", ResonantAccent.ARTISTS),
        ALBUMS("Álbumes", ResonantAccent.ALBUMS),
        PLAYMIXES("PlayMix", ResonantAccent.PLAYMIX),
        /** Solo con `features.aiTracks`; va la última para que no cambie el índice de las demás. */
        AI_TRACKS("IA", ResonantAccent.AI_TRACKS)
    }

    /** Pestañas que se muestran: la de IA solo con la capacidad encendida. */
    private val visibleTabs: List<Tab> by lazy {
        Tab.entries.filter { it != Tab.AI_TRACKS || CapabilitiesManager.isAiTracksEnabled() }
    }

    private class Page(val root: View) {
        val list: RecyclerView = root.findViewById(R.id.pageList)
        val skeleton: ShimmerFrameLayout = root.findViewById(R.id.pageSkeleton)
        val state: View = root.findViewById(R.id.pageState)
        var listAnimated = false

        /** Margen superior del estado vacío definido en el layout (se suma a la altura de la cabecera). */
        private val stateBaseTop = (state.layoutParams as ViewGroup.MarginLayoutParams).topMargin

        /**
         * Cabecera de la lista (atajos "Descargas"/"Nueva playlist", "Nueva PlayMix", acciones de canciones). Va dentro
         * del RecyclerView, así que el skeleton y el estado vacío, que se dibujan encima de la lista, se colocan por
         * debajo de ella: si no, la tapaban mientras carga o sin elementos.
         */
        var header: View? = null
            set(value) {
                field = value
                // Se aplaza con post: estos avisos llegan en mitad de un layout.
                value?.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
                    if (bottom - top != oldBottom - oldTop) v.post { applyHeaderOffset() }
                }
                value?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) { v.post { applyHeaderOffset() } }
                    override fun onViewDetachedFromWindow(v: View) { root.post { applyHeaderOffset() } }
                })
            }

        fun applyHeaderOffset() {
            val headerHeight = header?.takeIf { it.isAttachedToWindow && it.isShown }?.height ?: 0
            setTopMargin(skeleton, headerHeight)
            setTopMargin(state, headerHeight + stateBaseTop)
        }

        private fun setTopMargin(view: View, top: Int) {
            val params = view.layoutParams as ViewGroup.MarginLayoutParams
            if (params.topMargin == top) return
            params.topMargin = top
            view.layoutParams = params
        }
    }

    // Views
    private lateinit var appBarLayout: AppBarLayout
    private lateinit var hero: View
    private lateinit var tabsView: SlidingPillTabs
    private lateinit var pager: ViewPager2
    private lateinit var userProfileImage: ImageView
    private val pages = mutableMapOf<Tab, Page>()

    // Adapters (uno por pestaña: cada lista mantiene su estado)
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var songsAdapter: SearchResultAdapter
    private lateinit var artistsAdapter: SearchResultAdapter
    private lateinit var albumsAdapter: SearchResultAdapter
    private lateinit var playmixAdapter: PlaymixListAdapter
    /** "Me gustan" bajo las PlayMix propias (playmix-sharing-discovery). */
    private var likedPlaymixes: LikedPlaymixesSection? = null
    private lateinit var songsHeader: HeaderViewAdapter
    private lateinit var songsActions: View
    private lateinit var downloadsShortcut: View

    // Estado que sobrevive a la recreación de la vista (la instancia del fragment se conserva en el back stack)
    private var selectedTab = Tab.PLAYLISTS
    private var heroAnimated = false
    private val counts = mutableMapOf<Tab, Int>()

    // ViewModels
    private val playlistsListViewModel: PlaylistsListViewModel by viewModels {
        val playlistManager = PlaylistManager(requireContext())
        PlaylistsListViewModelFactory(playlistManager)
    }

    // Compartido con el resto de la app: misma caché de favoritos y mismos toggles.
    private val favoritesViewModel: FavoritesViewModel by activityViewModels()
    private val downloadViewModel: DownloadViewModel by activityViewModels()
    private val songViewModel: SongViewModel by activityViewModels()

    private lateinit var playmixListViewModel: PlaymixListViewModel
    private lateinit var aiTrackViewModel: AiTrackListViewModel
    private lateinit var aiTrackAdapter: AiTrackAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_saved, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupPlaymixViewModel()
        setupAdapters()
        createPages()
        setupPagerAndTabs()
        setupHero()
        observeData()

        // Handle BackStack results (e.g. after editing playlist)
        val navController = findNavController()
        navController.currentBackStackEntry?.savedStateHandle?.getLiveData<String>("PLAYLIST_UPDATED_ID")
            ?.observe(viewLifecycleOwner) { playlistId ->
                if (playlistId != null) {
                    playlistAdapter.clearCacheForPlaylist(playlistId)
                    forceReloadPlaylists()
                    navController.currentBackStackEntry?.savedStateHandle?.remove<String>("PLAYLIST_UPDATED_ID")
                }
            }

        // Cargas iniciales: todas las pestañas a la vez (respetan sus cachés), así
        // cambiar de pestaña nunca espera a la red. Las PlayMix también se precargan.
        reloadPlaylistsInitial()
        favoritesViewModel.loadAllFavorites()
        playmixListViewModel.loadMyPlaymixes()
        likedPlaymixes?.refresh()
        if (Tab.AI_TRACKS in visibleTabs) aiTrackViewModel.load()
    }

    override fun onStart() {
        super.onStart()
        if (::aiTrackViewModel.isInitialized) aiTrackViewModel.setVisible(selectedTab == Tab.AI_TRACKS)
    }

    override fun onStop() {
        if (::aiTrackViewModel.isInitialized) aiTrackViewModel.setVisible(false)
        super.onStop()
    }

    private fun initViews(view: View) {
        appBarLayout = view.findViewById(R.id.appBarLayout)
        hero = view.findViewById(R.id.libraryHero)
        tabsView = view.findViewById(R.id.libraryTabs)
        pager = view.findViewById(R.id.libraryPager)
        userProfileImage = view.findViewById(R.id.userProfile)
        viewLifecycleOwner.lifecycleScope.launch { Utils.loadUserProfile(requireContext(), userProfileImage) }
    }

    private fun setupPlaymixViewModel() {
        val playmixManager = PlaymixManager(requireContext())
        playmixListViewModel = ViewModelProvider(
            this,
            PlaymixListViewModelFactory(playmixManager)
        ).get(PlaymixListViewModel::class.java)
        aiTrackViewModel = ViewModelProvider(this, AiTrackListViewModelFactory(AiTrackManager(requireContext())))
            .get(AiTrackListViewModel::class.java)
    }

    // ── Cabecera ────────────────────────────────────────────────────────

    private fun setupHero() {
        updateHero(animate = false)
        ChartHero.attachParallax(appBarLayout, hero, requireView().findViewById(R.id.tvToolbarTitle))
        if (!heroAnimated) {
            heroAnimated = true
            ChartHero.animateIn(hero)
        }
    }

    /**
     * La cabecera toma el color de la pestaña activa y resume lo que contiene. De fondo
     * usa el icono de la biblioteca (no el logo de Resonant); su tinte lo lleva
     * [tintHeroWhileScrolling] de forma continua mientras se cambia de pestaña.
     */
    private fun updateHero(animate: Boolean) {
        ChartHero.bind(
            hero,
            selectedTab.accent,
            "Tu biblioteca",
            "Biblioteca",
            heroSubtitle(selectedTab),
            customIcon = R.drawable.ic_library_glyph
        )
        if (animate) ChartHero.pulse(hero)
    }

    /** Mezcla el tinte del icono de fondo entre la pestaña actual y la siguiente según el arrastre. */
    private fun tintHeroWhileScrolling(position: Int, offset: Float) {
        val from = visibleTabs[position].accent.color
        val to = visibleTabs.getOrNull(position + 1)?.accent?.color ?: from
        ChartHero.tintCustomIcon(hero, ColorUtils.blendARGB(from, to, offset))
    }

    private fun heroSubtitle(tab: Tab): String {
        val n = counts[tab] ?: return when (tab) {
            Tab.PLAYLISTS -> "Tus listas y las que has guardado"
            Tab.SONGS -> "Las canciones que te gustan"
            Tab.ARTISTS -> "Los artistas que sigues"
            Tab.ALBUMS -> "Los álbumes que has guardado"
            Tab.PLAYMIXES -> "Tus mezclas con transiciones"
            Tab.AI_TRACKS -> "Pistas que has creado con IA"
        }
        return when (tab) {
            Tab.PLAYLISTS -> if (n == 1) "1 lista" else "$n listas"
            Tab.SONGS -> if (n == 1) "1 canción que te gusta" else "$n canciones que te gustan"
            Tab.ARTISTS -> if (n == 1) "1 artista que sigues" else "$n artistas que sigues"
            Tab.ALBUMS -> if (n == 1) "1 álbum guardado" else "$n álbumes guardados"
            Tab.PLAYMIXES -> if (n == 1) "1 PlayMix" else "$n PlayMix"
            Tab.AI_TRACKS -> if (n == 1) "1 pista creada con IA" else "$n pistas creadas con IA"
        }
    }

    private fun setCount(tab: Tab, count: Int) {
        if (counts[tab] == count) return
        counts[tab] = count
        if (tab == selectedTab) ChartHero.setText(hero, "Biblioteca", heroSubtitle(tab), animate = true)
    }

    // ── Pestañas + páginas ──────────────────────────────────────────────

    private fun createPages() {
        val inflater = LayoutInflater.from(requireContext())
        visibleTabs.forEach { tab ->
            val root = inflater.inflate(R.layout.view_library_page, pager, false)
            val page = Page(root)
            page.list.layoutManager = LinearLayoutManager(requireContext())
            page.list.itemAnimator = null
            page.skeleton.setBackgroundResource(R.color.primaryColorTheme)
            pages[tab] = page
        }

        val playlistsPage = pages.getValue(Tab.PLAYLISTS)
        val playlistsHeader = buildPlaylistsHeader(playlistsPage.list)
        playlistsPage.header = playlistsHeader
        playlistsPage.list.adapter = ConcatAdapter(HeaderViewAdapter(playlistsHeader), playlistAdapter)
        val songsPage = pages.getValue(Tab.SONGS)
        songsActions = layoutInflater.inflate(R.layout.view_collection_actions, songsPage.list, false).apply {
            setPadding(paddingLeft, (4 * resources.displayMetrics.density).toInt(), paddingRight, (12 * resources.displayMetrics.density).toInt())
        }
        CollectionActions.bind(
            songsActions,
            Tab.SONGS.accent,
            onPlayAll = { favoritesViewModel.favoriteSongs.value?.let { playFavorites(it, 0) } },
            onShuffle = { favoritesViewModel.favoriteSongs.value?.shuffled()?.let { playFavorites(it, 0) } }
        )
        songsHeader = HeaderViewAdapter(songsActions).apply { isVisible = false }
        songsPage.header = songsActions
        songsPage.list.adapter = ConcatAdapter(songsHeader, songsAdapter)
        pages.getValue(Tab.ARTISTS).list.adapter = artistsAdapter
        pages.getValue(Tab.ALBUMS).list.adapter = albumsAdapter
        val liked = LikedPlaymixesSection(this).also { likedPlaymixes = it }
        liked.onChanged = { renderPlaymixes(playmixListViewModel.playmixes.value) }
        val playmixPage = pages.getValue(Tab.PLAYMIXES)
        val playmixHeader = buildPlaymixHeader(playmixPage.list)
        playmixPage.header = playmixHeader
        playmixPage.list.adapter = ConcatAdapter(
            listOf(HeaderViewAdapter(playmixHeader), playmixAdapter) + liked.adapters
        )
        pages[Tab.AI_TRACKS]?.let { aiPage ->
            val aiHeader = buildAiTracksHeader(aiPage.list)
            aiPage.header = aiHeader
            aiPage.list.adapter = ConcatAdapter(HeaderViewAdapter(aiHeader), aiTrackAdapter)
        }
    }

    private fun setupPagerAndTabs() {
        val views = visibleTabs.map { pages.getValue(it).root }
        pager.adapter = PagesAdapter(views)
        // Todas las páginas vivas: son ligeras y así nunca se recrean al cambiar.
        pager.offscreenPageLimit = visibleTabs.size - 1
        pager.setCurrentItem(selectedTab.ordinal, false)

        tabsView.followsPager = true
        tabsView.setTabs(
            labels = visibleTabs.map { it.label },
            tabAccents = visibleTabs.map { it.accent.color },
            initialIndex = selectedTab.ordinal
        ) { index -> pager.currentItem = index }

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                tabsView.setScrollProgress(position, positionOffset)
                tintHeroWhileScrolling(position, positionOffset)
            }

            override fun onPageSelected(position: Int) {
                val tab = visibleTabs[position]
                tabsView.markSelected(position)
                if (tab == selectedTab) return
                selectedTab = tab
                // La pestaña de IA consulta el estado de sus generaciones solo mientras se ve.
                if (::aiTrackViewModel.isInitialized) aiTrackViewModel.setVisible(tab == Tab.AI_TRACKS)
                updateHero(animate = true)
                // Al volver a PlayMix se refresca en segundo plano (la caché ya está pintada).
                if (tab == Tab.PLAYMIXES) {
                    playmixListViewModel.loadMyPlaymixes()
                    likedPlaymixes?.refresh()
                }
            }
        })
    }

    /** Páginas fijas ya creadas: una vista por tipo, nunca se reciclan entre sí. */
    private class PagesAdapter(private val views: List<View>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = views.size
        override fun getItemViewType(position: Int) = position
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = views[viewType]
            (view.parent as? ViewGroup)?.removeView(view)
            view.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            return object : RecyclerView.ViewHolder(view) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }

    // ── Cabeceras de lista ──────────────────────────────────────────────

    private fun buildPlaylistsHeader(parent: RecyclerView): View {
        val density = resources.displayMetrics.density
        val row = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (12 * density).toInt())
        }
        downloadsShortcut = layoutInflater.inflate(R.layout.view_library_shortcut, parent, false)
        val create = layoutInflater.inflate(R.layout.view_library_shortcut, parent, false)
        bindShortcut(downloadsShortcut, ResonantAccent.DOWNLOADS, R.drawable.ic_download, "Descargas", "Sin conexión") {
            findNavController().navigate(R.id.action_savedFragment_to_downloadedSongsFragment)
        }
        bindShortcut(create, ResonantAccent.PLAYLISTS, R.drawable.ic_add, "Nueva playlist", "Empieza una lista") {
            findNavController().navigate(R.id.action_savedFragment_to_createPlaylistFragment)
        }
        row.addView(downloadsShortcut, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = (5 * density).toInt()
        })
        row.addView(create, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = (5 * density).toInt()
        })
        return row
    }

    private fun buildPlaymixHeader(parent: RecyclerView): View {
        val density = resources.displayMetrics.density
        return android.widget.FrameLayout(requireContext()).apply {
            setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (12 * density).toInt())
            val create = layoutInflater.inflate(R.layout.view_library_shortcut, parent, false)
            bindShortcut(create, ResonantAccent.PLAYMIX, R.drawable.ic_add, "Nueva PlayMix", "Mezcla canciones con transiciones") {
                findNavController().navigate(R.id.action_global_to_createPlaymixFragment)
            }
            addView(create)
        }
    }

    private fun buildAiTracksHeader(parent: RecyclerView): View {
        val density = resources.displayMetrics.density
        return android.widget.FrameLayout(requireContext()).apply {
            setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (12 * density).toInt())
            val create = layoutInflater.inflate(R.layout.view_library_shortcut, parent, false)
            bindShortcut(create, ResonantAccent.AI_TRACKS, R.drawable.ic_star_ai, "Crear pista", "Instrumentales con Lyria") {
                findNavController().navigate(R.id.action_global_to_aiTrackCreateFragment)
            }
            addView(create)
        }
    }

    private fun bindShortcut(
        view: View,
        accent: ResonantAccent,
        @DrawableRes icon: Int,
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ) {
        view.findViewById<ImageView>(R.id.shortcutIcon).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(accent.color)
        }
        view.findViewById<TextView>(R.id.shortcutTitle).text = title
        view.findViewById<TextView>(R.id.shortcutSubtitle).text = subtitle
        view.setOnClickListener { onClick() }
    }

    // ── Adapters ────────────────────────────────────────────────────────

    private fun setupAdapters() {
        playlistAdapter = PlaylistAdapter(
            viewType = PlaylistAdapter.VIEW_TYPE_GRID,
            onClick = { playlist ->
                val bundle = Bundle().apply {
                    putString("playlistId", playlist.id)
                    putBoolean("isReadOnly", !canManagePlaylist(playlist))
                }
                findNavController().navigate(R.id.action_savedFragment_to_playlistFragment, bundle)
            },
            onPlaylistLongClick = { playlist, _ -> openPlaylistOptionsIfEditable(playlist) },
            onSettingsClick = { playlist -> openPlaylistOptionsIfEditable(playlist) }
        )

        aiTrackAdapter = AiTrackAdapter(
            onOpen = { track -> AiTrackNavigation.open(findNavController(), track) },
            onRetry = { track -> aiTrackViewModel.retry(track.id) }
        )

        songsAdapter = buildFavoritesAdapter()
        artistsAdapter = buildFavoritesAdapter()
        albumsAdapter = buildFavoritesAdapter()

        playmixAdapter = PlaymixListAdapter(
            onClick = { playmix ->
                val bundle = Bundle().apply { putString("playmixId", playmix.id) }
                findNavController().navigate(R.id.action_global_to_playmixDetailFragment, bundle)
            },
            onOptionsClick = { playmix ->
                PlaymixListOptionsBottomSheet(
                    playmix = playmix,
                    onDeleteClick = { p ->
                        ResonantDialog(requireContext())
                            .setSection("PlayMix")
                            .setTitle("Borrar PlayMix")
                            .setMessage("¿Borrar \"${p.name}\"? Esta acción no se puede deshacer.")
                            .setDestructive()
                            .setPositiveButton("Borrar") {
                                playmixListViewModel.deletePlaymix(p.id) { ok ->
                                    if (ok) showResonantSnackbar(p.name, SnackType.SUCCESS, title = "PlayMix eliminada")
                                    else showResonantSnackbar("Se ha restaurado en tu biblioteca", SnackType.ERROR, title = "No se pudo borrar la PlayMix")
                                }
                            }
                            .setNegativeButton("Cancelar")
                            .show()
                    }
                ).show(childFragmentManager, "PlaymixListOptions")
            }
        )
    }

    /** Adapter de favoritos (canciones, artistas o álbumes) con todas sus acciones. */
    private fun buildFavoritesAdapter() = SearchResultAdapter().apply {
        // Songs
        onSongClick = { (song, bitmap) ->
            val songs = favoritesViewModel.favoriteSongs.value ?: emptyList()
            val currentIndex = songs.indexOfFirst { it.id == song.id }
            if (currentIndex != -1) playFavorites(songs, currentIndex, song, bitmap)
        }

        onSettingsClick = { song -> ItemOptions.song(this@SavedFragment, song) }
        onFavoriteClick = { song, _ ->
            favoritesViewModel.toggleFavoriteSong(song, favoriteSongFeedback(song))
        }

        // Albums
        onAlbumClick = { album ->
            val bundle = Bundle().apply { putString("albumId", album.id) }
            findNavController().navigate(R.id.action_savedFragment_to_albumFragment, bundle)
        }
        onAlbumSettingsClick = { album ->
            val bottomSheet = AlbumOptionsBottomSheet(
                album = album,
                onGoToAlbumClick = {
                    val bundle = Bundle().apply { putString("albumId", it.id) }
                    findNavController().navigate(R.id.action_savedFragment_to_albumFragment, bundle)
                },
                onGoToArtistClick = { albumObj ->
                    val artists = albumObj.artists
                    if (artists.isNotEmpty()) {
                        if (artists.size > 1) {
                            val selector = ArtistSelectorBottomSheet(artists) { selectedArtist ->
                                val bundle = Bundle().apply { putString("artistId", selectedArtist.id) }
                                findNavController().navigate(R.id.action_savedFragment_to_artistFragment, bundle)
                            }
                            selector.show(parentFragmentManager, "ArtistSelectorBottomSheet")
                        } else {
                            val artist = artists[0]
                            val bundle = Bundle().apply { putString("artistId", artist.id) }
                            findNavController().navigate(R.id.action_savedFragment_to_artistFragment, bundle)
                        }
                    }
                },
                onViewDetailsClick = {
                    val bundle = Bundle().apply {
                        putParcelable("album", it)
                        putString("albumId", it.id)
                    }
                    findNavController().navigate(R.id.action_global_to_detailedAlbumFragment, bundle)
                }
            )
            bottomSheet.show(childFragmentManager, bottomSheet.tag)
        }

        // Artists
        onArtistClick = { artist, _ ->
            val bundle = Bundle().apply { putString("artistId", artist.id) }
            findNavController().navigate(R.id.action_savedFragment_to_artistFragment, bundle)
        }
        onArtistSettingsClick = { artist ->
            val bottomSheet = ArtistOptionsBottomSheet(
                artist = artist,
                onGoToArtistClick = {
                    val bundle = Bundle().apply { putString("artistId", it.id) }
                    findNavController().navigate(R.id.action_savedFragment_to_artistFragment, bundle)
                },
                onViewDetailsClick = {
                    val bundle = Bundle().apply {
                        putParcelable("artist", it)
                        putString("artistId", it.id)
                    }
                    findNavController().navigate(R.id.action_global_to_detailedArtistFragment, bundle)
                }
            )
            bottomSheet.show(childFragmentManager, bottomSheet.tag)
        }
    }

    private fun playFavorites(songs: List<Song>, index: Int, song: Song? = null, bitmap: Bitmap? = null) {
        if (index !in songs.indices) return
        val current = song ?: songs[index]
        val songArrayList = ArrayList(songs)
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), it, current.id) }
            val intent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_PLAY
                putExtra(MusicPlaybackService.EXTRA_CURRENT_SONG, current)
                putExtra(MusicPlaybackService.EXTRA_CURRENT_INDEX, index)
                putExtra(MusicPlaybackService.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, songArrayList)
                putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE, QueueSource.FAVORITE_SONGS)
                putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE_ID, "favorites")
            }
            requireContext().startService(intent)
        }
    }

    // ── Datos → listas ──────────────────────────────────────────────────

    private fun observeData() {
        playlistsListViewModel.playlists.observe(viewLifecycleOwner) { playlists ->
            playlistAdapter.submitList(playlists ?: emptyList()) {
                render(Tab.PLAYLISTS, loaded = playlists != null, isEmpty = playlists.isNullOrEmpty())
            }
            playlists?.let { setCount(Tab.PLAYLISTS, it.size) }
        }

        favoritesViewModel.favoriteSongs.observe(viewLifecycleOwner) { songs ->
            songsHeader.isVisible = !songs.isNullOrEmpty()
            songsAdapter.submitList(songs?.map { SearchResult.SongItem(it) } ?: emptyList()) {
                render(Tab.SONGS, loaded = songs != null, isEmpty = songs.isNullOrEmpty())
            }
            songs?.let { setCount(Tab.SONGS, it.size) }
        }
        favoritesViewModel.favoriteSongIds.observe(viewLifecycleOwner) { ids ->
            songsAdapter.favoriteSongIds = ids
        }
        favoritesViewModel.favoriteArtists.observe(viewLifecycleOwner) { artists ->
            artistsAdapter.submitList(artists?.map { SearchResult.ArtistItem(it) } ?: emptyList()) {
                render(Tab.ARTISTS, loaded = artists != null, isEmpty = artists.isNullOrEmpty())
            }
            artists?.let { setCount(Tab.ARTISTS, it.size) }
        }
        favoritesViewModel.favoriteAlbums.observe(viewLifecycleOwner) { albums ->
            albumsAdapter.submitList(albums?.map { SearchResult.AlbumItem(it) } ?: emptyList()) {
                render(Tab.ALBUMS, loaded = albums != null, isEmpty = albums.isNullOrEmpty())
            }
            albums?.let { setCount(Tab.ALBUMS, it.size) }
        }

        playmixListViewModel.playmixes.observe(viewLifecycleOwner) { playmixes ->
            playmixAdapter.submitList(playmixes ?: emptyList()) { renderPlaymixes(playmixes) }
            playmixes?.let { setCount(Tab.PLAYMIXES, it.size) }
        }

        aiTrackViewModel.items.observe(viewLifecycleOwner) { tracks ->
            aiTrackAdapter.submitList(tracks ?: emptyList()) {
                render(Tab.AI_TRACKS, loaded = tracks != null, isEmpty = tracks.isNullOrEmpty())
            }
            tracks?.let { setCount(Tab.AI_TRACKS, it.size) }
        }
        aiTrackViewModel.error.observe(viewLifecycleOwner) { message ->
            message ?: return@observe
            showResonantSnackbar(message, SnackType.ERROR)
            aiTrackViewModel.onErrorShown()
        }

        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            currentSong?.let { songsAdapter.setCurrentPlayingSong(it.id) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                downloadViewModel.downloadedSongIds.collect { ids ->
                    songsAdapter.downloadedSongIds = ids
                    downloadsShortcut.findViewById<TextView>(R.id.shortcutSubtitle).text = when (ids.size) {
                        0 -> "Sin conexión"
                        1 -> "1 canción sin conexión"
                        else -> "${ids.size} canciones sin conexión"
                    }
                }
            }
        }

        // Estado inicial de cada página antes de que llegue nada (skeleton si no hay datos).
        render(Tab.PLAYLISTS, playlistsListViewModel.playlists.value != null, playlistsListViewModel.playlists.value.isNullOrEmpty())
        render(Tab.SONGS, favoritesViewModel.favoriteSongs.value != null, favoritesViewModel.favoriteSongs.value.isNullOrEmpty())
        render(Tab.ARTISTS, favoritesViewModel.favoriteArtists.value != null, favoritesViewModel.favoriteArtists.value.isNullOrEmpty())
        render(Tab.ALBUMS, favoritesViewModel.favoriteAlbums.value != null, favoritesViewModel.favoriteAlbums.value.isNullOrEmpty())
        render(Tab.PLAYMIXES, playmixListViewModel.playmixes.value != null, playmixListViewModel.playmixes.value.isNullOrEmpty())
        render(Tab.AI_TRACKS, aiTrackViewModel.items.value != null, aiTrackViewModel.items.value.isNullOrEmpty())
    }

    /** Skeleton mientras no hay datos, estado vacío con acción si no hay filas y entrada escalonada la 1ª vez. */
    /** Vacía solo sin PlayMix propias ni con me gusta: si no, "Aún no tienes PlayMix" taparía "Me gustan". */
    private fun renderPlaymixes(playmixes: List<*>?) {
        render(
            Tab.PLAYMIXES,
            loaded = playmixes != null,
            isEmpty = playmixes.isNullOrEmpty() && likedPlaymixes?.hasItems != true
        )
    }

    private fun render(tab: Tab, loaded: Boolean, isEmpty: Boolean) {
        val page = pages[tab] ?: return
        page.applyHeaderOffset()
        val showSkeleton = !loaded
        if (page.skeleton.isVisible != showSkeleton) {
            page.skeleton.isVisible = showSkeleton
            if (showSkeleton) page.skeleton.startShimmer() else page.skeleton.stopShimmer()
        }

        if (loaded && isEmpty) showEmptyState(tab, page.state) else CollectionState.hide(page.state)

        if (loaded && !isEmpty && !page.listAnimated) {
            page.listAnimated = true
            page.list.scheduleLayoutAnimation()
        }
    }

    private fun showEmptyState(tab: Tab, state: View) {
        val explore = {
            requireActivity().findViewById<BottomNavigationView>(R.id.bottom_navigation)
                ?.selectedItemId = R.id.exploreFragment
        }
        when (tab) {
            Tab.PLAYLISTS -> CollectionState.show(
                state, tab.accent, R.drawable.ic_playlist_stack,
                title = "Crea tu primera playlist",
                subtitle = "Organiza tu música a tu manera, o guarda las de la comunidad"
            )
            Tab.SONGS -> CollectionState.show(
                state, tab.accent, R.drawable.ic_add_favorite,
                title = "Aún no tienes canciones favoritas",
                subtitle = "Toca el corazón de cualquier canción para guardarla aquí",
                actionText = "Explorar música", onAction = explore
            )
            Tab.ARTISTS -> CollectionState.show(
                state, tab.accent, R.drawable.ic_user,
                title = "Aún no sigues a ningún artista",
                subtitle = "Sigue a tus artistas para tenerlos siempre a mano",
                actionText = "Descubrir artistas", onAction = explore
            )
            Tab.ALBUMS -> CollectionState.show(
                state, tab.accent, R.drawable.ic_playlist_stack,
                title = "Aún no has guardado álbumes",
                subtitle = "Guarda un álbum desde su página para verlo aquí",
                actionText = "Explorar música", onAction = explore
            )
            Tab.PLAYMIXES -> CollectionState.show(
                state, tab.accent, R.drawable.ic_playmix,
                title = "Aún no tienes PlayMix",
                subtitle = "Mezcla canciones con transiciones a tu gusto"
            )
            Tab.AI_TRACKS -> CollectionState.show(
                state, tab.accent, R.drawable.ic_star_ai,
                title = "Aún no has creado pistas",
                subtitle = "Describe lo que quieres escuchar y Lyria lo compone para ti",
                actionText = "Crear pista",
                onAction = { findNavController().navigate(R.id.action_global_to_aiTrackCreateFragment) }
            )
        }
    }

    // ── Playlists: permisos y opciones ──────────────────────────────────

    private fun canManagePlaylist(playlist: Playlist): Boolean {
        if (playlist.canEdit == true) return true
        val currentUserId = UserManager(requireContext()).getUserId()
        return !currentUserId.isNullOrBlank() &&
            !playlist.userId.isNullOrBlank() &&
            currentUserId == playlist.userId
    }

    private fun openPlaylistOptionsIfEditable(playlist: Playlist) {
        if (!canManagePlaylist(playlist)) {
            // Lista guardada de otro usuario: sus opciones de lectura (reproducir, abrir).
            ItemOptions.playlist(this, playlist)
            return
        }
        val bottomSheet = PlaylistOptionsBottomSheet(
            playlist = playlist,
            playlistImageBitmap = null,
            onDeleteClick = { playlistToDelete ->
                playlistsListViewModel.deletePlaylist(playlistToDelete.id!!) { ok ->
                    if (ok) showResonantSnackbar(playlistToDelete.name, SnackType.SUCCESS, title = "Playlist eliminada")
                    else showResonantSnackbar("Se ha restaurado en tu biblioteca", SnackType.ERROR, title = "No se pudo borrar la playlist")
                }
            },
            onEditClick = { playlistToEdit ->
                val bundle = Bundle().apply {
                    putParcelable("playlist", playlistToEdit)
                }
                findNavController().navigate(R.id.action_savedFragment_to_editPlaylistFragment, bundle)
            },
            onToggleVisibilityClick = { pl ->
                playlistsListViewModel.toggleVisibility(
                    playlistId = pl.id!!,
                    currentIsPublic = pl.isPublic ?: false,
                    onSuccess = { newIsPublic ->
                        val msg = if (newIsPublic) "Playlist ahora es pública" else "Playlist ahora es privada"
                        showResonantSnackbar(
                            text = msg,
                            colorRes = R.color.successColor,
                            iconRes = R.drawable.ic_success
                        )
                    },
                    onError = { err ->
                        showResonantSnackbar(
                            text = "Error: $err",
                            colorRes = R.color.errorColor,
                            iconRes = R.drawable.ic_error
                        )
                    }
                )
            }
        )
        bottomSheet.show(childFragmentManager, bottomSheet.tag)
    }

    private fun reloadPlaylistsInitial() {
        if (playlistsListViewModel.playlists.value.isNullOrEmpty()) {
            forceReloadPlaylists()
        }
    }

    private fun forceReloadPlaylists() {
        playlistsListViewModel.loadLibraryPlaylists()
    }

    override fun onDestroyView() {
        pages.values.forEach { it.skeleton.stopShimmer() }
        pages.clear()
        super.onDestroyView()
    }
}
