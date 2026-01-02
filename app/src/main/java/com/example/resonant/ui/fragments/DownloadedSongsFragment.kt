package com.example.resonant.ui.fragments

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.data.models.Song
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.adapters.SongAdapter
import com.example.resonant.ui.bottomsheets.SelectPlaylistBottomSheet
import com.example.resonant.ui.bottomsheets.SongOptionsBottomSheet
import com.example.resonant.ui.dialogs.ResonantDialog
import com.example.resonant.ui.viewmodels.DownloadViewModel
import com.example.resonant.ui.viewmodels.FavoritesViewModel
import com.example.resonant.ui.viewmodels.SongViewModel
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.example.resonant.utils.Utils
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch
import java.util.Locale

class DownloadedSongsFragment : BaseFragment(R.layout.fragment_downloaded_songs) {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout

    private lateinit var songAdapter: SongAdapter
    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var songViewModel: SongViewModel
    private lateinit var favoritesViewModel: FavoritesViewModel

    // 🔥 IMPORTANTE: lateinit promete que se inicializará antes de usarse.
    private lateinit var userProfileImage: ImageView

    private lateinit var tipCard: MaterialCardView
    private lateinit var closeTipButton: ImageView
    private lateinit var deleteAllButton: ImageView
    private lateinit var storageInfoText: TextView

    // --- NUEVAS VARIABLES PARA BÚSQUEDA ---
    private lateinit var searchButton: ImageView
    private lateinit var searchContainer: MaterialCardView
    private lateinit var searchEditText: EditText
    private lateinit var closeSearchButton: ImageView

    // Cache de la lista completa para filtrar sin llamar a BD
    private var allDownloadedSongsList: List<Song> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupRecyclerView()
        setupViewModels()

        // Si tienes la función loadUserProfile en Utils, descomenta esto:
        Utils.loadUserProfile(requireContext(), userProfileImage)

