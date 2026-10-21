package com.example.resonant.ui.fragments

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Bundle
import android.os.StatFs
import android.text.format.Formatter
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.data.models.Song
import com.example.resonant.playback.OfflineDownloadProvider
import com.example.resonant.playback.PlaybackUrlResolver
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.adapters.HeaderViewAdapter
import com.example.resonant.ui.adapters.SongAdapter
import com.example.resonant.ui.bottomsheets.SelectPlaylistBottomSheet
import com.example.resonant.ui.bottomsheets.SongOptionsBottomSheet
import com.example.resonant.ui.components.ChartHero
import com.example.resonant.ui.components.CollectionActions
import com.example.resonant.ui.components.CollectionState
import com.example.resonant.ui.components.ResonantAccent
import com.example.resonant.ui.dialogs.ResonantDialog
import com.example.resonant.ui.viewmodels.DownloadViewModel
import com.example.resonant.ui.viewmodels.CollectionDownloadViewModel
import com.example.resonant.ui.components.DownloadedCollectionsSection
import com.example.resonant.playback.OfflineCollectionStore
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.ui.viewmodels.SongViewModel
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.example.resonant.utils.Utils
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch
import com.example.resonant.utils.SnackbarUtils.favoriteSongFeedback

@OptIn(UnstableApi::class)
class DownloadedSongsFragment : BaseFragment(R.layout.fragment_downloaded_songs) {

    private companion object {
        const val PREFS = "downloads_prefs"
        const val KEY_TIP_DISMISSED = "offline_tip_dismissed"
        const val QUEUE_ID = "DOWNLOADS_QUEUE"
    }

    private val accent = ResonantAccent.DOWNLOADS

    private lateinit var recyclerView: RecyclerView
    private lateinit var stateView: View
    private lateinit var appBarLayout: AppBarLayout
    private lateinit var hero: View

    private lateinit var songAdapter: SongAdapter
    private lateinit var headerAdapter: HeaderViewAdapter
    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var songViewModel: SongViewModel
    private lateinit var favoritesViewModel: FavoritesViewModel

    private lateinit var header: View
    private lateinit var tipCard: View
    private lateinit var storageInfoText: TextView
    private lateinit var storageFreeText: TextView
    private lateinit var storageProgress: LinearProgressIndicator
    private lateinit var deleteAllButton: View

    private lateinit var searchContainer: View
    private lateinit var searchEditText: EditText
    private lateinit var clearSearchButton: View

    // Cache de la lista completa para filtrar sin llamar a BD
    private var allDownloadedSongsList: List<Song> = emptyList()

    private var heroAnimated = false // La entrada del hero solo la primera vez, no al volver del back stack
    private var animateNextList = true // Entrada escalonada de filas en la próxima lista

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupHero(view)
        setupRecyclerView()
        setupViewModels()

