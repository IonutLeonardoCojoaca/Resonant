package com.example.resonant.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.widget.NestedScrollView
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.button.MaterialButton
import com.example.resonant.utils.ScrollHeaderBehavior
import com.example.resonant.R
import com.example.resonant.playback.PlaySourceMapper
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.adapters.AlbumAdapter
import com.example.resonant.ui.adapters.ArtistAdapter
import com.example.resonant.ui.adapters.SongAdapter
import com.example.resonant.ui.bottomsheets.AlbumOptionsBottomSheet
import com.example.resonant.ui.bottomsheets.ArtistOptionsBottomSheet
import com.example.resonant.ui.bottomsheets.ArtistSelectorBottomSheet
import com.example.resonant.ui.bottomsheets.SelectPlaylistBottomSheet
import com.example.resonant.ui.bottomsheets.SongOptionsBottomSheet
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.ui.viewmodels.HomeViewModel
import com.example.resonant.ui.viewmodels.SongViewModel
import com.example.resonant.ui.viewmodels.UserViewModel
import com.example.resonant.utils.Utils
import com.facebook.shimmer.ShimmerFrameLayout
import kotlinx.coroutines.launch
import com.example.resonant.ui.viewmodels.DownloadViewModel
import androidx.core.view.isVisible
import com.example.resonant.managers.CapabilitiesManager
import com.example.resonant.managers.NotificationsManager
import com.example.resonant.notifications.NotificationBadge
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.palette.graphics.Palette
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.example.resonant.ui.views.AuroraView
import com.example.resonant.utils.ImageRequestHelper
import com.google.firebase.auth.FirebaseAuth
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.example.resonant.utils.SnackbarUtils.favoriteSongFeedback

class HomeFragment : BaseFragment(R.layout.fragment_home) {

    companion object {
        private const val TOP_SONGS_COLLAPSED_COUNT = 5
        private const val HERO_BLUR = 60f
        private const val PALETTE_SIZE = 120
        private const val REVEAL_CHILDREN = 6
        private const val REVEAL_MS = 520L
        private const val REVEAL_STAGGER_MS = 70L
    }

    /** La entrada animada solo la primera vez (volver de otra pantalla no la repite). */
    private var savedHomeRevealDone = false

    private lateinit var recyclerViewHistory: RecyclerView
    private lateinit var historyAdapter: SongAdapter

    private lateinit var recyclerViewRecentFavorites: RecyclerView
    private lateinit var recentFavoritesAdapter: SongAdapter
    private lateinit var recentFavoritesContainer: View
    private lateinit var shimmerRecentFavoritesLayout: ShimmerFrameLayout

    private lateinit var recyclerViewArtists: RecyclerView
    private lateinit var artistAdapter: ArtistAdapter

    private lateinit var recyclerViewAlbums: RecyclerView
    private lateinit var albumsAdapter: AlbumAdapter

    private lateinit var recyclerViewSongs: RecyclerView
    private lateinit var songAdapter: SongAdapter
    private lateinit var shimmerSongLayout: ShimmerFrameLayout
    private lateinit var layoutErrorSongs: LinearLayout
    private lateinit var tvErrorSongs: TextView
    private lateinit var songsFeaturedTitle: TextView

    private lateinit var recyclerViewTopSongs: RecyclerView
    private lateinit var topSongAdapter: SongAdapter
    private lateinit var listTopSongsContainer: LinearLayout
    private lateinit var shimmerTopSongs: ShimmerFrameLayout
    private lateinit var btnSeeMoreTopSongs: MaterialButton
    private var fullTopSongsList: List<com.example.resonant.data.models.Song> = emptyList()
    private var isTopSongsExpanded = false

    private lateinit var recyclerViewTopArtists: RecyclerView
    private lateinit var topArtistAdapter: ArtistAdapter
    private lateinit var topArtistsContainer: View
    private lateinit var tvTopArtistsTitle: TextView
    private lateinit var shimmerTopArtists: ShimmerFrameLayout

    private lateinit var recyclerViewTopAlbums: RecyclerView
    private lateinit var topAlbumAdapter: AlbumAdapter
    private lateinit var topAlbumsContainer: View
    private lateinit var tvTopAlbumsTitle: TextView
    private lateinit var shimmerTopAlbums: ShimmerFrameLayout

    private lateinit var songsFeaturedTitleAlbums: TextView
    private lateinit var songsFeaturedTitleArtists: TextView

    // Containers
    private lateinit var historyContainer: View

    // Shimmer & Error Layouts
    private lateinit var shimmerHistoryLayout: ShimmerFrameLayout
    private lateinit var shimmerArtistLayout: ShimmerFrameLayout
    private lateinit var shimmerAlbumLayout: ShimmerFrameLayout
    private lateinit var layoutErrorHistory: LinearLayout
    private lateinit var layoutErrorArtists: LinearLayout
    private lateinit var layoutErrorAlbums: LinearLayout
    private lateinit var tvErrorHistory: TextView
    private lateinit var tvErrorArtists: TextView
    private lateinit var tvErrorAlbums: TextView

    private lateinit var userProfileImage: ImageView

    // Campana de la bandeja de notificaciones (solo con features.notifications)
    private lateinit var notificationBell: View
    private lateinit var notificationBadge: TextView

    // ViewModels
    private lateinit var songViewModel: SongViewModel
    private lateinit var favoritesViewModel: FavoritesViewModel
    private lateinit var userViewModel: UserViewModel
    private lateinit var homeViewModel: HomeViewModel

    private lateinit var downloadViewModel: DownloadViewModel

    private var scrollBehavior: ScrollHeaderBehavior? = null