        downloadViewModel.loadDownloadedSongs()
    }

    private fun initViews(view: View) {
        recyclerView = view.findViewById(R.id.recyclerView)
        emptyState = view.findViewById(R.id.emptyState)

        // Si tienes userProfile en tu XML, inicialízalo. Si lo borraste, quita esta línea.
        userProfileImage = view.findViewById(R.id.userProfile)

        tipCard = view.findViewById(R.id.tipCard)
        closeTipButton = view.findViewById(R.id.closeTipButton)
        deleteAllButton = view.findViewById(R.id.deleteAllButton)
        storageInfoText = view.findViewById(R.id.storageInfoText)

        // --- INICIALIZAR BÚSQUEDA ---
        searchButton = view.findViewById(R.id.searchButton)
        searchContainer = view.findViewById(R.id.searchContainer)
        searchEditText = view.findViewById(R.id.searchEditText)
        closeSearchButton = view.findViewById(R.id.closeSearchButton)

        setupSearchLogic()
        // ---------------------------

        closeTipButton.setOnClickListener {
            tipCard.animate()
                .alpha(0f)
                .translationY(-50f)
                .setDuration(300)
                .withEndAction { tipCard.visibility = View.GONE }
                .start()
        }

        deleteAllButton.setOnClickListener {
            showDeleteConfirmationDialog()
        }
    }

    private fun setupSearchLogic() {
        // ABRIR BÚSQUEDA
        searchButton.setOnClickListener {
            searchContainer.visibility = View.VISIBLE
            searchContainer.alpha = 0f
            searchContainer.translationY = -20f
            searchContainer.animate().alpha(1f).translationY(0f).setDuration(200).start()

            // Foco y teclado
            searchEditText.requestFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(searchEditText, InputMethodManager.SHOW_IMPLICIT)
        }

        // CERRAR BÚSQUEDA
        closeSearchButton.setOnClickListener {
            closeSearch()
        }

        // FILTRADO EN TIEMPO REAL
        searchEditText.doOnTextChanged { text, _, _, _ ->
            filterList(text.toString())
        }
    }

    private fun closeSearch() {
        // Ocultar teclado
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)

        // Limpiar texto y animación de salida
        searchEditText.text.clear()
        searchContainer.animate()
            .alpha(0f)
            .translationY(-20f)
            .setDuration(200)
            .withEndAction { searchContainer.visibility = View.GONE }
            .start()

        // Restaurar lista completa
        songAdapter.submitList(allDownloadedSongsList)
    }

    private fun filterList(query: String) {
        if (query.isBlank()) {
            songAdapter.submitList(allDownloadedSongsList)
            return
        }

        val filteredList = allDownloadedSongsList.filter { song ->
            val titleMatch = song.title.lowercase().contains(query.lowercase())
            val artistMatch = song.artistName?.lowercase()?.contains(query.lowercase()) == true
            titleMatch || artistMatch
        }

        songAdapter.submitList(filteredList)
    }

    private fun updateStorageInfo(songs: List<Song>) {
        val totalBytes = songs.sumOf { it.sizeBytes }

        if (totalBytes == 0L) {
            storageInfoText.visibility = View.GONE
            return
        }

        val sizeFormatted = if (totalBytes > 1024 * 1024 * 1024) {
            val gb = totalBytes / (1024.0 * 1024.0 * 1024.0)
            String.format(Locale.US, "Tus canciones ocupan %.2f GB.", gb)
        } else {
            val mb = totalBytes / (1024.0 * 1024.0)
            String.format(Locale.US, "Tus canciones ocupan %.1f MB.", mb)
        }

        storageInfoText.text = sizeFormatted
        storageInfoText.visibility = View.VISIBLE
    }

    private fun showDeleteConfirmationDialog() {
        ResonantDialog(requireContext())
            .setTitle("¿Eliminar todo?")
            .setMessage("Se eliminarán todas las canciones descargadas de tu dispositivo.")
            .setPositiveButton("Eliminar") {
                downloadViewModel.deleteAllDownloads()
            }
            .setNegativeButton("Cancelar")
            .show()
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(SongAdapter.VIEW_TYPE_FULL)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = songAdapter
        setupAdapterClicks()
    }

    private fun setupViewModels() {
        songViewModel = ViewModelProvider(requireActivity())[SongViewModel::class.java]
        favoritesViewModel = ViewModelProvider(requireActivity())[FavoritesViewModel::class.java]
        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]

        downloadViewModel.downloadedSongs.observe(viewLifecycleOwner) { songs ->
            // GUARDAMOS LA REFERENCIA COMPLETA
            allDownloadedSongsList = songs ?: emptyList()

            if (songs.isNullOrEmpty()) {
                recyclerView.visibility = View.GONE
                emptyState.visibility = View.VISIBLE
                deleteAllButton.visibility = View.GONE
                searchButton.visibility = View.GONE // Ocultar lupa si no hay canciones
                storageInfoText.visibility = View.GONE
            } else {
                recyclerView.visibility = View.VISIBLE
                emptyState.visibility = View.GONE
                deleteAllButton.visibility = View.VISIBLE
                searchButton.visibility = View.VISIBLE // Mostrar lupa

                // Si hay una búsqueda activa, reaplicamos el filtro, si no, mostramos todo
                if (searchContainer.visibility == View.VISIBLE && searchEditText.text.isNotEmpty()) {
                    filterList(searchEditText.text.toString())
                } else {
                    songAdapter.submitList(songs)
                }

                updateStorageInfo(songs)
            }
        }

        // ... (Resto de observers igual que antes) ...
        lifecycleScope.launch {
            downloadViewModel.downloadedSongIds.collect { ids ->
                songAdapter.downloadedSongIds = ids
                songAdapter.notifyDataSetChanged()
            }
        }

        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { current ->
            current?.let { songAdapter.setCurrentPlayingSong(it.id) }
        }

        favoritesViewModel.loadFavoriteSongs()
        favoritesViewModel.favoriteSongIds.observe(viewLifecycleOwner) { songIds ->
            songAdapter.favoriteSongIds = songIds
            songAdapter.notifyDataSetChanged()
        }
    }

    // Asegurarse de cerrar la búsqueda si el usuario pulsa atrás
    // (Opcional, requiere gestionar el OnBackPressedDispatcher)

    private fun setupAdapterClicks() {
        // ... (Tu código de clicks existente, mantenlo igual) ...
        val queueId = "DOWNLOADS_QUEUE"
        songAdapter.onItemClick = { (song, bitmap) ->
            val path = song.url
            var isValid = true
            if (path.isNullOrEmpty()) {
                showResonantSnackbar("Error: Ruta de archivo vacía", R.color.errorColor, R.drawable.ic_error)
                isValid = false
            } else {
                val file = java.io.File(path)
                if (!file.exists() || file.length() == 0L) {
                    showResonantSnackbar("Error: Archivo dañado o no existe", R.color.errorColor, R.drawable.ic_error)
                    isValid = false
                }
            }

            if (isValid) {
                // Nota importante: Al reproducir desde una búsqueda,
                // pasamos la lista FILTRADA (songAdapter.currentList)
                val currentIndex = songAdapter.currentList.indexOfFirst { it.id == song.id }
                val bitmapPath = bitmap?.let { Utils.saveBitmapToCache(requireContext(), it, song.id) }
                val songList = ArrayList(songAdapter.currentList)

                val playIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                    action = MusicPlaybackService.ACTION_PLAY
                    putExtra(MusicPlaybackService.EXTRA_CURRENT_SONG, song)
                    putExtra(MusicPlaybackService.EXTRA_CURRENT_INDEX, currentIndex)
                    putExtra(MusicPlaybackService.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                    putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, songList)
                    putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE, QueueSource.PLAYLIST)
                    putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE_ID, queueId)
                }
                try {
                    requireContext().startService(playIntent)
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
        // ... (Resto de listeners settings/favorites) ...
        songAdapter.onFavoriteClick = { song, _ -> favoritesViewModel.toggleFavoriteSong(song) }
        songAdapter.onSettingsClick = { song ->
            // Tu lógica de settings...
            if (song.artistName.isNullOrEmpty()) song.artistName = "Artista desconocido"
            val bottomSheet = SongOptionsBottomSheet(
                song = song,
                onSeeSongClick = { findNavController().navigate(R.id.action_global_to_detailedSongFragment, Bundle().apply{putParcelable("song", it)}) },
                onFavoriteToggled = { favoritesViewModel.toggleFavoriteSong(it) },
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
                    downloadViewModel.deleteSong(songToDelete)
                    showResonantSnackbar(
                        text = "Descarga eliminada",
                        colorRes = R.color.successColor,
                        iconRes = R.drawable.ic_success
                    )
                }
            )
            bottomSheet.show(parentFragmentManager, "SongOptionsBottomSheet")
        }
    }
}