        downloadViewModel.loadDownloadedSongs()
    }

    private fun initViews(view: View) {
        recyclerView = view.findViewById(R.id.recyclerView)
        stateView = view.findViewById(R.id.stateView)
        appBarLayout = view.findViewById(R.id.appBarLayout)
        hero = view.findViewById(R.id.chartHero)
        deleteAllButton = view.findViewById(R.id.deleteAllButton)

        searchContainer = view.findViewById(R.id.searchContainer)
        searchEditText = view.findViewById(R.id.searchEditText)
        clearSearchButton = view.findViewById(R.id.closeSearchButton)

        // El LayoutManager tiene que existir antes de inflar la cabecera con recyclerView
        // como padre: RecyclerView lo necesita para generar los LayoutParams.
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        header = layoutInflater.inflate(R.layout.view_downloads_header, recyclerView, false)
        tipCard = header.findViewById(R.id.tipCard)
        storageInfoText = header.findViewById(R.id.storageInfoText)
        storageFreeText = header.findViewById(R.id.storageFreeText)
        storageProgress = header.findViewById(R.id.storageProgress)
        storageProgress.setIndicatorColor(accent.color)
        header.findViewById<ImageView>(R.id.tipIcon).imageTintList = ColorStateList.valueOf(accent.color)

        setupTip()
        setupSearchLogic()
        setupAutoDownloadEntry()

        deleteAllButton.setOnClickListener { showDeleteConfirmationDialog() }
        view.findViewById<View>(R.id.btnBack).setOnClickListener { findNavController().navigateUp() }
    }

    private fun setupHero(view: View) {
        ChartHero.bind(hero, accent, "Sin conexión", "Descargas", "Tu música, también sin internet")
        ChartHero.attachParallax(appBarLayout, hero, view.findViewById(R.id.tvToolbarTitle))
        if (!heroAnimated) {
            ChartHero.animateIn(hero)
            heroAnimated = true
        }
    }

    /** Entrada a «Descargas automáticas», visible solo con `features.autoDownloads`. */
    private fun setupAutoDownloadEntry() {
        val entry = header.findViewById<View>(R.id.autoDownloadSettingsEntry)
        entry.setOnClickListener { findNavController().navigate(R.id.autoDownloadSettingsFragment) }
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                entry.isVisible = com.example.resonant.managers.CapabilitiesManager.refresh(appContext).autoDownloads
            }
        }
    }

    /** El consejo se puede cerrar y no vuelve a aparecer en siguientes visitas. */
    private fun setupTip() {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        tipCard.isVisible = !prefs.getBoolean(KEY_TIP_DISMISSED, false)
        header.findViewById<View>(R.id.closeTipButton).setOnClickListener {
            prefs.edit().putBoolean(KEY_TIP_DISMISSED, true).apply()
            tipCard.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction { tipCard.isVisible = false }
                .start()
        }
    }

    private fun setupSearchLogic() {
        searchEditText.doOnTextChanged { text, _, _, _ ->
            clearSearchButton.isVisible = !text.isNullOrEmpty()
            filterList(text?.toString().orEmpty())
        }
        searchEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
        clearSearchButton.setOnClickListener {
            searchEditText.text.clear()
            hideKeyboard()
        }
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)
        searchEditText.clearFocus()
    }

    private fun filterList(query: String) {
        val filteredList = if (query.isBlank()) {
            allDownloadedSongsList
        } else {
            val needle = query.lowercase()
            allDownloadedSongsList.filter { song ->
                song.title.lowercase().contains(needle) ||
                    song.artistName?.lowercase()?.contains(needle) == true
            }
        }
        songAdapter.submitList(filteredList)
        render(filteredList, query)
    }

    /** Cabecera, búsqueda y estados según haya descargas, resultados de búsqueda o nada. */
    private fun render(visibleSongs: List<Song>, query: String) {
        val hasDownloads = allDownloadedSongsList.isNotEmpty()
        val searching = query.isNotBlank()

        searchContainer.isVisible = hasDownloads
        deleteAllButton.isVisible = hasDownloads
        headerAdapter.isVisible = hasDownloads && !searching
        CollectionActions.setEnabled(header, visibleSongs.isNotEmpty())

        when {
            !hasDownloads -> CollectionState.show(
                stateView, accent, R.drawable.ic_download,
                title = "Aún no tienes descargas",
                subtitle = "Descarga canciones desde su menú ⋯ y escúchalas en cualquier sitio, también sin conexión",
                actionText = "Explorar música",
                onAction = {
                    requireActivity().findViewById<BottomNavigationView>(R.id.bottom_navigation)
                        ?.selectedItemId = R.id.exploreFragment
                }
            )
            visibleSongs.isEmpty() -> CollectionState.show(
                stateView, accent, R.drawable.ic_search,
                title = "Sin resultados para \"$query\"",
                subtitle = "Prueba con otro título o artista"
            )
            else -> CollectionState.hide(stateView)
        }
    }

    private fun updateStorageInfo(songs: List<Song>) {
        val context = requireContext()
        val usedBytes = songs.sumOf { it.sizeBytes }
        val freeBytes = StatFs(context.filesDir.path).availableBytes

        storageInfoText.text = Formatter.formatShortFileSize(context, usedBytes)
        storageFreeText.text = "${Formatter.formatShortFileSize(context, freeBytes)} libres en tu dispositivo"
        val total = usedBytes + freeBytes
        val progress = if (total > 0) ((usedBytes * 1000) / total).toInt() else 0
        // Siempre un mínimo visible para que la barra no parezca vacía con pocas descargas.
        storageProgress.setProgressCompat(if (usedBytes > 0) progress.coerceAtLeast(12) else 0, true)

        val count = songs.size
        val songsLabel = "$count ${if (count == 1) "canción" else "canciones"}"
        ChartHero.setMeta(
            hero,
            if (count == 0) null else "$songsLabel · ${Formatter.formatShortFileSize(context, usedBytes)}"
        )
    }

    private fun showDeleteConfirmationDialog() {
        ResonantDialog(requireContext())
            .setTitle("¿Eliminar todas las descargas?")
            .setMessage("Se borrarán de este dispositivo y dejarán de estar disponibles sin conexión. Podrás volver a descargarlas cuando quieras.")
            .setPositiveButton("Eliminar") {
                downloadViewModel.deleteAllDownloads()
            }
            .setNegativeButton("Cancelar")
            .show()
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(SongAdapter.VIEW_TYPE_FULL)
        headerAdapter = HeaderViewAdapter(header).apply { isVisible = false }
        CollectionActions.bind(
            header,
            accent,
            onPlayAll = { songAdapter.currentList.firstOrNull()?.let { playDownloaded(songAdapter.currentList, it, null) } },
            onShuffle = {
                val shuffled = songAdapter.currentList.shuffled()
                shuffled.firstOrNull()?.let { playDownloaded(shuffled, it, null) }
            }
        )
        recyclerView.adapter = ConcatAdapter(headerAdapter, songAdapter)
        setupAdapterClicks()
    }

    private fun setupViewModels() {
        songViewModel = ViewModelProvider(requireActivity())[SongViewModel::class.java]
        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]
        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]

        downloadViewModel.downloadedSongs.observe(viewLifecycleOwner) { songs ->
            // GUARDAMOS LA REFERENCIA COMPLETA
            allDownloadedSongsList = songs ?: emptyList()
            updateStorageInfo(allDownloadedSongsList)

            // Si hay una búsqueda activa se reaplica el filtro sobre la lista nueva
            val query = searchEditText.text?.toString().orEmpty()
            filterList(query)

            if (animateNextList && allDownloadedSongsList.isNotEmpty()) {
                animateNextList = false
                recyclerView.scheduleLayoutAnimation()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                downloadViewModel.downloadedSongIds.collect { ids ->
                    songAdapter.downloadedSongIds = ids
                }
            }
        }

        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { current ->
            current?.let { songAdapter.setCurrentPlayingSong(it.id) }
        }

        // Playlists, Playmix y Álbumes descargados (collection-queue-offline).
        val collections = ViewModelProvider(requireActivity())[CollectionDownloadViewModel::class.java]
        val section = DownloadedCollectionsSection(header.findViewById(R.id.downloadedCollectionsContainer)) { item ->
            openDownloadedCollection(item.summary.collectionType, item.summary.collectionId)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                collections.downloadedCollections().collect { section.bind(it) }
            }
        }

        favoritesViewModel.loadFavoriteSongs()
        favoritesViewModel.favoriteSongIds.observe(viewLifecycleOwner) { songIds ->
            songAdapter.favoriteSongIds = songIds
            songAdapter.notifyDataSetChanged()
        }
    }

    /** Abre una colección descargada; sin conexión, su pantalla muestra lo guardado. */
    private fun openDownloadedCollection(type: String, id: String) {
        val nav = findNavController()
        runCatching {
            when (type) {
                OfflineCollectionStore.TYPE_PLAYLIST ->
                    nav.navigate(R.id.playlistFragment, Bundle().apply { putString("playlistId", id) })
                OfflineCollectionStore.TYPE_PLAYMIX ->
                    nav.navigate(R.id.playmixDetailFragment, Bundle().apply { putString("playmixId", id) })
                OfflineCollectionStore.TYPE_ALBUM ->
                    nav.navigate(R.id.albumFragment, Bundle().apply { putString("albumId", id) })
            }
        }
    }

    /** Comprueba que el archivo de [song] sigue en el dispositivo y reproduce [songs] desde ella. */
    private fun playDownloaded(songs: List<Song>, song: Song, bitmap: Bitmap?) {
        val path = song.url
        if (path.isNullOrEmpty()) {
            showResonantSnackbar("Error: Ruta de archivo vacía", R.color.errorColor, R.drawable.ic_error)
            return
        }
        val isStableUri = path.startsWith(
            "${PlaybackUrlResolver.STABLE_SCHEME}://" +
                "${PlaybackUrlResolver.STABLE_AUTHORITY}/",
            ignoreCase = true
        )
        val exists = if (isStableUri) {
            OfflineDownloadProvider
                .getDownloadManager(requireContext())
                .downloadIndex
                .getDownload(song.id)
                ?.state == Download.STATE_COMPLETED
        } else {
            val file = java.io.File(path)
            file.exists() && file.length() > 0L
        }
        if (!exists) {
            showResonantSnackbar("Error: Archivo dañado o no existe", R.color.errorColor, R.drawable.ic_error)
            return
        }

        // Al reproducir desde una búsqueda se pasa la lista FILTRADA
        val currentIndex = songs.indexOfFirst { it.id == song.id }
        val songList = ArrayList(songs)

        viewLifecycleOwner.lifecycleScope.launch {
            val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), it, song.id) }

            val playIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_PLAY
                putExtra(MusicPlaybackService.EXTRA_CURRENT_SONG, song)
                putExtra(MusicPlaybackService.EXTRA_CURRENT_INDEX, currentIndex)
                putExtra(MusicPlaybackService.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, songList)
                putExtra(
                    MusicPlaybackService.EXTRA_QUEUE_SOURCE,
                    QueueSource.DOWNLOADED_SONGS
                )
                putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE_ID, QUEUE_ID)
            }
            try {
                requireContext().startService(playIntent)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun setupAdapterClicks() {
        songAdapter.onItemClick = { (song, bitmap) ->
            playDownloaded(songAdapter.currentList, song, bitmap)
        }
        songAdapter.onFavoriteClick = { song, _ -> favoritesViewModel.toggleFavoriteSong(song, favoriteSongFeedback(song)) }
        songAdapter.onSettingsClick = { song ->
            if (song.artistName.isNullOrEmpty()) song.artistName = "Artista desconocido"
            val bottomSheet = SongOptionsBottomSheet(
                song = song,
                onSeeSongClick = { findNavController().navigate(R.id.action_global_to_detailedSongFragment, Bundle().apply{putParcelable("song", it)}) },
                onFavoriteToggled = { favoritesViewModel.toggleFavoriteSong(it, favoriteSongFeedback(it)) },
                onAddToPlaylistClick = { songToAdd ->
                    val sheet = SelectPlaylistBottomSheet(
                        song = songToAdd,
                        onNoPlaylistsFound = { findNavController().navigate(R.id.action_global_to_createPlaylistFragment) }
                    )
                    sheet.show(parentFragmentManager, "SelectPlaylistBottomSheet")
                },
                onDownloadClick = { songToDownload ->
                    downloadViewModel.downloadSong(songToDownload)
                    showResonantSnackbar(
                        text = "Iniciando descarga...",
                        colorRes = R.color.successColor,
                        iconRes = R.drawable.ic_download
                    )
                },
                onRemoveDownloadClick = { songToDelete ->
                    // El resultado lo muestra MainActivity (downloadEvent).
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