    // ─── Marca: aurora, saludo y "Sigue escuchando" ───
    private lateinit var topArtistsHeader: View
    private lateinit var topAlbumsHeader: View
    private var aurora: AuroraView? = null
    private var auroraLayer: View? = null
    private var heroSongId: String? = null
    private var heroPaletteTarget: CustomTarget<Bitmap>? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)
        initViews(view)
        setupRecyclerViews()

        setupViewModels()

        return view
    }

    override fun onResume() {
        super.onResume()
        exitTransition = androidx.transition.Fade().apply { duration = 50L }
        refreshNotificationBell()
        aurora?.setAnimating(true)
        // La hora puede haber cambiado (mañana → tarde) mientras la pantalla estaba en segundo plano.
        view?.let { bindGreeting(it) }
    }

    override fun onPause() {
        aurora?.setAnimating(false)
        super.onPause()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val normalHeader = view.findViewById<View>(R.id.superiorToolbar)
        val searchHeader = view.findViewById<View>(R.id.searchHeader)
        val homeScrollView = view.findViewById<NestedScrollView>(R.id.homeScrollView)
        scrollBehavior = ScrollHeaderBehavior(
            normalHeader  = normalHeader,
            searchHeader  = searchHeader,
            onSearchClick = { exitTransition = null; findNavController().navigate(R.id.action_homeFragment_to_searchFragment) }
        )
        scrollBehavior?.attachToNestedScrollView(homeScrollView) { scrollY -> applyHomeParallax(scrollY) }

        setupBrandHeader(view)
        loadHomeContent()
        // view.findViewById<View>(R.id.historyPrincipalContainer)?.visibility = View.GONE // Removed

        observeDownloadedSongIds()
        observeNotificationBell()
    }

    private fun loadHomeContent() {
        homeViewModel.loadHome()
    }

    private fun observeDownloadedSongIds() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                downloadViewModel.downloadedSongIds.collect { downloadedIds ->
                    historyAdapter.downloadedSongIds = downloadedIds
                    recentFavoritesAdapter.downloadedSongIds = downloadedIds
                    songAdapter.downloadedSongIds = downloadedIds
                    topSongAdapter.downloadedSongIds = downloadedIds
                }
            }
        }
    }

    private fun initViews(view: View) {
        shimmerHistoryLayout = view.findViewById(R.id.shimmerHistory)
        shimmerArtistLayout = view.findViewById(R.id.shimmerArtist)
        shimmerAlbumLayout = view.findViewById(R.id.shimmerAlbum)
        songsFeaturedTitleAlbums = view.findViewById(R.id.albumsFeatured)
        songsFeaturedTitleArtists = view.findViewById(R.id.artistFeatured)
        layoutErrorHistory = view.findViewById(R.id.layoutErrorHistory)
        layoutErrorArtists = view.findViewById(R.id.layoutErrorArtists)
        layoutErrorAlbums = view.findViewById(R.id.layoutErrorAlbums)
        tvErrorHistory = view.findViewById(R.id.tvErrorHistory)
        tvErrorArtists = view.findViewById(R.id.tvErrorArtists)
        tvErrorAlbums = view.findViewById(R.id.tvErrorAlbums)
        recyclerViewArtists = view.findViewById(R.id.listArtistsRecycler)
        recyclerViewHistory = view.findViewById(R.id.listHistoryRecycler)
        recyclerViewAlbums = view.findViewById(R.id.listAlbumsRecycler)
        historyContainer = view.findViewById(R.id.historyPrincipalContainer)
        recyclerViewSongs = view.findViewById(R.id.allSongList)
        shimmerSongLayout = view.findViewById(R.id.shimmerSongLayout)
        layoutErrorSongs = view.findViewById(R.id.layoutErrorSongs)
        tvErrorSongs = view.findViewById(R.id.tvErrorSongs)
        songsFeaturedTitle = view.findViewById(R.id.songsFeatured)
        recyclerViewRecentFavorites = view.findViewById(R.id.listRecentFavoritesRecycler)
        recentFavoritesContainer = view.findViewById(R.id.recentFavoritesPrincipalContainer)
        shimmerRecentFavoritesLayout = view.findViewById(R.id.shimmerRecentFavorites)
        listTopSongsContainer = view.findViewById(R.id.listTopSongs)
        recyclerViewTopSongs = view.findViewById(R.id.allTopSongList)
        shimmerTopSongs = view.findViewById(R.id.shimmerTopSongs)
        btnSeeMoreTopSongs = view.findViewById(R.id.btnSeeMoreTopSongs)

        tvTopArtistsTitle = view.findViewById(R.id.tvTopArtistsTitle)
        topArtistsContainer = view.findViewById(R.id.topArtistsContainer)
        recyclerViewTopArtists = view.findViewById(R.id.listTopArtists)
        shimmerTopArtists = view.findViewById(R.id.shimmerTopArtists)

        topArtistsHeader = view.findViewById(R.id.topArtistsHeader)
        topAlbumsHeader = view.findViewById(R.id.topAlbumsHeader)
        tvTopAlbumsTitle = view.findViewById(R.id.tvTopAlbumsTitle)
        topAlbumsContainer = view.findViewById(R.id.topAlbumsContainer)
        recyclerViewTopAlbums = view.findViewById(R.id.listTopAlbums)
        shimmerTopAlbums = view.findViewById(R.id.shimmerTopAlbums)

        userProfileImage = view.findViewById(R.id.userProfile)
        viewLifecycleOwner.lifecycleScope.launch { Utils.loadUserProfile(requireContext(), userProfileImage) }

        view.findViewById<View>(R.id.searchButton).setOnClickListener {
            exitTransition = null
            findNavController().navigate(R.id.action_homeFragment_to_searchFragment)
        }

        notificationBell = view.findViewById(R.id.notificationBellButton)
        notificationBadge = view.findViewById(R.id.notificationBellBadge)
        notificationBell.setOnClickListener {
            exitTransition = null
            findNavController().navigate(R.id.action_global_to_notificationInbox)
        }
    }

    /** Badge from the shared counter; the bell only shows with the capability and the server agreeing. */
    private fun observeNotificationBell() {
        val manager = NotificationsManager.get(requireContext())
        manager.unreadCount.observe(viewLifecycleOwner) { unread ->
            val text = NotificationBadge.format(unread ?: 0)
            notificationBadge.text = text.orEmpty()
            notificationBadge.isVisible = text != null
        }
        manager.featureAvailable.observe(viewLifecycleOwner) { renderNotificationBellVisibility() }
    }

    private fun renderNotificationBellVisibility() {
        notificationBell.isVisible = CapabilitiesManager.isNotificationsEnabled() &&
            NotificationsManager.get(requireContext()).featureAvailable.value != false
    }

    /** On resume (also when coming back from the inbox). With the capability off nothing is requested. */
    private fun refreshNotificationBell() {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            CapabilitiesManager.refresh(appContext)
            renderNotificationBellVisibility()
            if (CapabilitiesManager.isNotificationsEnabled()) {
                NotificationsManager.get(appContext).refreshUnreadCount()
            }
        }
    }

    private fun setupRecyclerViews() {
        // History — grid 2x4 ("Retoma desde donde lo has dejado"). El carrusel
        // horizontal se probó y se veía como una fila a medio cortar en el
        // borde de pantalla; el grid de 2 columnas encaja mejor con 8 items.
        recyclerViewHistory.layoutManager = GridLayoutManager(context, 2)
        historyAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_GRID)
        recyclerViewHistory.adapter = historyAdapter
        recyclerViewHistory.isNestedScrollingEnabled = false

        // Recent favorites — lista vertical normal (no grid, no carrusel).
        recyclerViewRecentFavorites.layoutManager = LinearLayoutManager(requireContext())
        recentFavoritesAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_FULL)
        recyclerViewRecentFavorites.adapter = recentFavoritesAdapter
        recyclerViewRecentFavorites.isNestedScrollingEnabled = false

        // Artists para ti — carrusel horizontal (mismo view type que ya usa
        // el carrusel de Top Artists, que ya funciona bien en horizontal).
        recyclerViewArtists.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        artistAdapter = ArtistAdapter(mutableListOf())
        artistAdapter.setViewType(ArtistAdapter.Companion.VIEW_TYPE_GRID)
        
        // Setup artist click listener
        artistAdapter.onArtistClick = { artist, sharedImage ->
            val bundle = Bundle().apply {
                putString("artistId", artist.id)
                putString("artistName", artist.name)
                putString("artistImageUrl", artist.url)
                putString("artistImageTransitionName", sharedImage.transitionName)
            }
            val extras = androidx.navigation.fragment.FragmentNavigatorExtras(
                sharedImage to sharedImage.transitionName
            )
            findNavController().navigate(
                R.id.action_homeFragment_to_artistFragment,
                bundle,
                null,
                extras
            )
        }
        
        // Artist Settings Click
        artistAdapter.onSettingsClick = { artist ->
            val bottomSheet = ArtistOptionsBottomSheet(
                artist = artist,
                onGoToArtistClick = { selectedArtist ->
                    val bundle = Bundle().apply { 
                         putString("artistId", selectedArtist.id)
                         putString("artistName", selectedArtist.name)
                         putString("artistImageUrl", selectedArtist.url)
                    }
                    findNavController().navigate(R.id.action_homeFragment_to_artistFragment, bundle)
                },
                onViewDetailsClick = {
                     val bundle = Bundle().apply { 
                         putParcelable("artist", it)
                         putString("artistId", it.id)
                     }
                     findNavController().navigate(R.id.action_global_to_detailedArtistFragment, bundle)
                }
            )
            bottomSheet.show(parentFragmentManager, "ArtistOptionsBottomSheet")
        }
        
        recyclerViewArtists.adapter = artistAdapter
        recyclerViewArtists.isNestedScrollingEnabled = false

        // Albums para ti — carrusel horizontal (mismo tratamiento que Top Albums).
        recyclerViewAlbums.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        albumsAdapter = AlbumAdapter(mutableListOf(), 0)
        albumsAdapter.itemWidthOverride = dpToPx(
            if (resources.configuration.screenWidthDp >= 600) 180 else 156
        )
        albumsAdapter.onAlbumClick = { album ->
            val bundle = Bundle().apply { putString("albumId", album.id) }
            findNavController().navigate(R.id.action_homeFragment_to_albumFragment, bundle)
        }
        
        // Album Settings Click
        albumsAdapter.onSettingsClick = { album ->
             val bottomSheet = AlbumOptionsBottomSheet(
                 album = album,
                onGoToAlbumClick = {
                    val bundle = Bundle().apply { putString("albumId", it.id) }
                    findNavController().navigate(R.id.action_homeFragment_to_albumFragment, bundle)
                },
                onGoToArtistClick = {
                    val artists = it.artists
                     if (artists.isNotEmpty()) {
                        if (artists.size > 1) {
                            val selector = ArtistSelectorBottomSheet(artists) { selectedArtist ->
                                val bundle = Bundle().apply { 
                                     putString("artistId", selectedArtist.id)
                                     putString("artistName", selectedArtist.name)
                                     putString("artistImageUrl", selectedArtist.url)
                                }
                                findNavController().navigate(R.id.action_homeFragment_to_artistFragment, bundle)
                            }
                            selector.show(parentFragmentManager, "ArtistSelectorBottomSheet")
                        } else {
                            val artist = artists[0]
                             val bundle = Bundle().apply { 
                                 putString("artistId", artist.id)
                                 putString("artistName", artist.name)
                                 putString("artistImageUrl", artist.url)
                            }
                            findNavController().navigate(R.id.action_homeFragment_to_artistFragment, bundle)
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
             bottomSheet.show(parentFragmentManager, "AlbumOptionsBottomSheet")
        }
        
        recyclerViewAlbums.adapter = albumsAdapter
        recyclerViewAlbums.isNestedScrollingEnabled = false

        // Recomendado para ti (canciones) — listado vertical, entre
        // Álbumes para ti y Artistas que más escuchas.
        recyclerViewSongs.layoutManager = LinearLayoutManager(requireContext())
        songAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_FULL)
        recyclerViewSongs.adapter = songAdapter
        recyclerViewSongs.isNestedScrollingEnabled = false

        // Top Songs — vertical list
        recyclerViewTopSongs.layoutManager = LinearLayoutManager(requireContext())
        topSongAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_FULL)
        recyclerViewTopSongs.adapter = topSongAdapter
        recyclerViewTopSongs.isNestedScrollingEnabled = false

        // Top Artists — horizontal list
        recyclerViewTopArtists.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        topArtistAdapter = ArtistAdapter(mutableListOf())
        topArtistAdapter.setViewType(ArtistAdapter.Companion.VIEW_TYPE_GRID)
        topArtistAdapter.onArtistClick = { artist, sharedImage ->
            val bundle = Bundle().apply {
                putString("artistId", artist.id)
                putString("artistName", artist.name)
                putString("artistImageUrl", artist.url)
                putString("artistImageTransitionName", sharedImage.transitionName)
            }
            val extras = androidx.navigation.fragment.FragmentNavigatorExtras(
                sharedImage to sharedImage.transitionName
            )
            findNavController().navigate(R.id.action_homeFragment_to_artistFragment, bundle, null, extras)
        }
        topArtistAdapter.onSettingsClick = { artist ->
            val bottomSheet = ArtistOptionsBottomSheet(
                artist = artist,
                onGoToArtistClick = { selectedArtist ->
                    val bundle = Bundle().apply {
                        putString("artistId", selectedArtist.id)
                        putString("artistName", selectedArtist.name)
                        putString("artistImageUrl", selectedArtist.url)
                    }
                    findNavController().navigate(R.id.action_homeFragment_to_artistFragment, bundle)
                },
                onViewDetailsClick = {
                    val bundle = Bundle().apply {
                        putParcelable("artist", it)
                        putString("artistId", it.id)
                    }
                    findNavController().navigate(R.id.action_global_to_detailedArtistFragment, bundle)
                }
            )
            bottomSheet.show(parentFragmentManager, "ArtistOptionsBottomSheet")
        }
        recyclerViewTopArtists.adapter = topArtistAdapter
        recyclerViewTopArtists.isNestedScrollingEnabled = false

        // Top Albums — horizontal list
        recyclerViewTopAlbums.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        topAlbumAdapter = AlbumAdapter(mutableListOf(), 0)
        topAlbumAdapter.onAlbumClick = { album ->
            val bundle = Bundle().apply { putString("albumId", album.id) }
            findNavController().navigate(R.id.action_homeFragment_to_albumFragment, bundle)
        }
        topAlbumAdapter.onSettingsClick = { album ->
            val bottomSheet = AlbumOptionsBottomSheet(
                album = album,
                onGoToAlbumClick = {
                    val bundle = Bundle().apply { putString("albumId", it.id) }
                    findNavController().navigate(R.id.action_homeFragment_to_albumFragment, bundle)
                },
                onGoToArtistClick = {
                    val artists = it.artists
                    if (artists.isNotEmpty()) {
                        if (artists.size > 1) {
                            val selector = ArtistSelectorBottomSheet(artists) { selectedArtist ->
                                val bundle = Bundle().apply {
                                    putString("artistId", selectedArtist.id)
                                    putString("artistName", selectedArtist.name)
                                    putString("artistImageUrl", selectedArtist.url)
                                }
                                findNavController().navigate(R.id.action_homeFragment_to_artistFragment, bundle)
                            }
                            selector.show(parentFragmentManager, "ArtistSelectorBottomSheet")
                        } else {
                            val artist = artists[0]
                            val bundle = Bundle().apply {
                                putString("artistId", artist.id)
                                putString("artistName", artist.name)
                                putString("artistImageUrl", artist.url)
                            }
                            findNavController().navigate(R.id.action_homeFragment_to_artistFragment, bundle)
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
            bottomSheet.show(parentFragmentManager, "AlbumOptionsBottomSheet")
        }
        recyclerViewTopAlbums.adapter = topAlbumAdapter
        recyclerViewTopAlbums.isNestedScrollingEnabled = false
        val horizontalAlbumWidthDp =
            if (resources.configuration.screenWidthDp >= 600) 180 else 156
        topAlbumAdapter.itemWidthOverride = dpToPx(horizontalAlbumWidthDp)

        setupSongClickListeners()
    }

    private fun setupViewModels() {
        songViewModel = ViewModelProvider(requireActivity()).get(SongViewModel::class.java)
        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]
        userViewModel = ViewModelProvider(requireActivity())[UserViewModel::class.java]
        homeViewModel = ViewModelProvider(requireActivity())[HomeViewModel::class.java]
        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]

        setupHomeObservers()
        setupOtherObservers()
    }

    private fun setupHomeObservers() {
        // --- OBSERVAR HISTORIAL ---
        homeViewModel.history.observe(viewLifecycleOwner) { history ->
            if (history.isNullOrEmpty()) {
                historyContainer.visibility = View.GONE
                updateSectionState(false, false, recyclerViewHistory, shimmerHistoryLayout, layoutErrorHistory)
            } else {
                historyContainer.visibility = View.VISIBLE
                historyAdapter.submitList(history)
                updateSectionState(false, false, recyclerViewHistory, shimmerHistoryLayout, layoutErrorHistory)
            }
            bindHero(history?.firstOrNull())
        }
        homeViewModel.historyLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) {
                 historyContainer.visibility = View.VISIBLE
                 updateSectionState(true, false, recyclerViewHistory, shimmerHistoryLayout, layoutErrorHistory)
            } else {
                 if (historyAdapter.currentList.isEmpty()) {
                     historyContainer.visibility = View.GONE
                 }
                 updateSectionState(false, false, recyclerViewHistory, shimmerHistoryLayout, layoutErrorHistory)
            }
        }
        homeViewModel.historyError.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                 historyContainer.visibility = View.VISIBLE
                 updateSectionState(
                    false, true, recyclerViewHistory, shimmerHistoryLayout, layoutErrorHistory, tvErrorHistory, error
                )
            }
        }

        // --- OBSERVAR FAVORITOS RECIENTES ---
        homeViewModel.recentFavorites.observe(viewLifecycleOwner) { songs ->
            if (songs.isNullOrEmpty()) {
                recentFavoritesContainer.visibility = View.GONE
                updateSectionState(false, false, recyclerViewRecentFavorites, shimmerRecentFavoritesLayout, null)
            } else {
                recentFavoritesContainer.visibility = View.VISIBLE
                recentFavoritesAdapter.submitList(songs)
                updateSectionState(false, false, recyclerViewRecentFavorites, shimmerRecentFavoritesLayout, null)
            }
        }
        homeViewModel.recentFavoritesLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) {
                recentFavoritesContainer.visibility = View.VISIBLE
                updateSectionState(true, false, recyclerViewRecentFavorites, shimmerRecentFavoritesLayout, null)
            } else if (recentFavoritesAdapter.currentList.isEmpty()) {
                recentFavoritesContainer.visibility = View.GONE
            }
        }
        homeViewModel.recentFavoritesError.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                recentFavoritesContainer.visibility = View.GONE
            }
        }

        // --- OBSERVAR ARTISTAS ---
        homeViewModel.artists.observe(viewLifecycleOwner) { artists ->
            artistAdapter.submitArtists(artists.orEmpty())
            updateSectionState(false, false, recyclerViewArtists, shimmerArtistLayout, layoutErrorArtists)
        }
        homeViewModel.artistsTitle.observe(viewLifecycleOwner) { title ->
            songsFeaturedTitleArtists.text = title
        }
        homeViewModel.artistsLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) {
                updateSectionState(true, false, recyclerViewArtists, shimmerArtistLayout, layoutErrorArtists)
            }
        }
        homeViewModel.artistsError.observe(viewLifecycleOwner) { error ->
            if (error != null) updateSectionState(
                false, true, recyclerViewArtists, shimmerArtistLayout, layoutErrorArtists, tvErrorArtists, error
            )
        }

        // --- OBSERVAR ÁLBUMES ---
        homeViewModel.albums.observe(viewLifecycleOwner) { albums ->
            albumsAdapter.updateList(albums.orEmpty())
            updateSectionState(false, false, recyclerViewAlbums, shimmerAlbumLayout, layoutErrorAlbums)
        }
        homeViewModel.albumsTitle.observe(viewLifecycleOwner) { title ->
            songsFeaturedTitleAlbums.text = title
        }
        homeViewModel.albumsLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) {
                updateSectionState(true, false, recyclerViewAlbums, shimmerAlbumLayout, layoutErrorAlbums)
            }
        }
        homeViewModel.albumsError.observe(viewLifecycleOwner) { error ->
            if (error != null) updateSectionState(
                false, true, recyclerViewAlbums, shimmerAlbumLayout, layoutErrorAlbums, tvErrorAlbums, error
            )
        }

        // --- OBSERVAR CANCIONES RECOMENDADAS ---
        homeViewModel.songs.observe(viewLifecycleOwner) { songs ->
            songAdapter.submitList(songs)
            updateSectionState(false, false, recyclerViewSongs, shimmerSongLayout, layoutErrorSongs)
        }
        homeViewModel.songsTitle.observe(viewLifecycleOwner) { title ->
            songsFeaturedTitle.text = title
        }
        homeViewModel.songsLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) {
                updateSectionState(true, false, recyclerViewSongs, shimmerSongLayout, layoutErrorSongs)
            }
        }
        homeViewModel.songsError.observe(viewLifecycleOwner) { error ->
            if (error != null) updateSectionState(
                false, true, recyclerViewSongs, shimmerSongLayout, layoutErrorSongs, tvErrorSongs, error
            )
        }

        // --- OBSERVAR ARTISTAS MÁS ESCUCHADOS ---
        homeViewModel.topArtists.observe(viewLifecycleOwner) { artists ->
            if (artists.isNullOrEmpty()) {
                topArtistsContainer.visibility = View.GONE
                topArtistsHeader.visibility = View.GONE
            } else {
                topArtistsContainer.visibility = View.VISIBLE
                topArtistsHeader.visibility = View.VISIBLE
                topArtistAdapter.submitArtists(artists)
                shimmerTopArtists.stopShimmer()
                shimmerTopArtists.visibility = View.GONE
                recyclerViewTopArtists.visibility = View.VISIBLE
            }
        }
        homeViewModel.topArtistsLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) {
                recyclerViewTopArtists.visibility = View.INVISIBLE
                shimmerTopArtists.visibility = View.VISIBLE
                shimmerTopArtists.startShimmer()
            }
        }
        homeViewModel.topArtistsError.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                topArtistsContainer.visibility = View.GONE
                topArtistsHeader.visibility = View.GONE
            }
        }


        // --- OBSERVAR ÁLBUMES MÁS ESCUCHADOS ---
        homeViewModel.topAlbums.observe(viewLifecycleOwner) { albums ->
            if (albums.isNullOrEmpty()) {
                topAlbumsContainer.visibility = View.GONE
                topAlbumsHeader.visibility = View.GONE
            } else {
                topAlbumsContainer.visibility = View.VISIBLE
                topAlbumsHeader.visibility = View.VISIBLE
                topAlbumAdapter.updateList(albums)
                shimmerTopAlbums.stopShimmer()
                shimmerTopAlbums.visibility = View.GONE
                recyclerViewTopAlbums.visibility = View.VISIBLE
            }
        }
        homeViewModel.topAlbumsLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) {
                recyclerViewTopAlbums.visibility = View.INVISIBLE
                shimmerTopAlbums.visibility = View.VISIBLE
                shimmerTopAlbums.startShimmer()
            }
        }
        homeViewModel.topAlbumsError.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                topAlbumsContainer.visibility = View.GONE
                topAlbumsHeader.visibility = View.GONE
            }
        }

        // --- OBSERVAR TUS MÁS ESCUCHADAS ---
        homeViewModel.topSongs.observe(viewLifecycleOwner) { songs ->
            if (songs.isNullOrEmpty()) {
                listTopSongsContainer.visibility = View.GONE
                view?.findViewById<View>(R.id.titleTopSongsContainer)?.visibility = View.GONE
            } else {
                listTopSongsContainer.visibility = View.VISIBLE
                view?.findViewById<View>(R.id.titleTopSongsContainer)?.visibility = View.VISIBLE
                fullTopSongsList = songs
                shimmerTopSongs.stopShimmer()
                shimmerTopSongs.visibility = View.GONE
                recyclerViewTopSongs.visibility = View.VISIBLE
                updateTopSongsListDisplay()
            }
        }
        homeViewModel.topSongsLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) {
                recyclerViewTopSongs.visibility = View.INVISIBLE
                shimmerTopSongs.visibility = View.VISIBLE
                shimmerTopSongs.startShimmer()
            }
        }
        homeViewModel.topSongsError.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                listTopSongsContainer.visibility = View.GONE
                view?.findViewById<View>(R.id.titleTopSongsContainer)?.visibility = View.GONE
            }
        }

        btnSeeMoreTopSongs.setOnClickListener {
            val transition = AutoTransition()
            transition.duration = 200
            transition.excludeChildren(recyclerViewTopSongs, true)
            TransitionManager.beginDelayedTransition(listTopSongsContainer, transition)

            isTopSongsExpanded = !isTopSongsExpanded
            updateTopSongsListDisplay()
        }
    }

    /** Muestra solo las primeras [TOP_SONGS_COLLAPSED_COUNT] canciones hasta que el usuario pide ver más — mismo patrón que ArtistFragment.updateSongsListDisplay(). */
    private fun updateTopSongsListDisplay() {
        if (fullTopSongsList.isEmpty()) {
            btnSeeMoreTopSongs.visibility = View.GONE
            return
        }

        val listToShow = if (isTopSongsExpanded) {
            fullTopSongsList
        } else {
            fullTopSongsList.take(TOP_SONGS_COLLAPSED_COUNT)
        }
        topSongAdapter.submitList(listToShow)

        if (fullTopSongsList.size > TOP_SONGS_COLLAPSED_COUNT) {
            btnSeeMoreTopSongs.visibility = View.VISIBLE
            if (isTopSongsExpanded) {
                btnSeeMoreTopSongs.text = "Ver menos"
                btnSeeMoreTopSongs.setIconResource(R.drawable.ic_keyboard_arrow_up)
            } else {
                val remaining = fullTopSongsList.size - TOP_SONGS_COLLAPSED_COUNT
                btnSeeMoreTopSongs.text = "Ver $remaining más"
                btnSeeMoreTopSongs.setIconResource(R.drawable.ic_keyboard_arrow_down)
            }
        } else {
            btnSeeMoreTopSongs.visibility = View.GONE
        }
    }

    private fun setupOtherObservers() {
        // Song playing
        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { currentSong ->
            currentSong?.let {
                historyAdapter.setCurrentPlayingSong(it.id)
                recentFavoritesAdapter.setCurrentPlayingSong(it.id)
                songAdapter.setCurrentPlayingSong(it.id)
                topSongAdapter.setCurrentPlayingSong(it.id)
            }
        }

        // User Profile
        userViewModel.profileImageUpdated.observe(viewLifecycleOwner) { isUpdated ->
            if (isUpdated) {
                viewLifecycleOwner.lifecycleScope.launch { Utils.loadUserProfile(requireContext(), userProfileImage) }
            }
        }

        // Favorites
        favoritesViewModel.loadFavoriteSongs()
        favoritesViewModel.favoriteSongIds.observe(viewLifecycleOwner) { songIds ->
            historyAdapter.favoriteSongIds = songIds
            recentFavoritesAdapter.favoriteSongIds = songIds
            songAdapter.favoriteSongIds = songIds
            topSongAdapter.favoriteSongIds = songIds
            if (historyAdapter.currentList.isNotEmpty()) {
                historyAdapter.notifyDataSetChanged()
            }
            if (recentFavoritesAdapter.currentList.isNotEmpty()) {
                recentFavoritesAdapter.notifyDataSetChanged()
            }
            if (songAdapter.currentList.isNotEmpty()) {
                songAdapter.notifyDataSetChanged()
            }
            if (topSongAdapter.currentList.isNotEmpty()) {
                topSongAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun setupSongClickListeners() {
        val currentHomeQueueId = System.currentTimeMillis().toString()
        setupAdapterListeners(historyAdapter, currentHomeQueueId, PlaySourceMapper.HOME_HISTORY)
        setupAdapterListeners(recentFavoritesAdapter, currentHomeQueueId, PlaySourceMapper.HOME_OTHER)
        setupAdapterListeners(songAdapter, currentHomeQueueId, PlaySourceMapper.HOME_FOR_YOU) {
            homeViewModel.songsRequestId
        }
        setupAdapterListeners(topSongAdapter, currentHomeQueueId, PlaySourceMapper.HOME_OTHER)
    }

    /**
     * [playSource] es el valor canónico con el que se reportan las escuchas de esta lista y
     * [requestId] el `requestId` de la sección (solo "Canciones para ti"), que se lee al pulsar.
     */
    private fun setupAdapterListeners(
        adapter: SongAdapter,
        queueId: String,
        playSource: String,
        requestId: () -> String? = { null }
    ) {
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
                    putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE, QueueSource.HOME)
                    putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE_ID, queueId)
                    putExtra(MusicPlaybackService.Companion.EXTRA_PLAY_SOURCE_CANONICAL, playSource)
                    requestId()?.let { putExtra(MusicPlaybackService.Companion.EXTRA_RECOMMENDATION_REQUEST_ID, it) }
                }
                requireContext().startService(playIntent)
            }
        }

        adapter.onFavoriteClick = { song, _ -> favoritesViewModel.toggleFavoriteSong(song, favoriteSongFeedback(song)) }

        adapter.onSettingsClick = { song ->
            lifecycleScope.launch {
                // Song already has artists list from the API, use it
                song.artistName = song.artists?.joinToString(", ") { it.name } ?: song.artistName ?: "Desconocido"

                val bottomSheet = SongOptionsBottomSheet(
                    song = song,
                    onSeeSongClick = { selectedSong ->
                        val bundle = Bundle().apply { putParcelable("song", selectedSong) }
                        findNavController().navigate(R.id.action_homeFragment_to_detailedSongFragment, bundle)
                    },
                    onFavoriteToggled = { toggledSong -> favoritesViewModel.toggleFavoriteSong(toggledSong, favoriteSongFeedback(toggledSong)) },
                    onAddToPlaylistClick = { songToAdd ->
                        val sheet = SelectPlaylistBottomSheet(
                            song = songToAdd,
                            onNoPlaylistsFound = { findNavController().navigate(R.id.action_global_to_createPlaylistFragment) }
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
                        findNavController().navigate(R.id.action_homeFragment_to_albumFragment, bundle)
                    },
                    onGoToArtistClick = { artist ->
                         val bundle = Bundle().apply { 
                             putString("artistId", artist.id)
                             putString("artistName", artist.name)
                             putString("artistImageUrl", artist.url)
                        }
                        findNavController().navigate(R.id.action_homeFragment_to_artistFragment, bundle)
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

    // ═══════════════════════════════════════════════
    // MARCA: saludo, buscador, accesos, "Sigue escuchando" y aurora
    // ═══════════════════════════════════════════════

    private fun setupBrandHeader(view: View) {
        aurora = view.findViewById(R.id.homeAurora)
        auroraLayer = view.findViewById(R.id.homeAuroraLayer)
        bindGreeting(view)
        view.findViewById<View>(R.id.homeSearchPill).setOnClickListener {
            exitTransition = null
            findNavController().navigate(R.id.action_homeFragment_to_searchFragment)
        }
        buildShortcuts(view.findViewById(R.id.homeShortcuts))
        view.findViewById<View>(R.id.homeHeroCoverFrame).clipToOutline = true
        view.findViewById<View>(R.id.homeHeroCard).clipToOutline = true
        view.findViewById<View>(R.id.homeHeroBackdrop).setRenderEffect(
            RenderEffect.createBlurEffect(HERO_BLUR, HERO_BLUR, Shader.TileMode.CLAMP)
        )
        if (savedHomeRevealDone) return
        savedHomeRevealDone = true
        revealHome(view.findViewById(R.id.homeContent))
    }

    /** Fecha, saludo según la hora y el nombre con el degradado de la marca. */
    private fun bindGreeting(view: View) {
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val (greeting, subtitle) = when (hour) {
            in 6..11 -> "Buenos días," to "Empieza el día con buen ritmo."
            in 12..19 -> "Buenas tardes," to "Tu banda sonora para esta tarde."
            in 20..23 -> "Buenas noches," to "Algo especial para esta noche."
            else -> "Hola, noctámbulo," to "Para los que siguen despiertos."
        }
        val date = SimpleDateFormat("EEEE · d 'de' MMMM", Locale("es", "ES")).format(now.time)
        view.findViewById<TextView>(R.id.homeDateEyebrow).text = date.uppercase(Locale("es", "ES"))
        view.findViewById<TextView>(R.id.homeGreeting).text = greeting
        view.findViewById<TextView>(R.id.homeGreetingSubtitle).text = subtitle

        val nameView = view.findViewById<TextView>(R.id.homeGreetingName)
        val fullName = FirebaseAuth.getInstance().currentUser?.displayName
            ?: requireContext().getSharedPreferences("user_data", android.content.Context.MODE_PRIVATE).getString("name", null)
        val firstName = fullName?.trim()?.split(" ")?.firstOrNull()?.takeIf { it.isNotBlank() }
        nameView.text = firstName ?: "melómano"
        nameView.post {
            val width = nameView.paint.measureText(nameView.text.toString())
            nameView.paint.shader = LinearGradient(
                0f, 0f, width, 0f,
                intArrayOf(AuroraView.DEFAULT_A, AuroraView.DEFAULT_B, AuroraView.DEFAULT_C), null, Shader.TileMode.CLAMP
            )
            nameView.invalidate()
        }
    }

    private data class Shortcut(val label: String, val icon: Int, val destination: Int)

    /** Accesos rápidos a pantallas que no están en la barra inferior. */
    private fun buildShortcuts(row: LinearLayout) {
        row.removeAllViews()
        val iconSize = dpToPx(18)
        listOf(
            Shortcut("PlayMix", R.drawable.ic_playmix, R.id.playmixListFragment),
            Shortcut("Descargas", R.drawable.ic_download, R.id.downloadedSongsFragment),
            Shortcut("Historial", R.drawable.ic_recent, R.id.historyFragment),
            Shortcut("Estadísticas", R.drawable.ic_rs_stats, R.id.listeningStatsFragment)
        ).forEach { shortcut ->
            val chip = TextView(requireContext(), null, 0, R.style.RsShortcut).apply {
                text = shortcut.label
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(44)).apply {
                    marginEnd = dpToPx(8)
                }
                val icon = ContextCompat.getDrawable(requireContext(), shortcut.icon)?.mutate()?.apply {
                    setBounds(0, 0, iconSize, iconSize)
                    setTint(ContextCompat.getColor(requireContext(), R.color.white))
                }
                setCompoundDrawablesRelative(icon, null, null, null)
                setOnClickListener {
                    exitTransition = null
                    findNavController().navigate(shortcut.destination)
                }
            }
            row.addView(chip)
        }
    }

    /**
     * "Sigue escuchando": lo último que sonó, con su portada desenfocada de fondo. Su paleta tiñe la aurora de arriba
     * (si no hay historial, la aurora conserva los colores de la marca).
     */
    private fun bindHero(song: com.example.resonant.data.models.Song?) {
        val root = view ?: return
        val card = root.findViewById<View>(R.id.homeHeroCard)
        if (song == null) {
            card.isVisible = false
            heroSongId = null
            return
        }
        val firstTime = !card.isVisible
        card.isVisible = true
        root.findViewById<TextView>(R.id.homeHeroTitle).text = song.title
        root.findViewById<TextView>(R.id.homeHeroArtist).text =
            song.artists?.joinToString(", ") { it.name }?.takeIf { it.isNotBlank() } ?: song.artistName.orEmpty()
        val play = { historyAdapter.onItemClick?.invoke(song to null) }
        card.setOnClickListener { play() }
        root.findViewById<View>(R.id.homeHeroPlay).setOnClickListener { button ->
            button.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).withEndAction {
                button.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(OvershootInterpolator(2f)).start()
            }.start()
            play()
        }
        if (firstTime) {
            card.alpha = 0f
            card.translationY = dpToPx(20).toFloat()
            card.animate().alpha(1f).translationY(0f).setDuration(520).setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
        if (song.id == heroSongId) return
        heroSongId = song.id

        val url = song.coverUrl ?: song.album?.url
        val cover = root.findViewById<ImageView>(R.id.homeHeroCover)
        val backdrop = root.findViewById<ImageView>(R.id.homeHeroBackdrop)
        if (url.isNullOrBlank()) {
            cover.setImageResource(R.drawable.ic_disc)
            return
        }
        val model = ImageRequestHelper.buildGlideModel(requireContext(), url)
        Glide.with(cover).load(model).centerCrop().transition(DrawableTransitionOptions.withCrossFade(300)).into(cover)
        Glide.with(backdrop).load(model).centerCrop().transition(DrawableTransitionOptions.withCrossFade(500)).into(backdrop)

        heroPaletteTarget?.let { Glide.with(this).clear(it) }
        heroPaletteTarget = object : CustomTarget<Bitmap>(PALETTE_SIZE, PALETTE_SIZE) {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                Palette.from(resource).generate { palette ->
                    val p = palette ?: return@generate
                    aurora?.setPalette(
                        p.getVibrantColor(AuroraView.DEFAULT_A),
                        p.getDarkVibrantColor(p.getMutedColor(AuroraView.DEFAULT_B)),
                        p.getLightVibrantColor(p.getDominantColor(AuroraView.DEFAULT_C))
                    )
                }
            }
            override fun onLoadCleared(placeholder: Drawable?) = Unit
        }
        Glide.with(this).asBitmap().load(model).into(heroPaletteTarget!!)
    }

    /** La aurora sube a media velocidad y se apaga al bajar; deja de animarse cuando ya no se ve. */
    private fun applyHomeParallax(scrollY: Int) {
        val layer = auroraLayer ?: return
        val height = layer.height.takeIf { it > 0 } ?: return
        val progress = (scrollY / (height * 0.8f)).coerceIn(0f, 1f)
        layer.translationY = -scrollY * 0.5f
        aurora?.intensity = 1f - progress
        aurora?.setAnimating(progress < 1f && isResumed)
    }

    /** Entrada escalonada de la cabecera y las primeras secciones la primera vez que se abre Inicio. */
    private fun revealHome(content: LinearLayout) {
        val rise = dpToPx(24).toFloat()
        for (i in 0 until minOf(content.childCount, REVEAL_CHILDREN)) {
            val child = content.getChildAt(i)
            child.alpha = 0f
            child.translationY = rise
            child.animate().alpha(1f).translationY(0f)
                .setStartDelay(REVEAL_STAGGER_MS * i)
                .setDuration(REVEAL_MS)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }
    }

    override fun onDestroyView() {
        heroPaletteTarget?.let { runCatching { Glide.with(this).clear(it) } }
        heroPaletteTarget = null
        heroSongId = null
        aurora = null
        auroraLayer = null
        super.onDestroyView()
        scrollBehavior?.reset()
        scrollBehavior = null
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun updateSectionState(
        isLoading: Boolean,
        isError: Boolean,
        recyclerView: RecyclerView,
        shimmer: ShimmerFrameLayout,
        errorView: View? = null,
        errorMessageView: TextView? = null,
        message: String = ""
    ) {
        if (isLoading) {
            recyclerView.visibility = View.INVISIBLE
            errorView?.visibility = View.GONE
            shimmer.visibility = View.VISIBLE
            shimmer.startShimmer()
        } else if (isError) {
            shimmer.stopShimmer()
            shimmer.visibility = View.GONE
            recyclerView.visibility = View.GONE
            errorView?.visibility = View.VISIBLE
            errorMessageView?.text = message
        } else {
            shimmer.stopShimmer()
            shimmer.visibility = View.GONE
            errorView?.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }
}
