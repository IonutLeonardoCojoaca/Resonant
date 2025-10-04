package com.example.resonant

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.SnackbarUtils.showResonantSnackbar
import com.google.android.material.button.MaterialButton

class SavedFragment : BaseFragment(R.layout.fragment_saved) {

    // --- Vistas ---
    private lateinit var songsButton: MaterialButton
    private lateinit var artistsButton: MaterialButton
    private lateinit var albumsButton: MaterialButton
    private lateinit var createPlaylistButton: MaterialButton
    private lateinit var emptyTextView: TextView
    private lateinit var playlistRecyclerView: RecyclerView
    private lateinit var playlistAdapter: PlaylistAdapter

    // --- ViewModel ---
    // ✅ Pide la instancia del ViewModel correcto: PlaylistsListViewModel
    private val playlistsListViewModel: PlaylistsListViewModel by viewModels {
        val service = ApiClient.getService(requireContext())
        val playlistManager = PlaylistManager(service)
        PlaylistsListViewModelFactory(playlistManager)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // En onCreateView, solo inflamos la vista. No hacemos nada más.
        return inflater.inflate(R.layout.fragment_saved, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. INICIALIZACIÓN: Todas las inicializaciones ocurren aquí.
        initViews(view)
        setupRecyclerView()
        setupClickListeners()

        // 2. OBSERVADOR PRINCIPAL: Observa la lista de playlists.
        playlistsListViewModel.playlists.observe(viewLifecycleOwner, Observer { playlists ->
            Log.d("SavedFragment", "Observer de playlists ha recibido ${playlists?.size ?: 0} elementos.")
            playlistAdapter.submitList(playlists ?: emptyList())
            updateEmptyView(playlists)
        })

        // 3. OBSERVADOR DE RESULTADO: Escucha la señal del fragment de detalle.
        // Lo ponemos aquí, en onViewCreated, para asegurar que playlistAdapter está inicializado.
        val navController = findNavController()
        navController.currentBackStackEntry?.savedStateHandle?.getLiveData<String>("PLAYLIST_UPDATED_ID")
            ?.observe(viewLifecycleOwner) { playlistId ->
                if (playlistId != null) {
                    Log.d("SavedFragment", "Señal de refresco recibida para la playlist ID: $playlistId")

                    // A. Invalidamos la caché del adapter para esa playlist específica
                    playlistAdapter.clearCacheForPlaylist(playlistId)

                    // B. Recargamos los datos para obtener la info actualizada (contador, etc.)
                    forceReloadPlaylists()

                    // C. Limpiamos la señal para que no se vuelva a ejecutar
                    navController.currentBackStackEntry?.savedStateHandle?.remove<String>("PLAYLIST_UPDATED_ID")
                }
            }

        // 4. ELIMINAMOS EL OBSERVADOR ANTIGUO: El listener para "NEEDS_REFRESH" ya no es necesario.

        // 5. CARGA INICIAL: Llama a la lógica de carga inicial.
        reloadPlaylistsInitial()
    }

    private fun initViews(view: View) {
        songsButton = view.findViewById(R.id.songsButton)
        artistsButton = view.findViewById(R.id.artistsButton)
        albumsButton = view.findViewById(R.id.albumsButton)
        createPlaylistButton = view.findViewById(R.id.createPlaylistButton)
        playlistRecyclerView = view.findViewById(R.id.playlistList)
        emptyTextView = view.findViewById(R.id.noPlaylistText)
    }

    private fun setupRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            PlaylistAdapter.VIEW_TYPE_GRID,
            onClick = null,
            onPlaylistLongClick = { playlist, bitmap ->
                val bottomSheet = PlaylistOptionsBottomSheet(
                    playlist = playlist,
                    playlistImageBitmap = bitmap,
                    onDeleteClick = { playlistToDelete ->
                        // ✅ Llama al método del ViewModel correcto
                        playlistsListViewModel.deletePlaylist(playlistToDelete.id!!)
                        showResonantSnackbar(
                            text = "Se ha borrado la lista correctamente",
                            colorRes = R.color.successColor,
                            iconRes = R.drawable.success
                        )
                        forceReloadPlaylists()
                    }
                )
                bottomSheet.show(childFragmentManager, bottomSheet.tag)
            }
        )
        playlistRecyclerView.adapter = playlistAdapter
        playlistRecyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
    }

    private fun reloadPlaylistsInitial() {
        // ✅ Comprueba el LiveData del ViewModel correcto
        if (playlistsListViewModel.playlists.value.isNullOrEmpty()) {
            Log.d("SavedFragment", "ViewModel vacío. Realizando carga inicial de playlists.")
            forceReloadPlaylists()
        } else {
            Log.d("SavedFragment", "ViewModel ya tiene datos. Carga inicial omitida.")
        }
    }

    private fun forceReloadPlaylists() {
        Log.d("SavedFragment", "Forzando recarga de playlists desde la red.")
        val userId = UserManager.getUserId(requireContext())
        if (userId != null) {
            // ✅ Llama al método del ViewModel correcto
            playlistsListViewModel.getPlaylistsByUserId(userId)
        } else {
            updateEmptyView(null)
        }
    }

    private fun updateEmptyView(playlists: List<Playlist>?) {
        val userId = UserManager.getUserId(requireContext())
        if (userId == null) {
            emptyTextView.text = "No tienes ninguna playlist guardada"
            emptyTextView.visibility = View.VISIBLE
            playlistRecyclerView.visibility = View.GONE
        } else if (playlists.isNullOrEmpty()) {
            emptyTextView.text = "Aún no tienes ninguna playlist"
            emptyTextView.visibility = View.VISIBLE
            playlistRecyclerView.visibility = View.GONE
        } else {
            emptyTextView.visibility = View.GONE
            playlistRecyclerView.visibility = View.VISIBLE
        }
    }

    private fun setupClickListeners() {
        songsButton.setOnClickListener {
            findNavController().navigate(SavedFragmentDirections.actionSavedFragmentToFavoriteSongsFragment())
        }
        artistsButton.setOnClickListener {
            findNavController().navigate(SavedFragmentDirections.actionSavedFragmentToFavoriteArtistsFragment())
        }
        albumsButton.setOnClickListener {
            findNavController().navigate(SavedFragmentDirections.actionSavedFragmentToFavoriteAlbumsFragment())
        }
        createPlaylistButton.setOnClickListener {
            findNavController().navigate(SavedFragmentDirections.actionSavedFragmentToCreatePlaylistFragment())
        }
    }

}