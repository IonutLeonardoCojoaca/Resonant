package com.example.resonant.ui.fragments

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
import com.example.resonant.ui.bottomsheets.PlaylistOptionsBottomSheet
import com.example.resonant.ui.viewmodels.PlaylistsListViewModel
import com.example.resonant.ui.viewmodels.PlaylistsListViewModelFactory
import com.example.resonant.R
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.example.resonant.managers.UserManager
import com.example.resonant.data.models.Playlist
import com.example.resonant.data.network.ApiClient
import com.example.resonant.managers.PlaylistManager
import com.example.resonant.ui.adapters.PlaylistAdapter
import com.google.android.material.button.MaterialButton

class SavedFragment : BaseFragment(R.layout.fragment_saved) {

    private lateinit var songsButton: MaterialButton
    private lateinit var artistsButton: MaterialButton
    private lateinit var albumsButton: MaterialButton
    private lateinit var createPlaylistButton: MaterialButton
    private lateinit var emptyTextView: TextView
    private lateinit var playlistRecyclerView: RecyclerView
    private lateinit var playlistAdapter: PlaylistAdapter

    private val playlistsListViewModel: PlaylistsListViewModel by viewModels {
        val service = ApiClient.getService(requireContext())
        val playlistManager = PlaylistManager(service)
        PlaylistsListViewModelFactory(playlistManager)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_saved, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupRecyclerView()
        setupClickListeners()

        playlistsListViewModel.playlists.observe(viewLifecycleOwner, Observer { playlists ->
            Log.d(
                "SavedFragment",
                "Observer de playlists ha recibido ${playlists?.size ?: 0} elementos."
            )
            playlistAdapter.submitList(playlists ?: emptyList())
            updateEmptyView(playlists)
        })

        val navController = findNavController()
        navController.currentBackStackEntry?.savedStateHandle?.getLiveData<String>("PLAYLIST_UPDATED_ID")
            ?.observe(viewLifecycleOwner) { playlistId ->
                if (playlistId != null) {
                    Log.d("SavedFragment", "Señal de refresco recibida para la playlist ID: $playlistId")

                    playlistAdapter.clearCacheForPlaylist(playlistId)

                    forceReloadPlaylists()

                    navController.currentBackStackEntry?.savedStateHandle?.remove<String>("PLAYLIST_UPDATED_ID")
                }
            }

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
            PlaylistAdapter.Companion.VIEW_TYPE_GRID,
            onClick = null,
            onPlaylistLongClick = { playlist, bitmap ->
                val bottomSheet = PlaylistOptionsBottomSheet(
                    playlist = playlist,
                    playlistImageBitmap = bitmap,
                    onDeleteClick = { playlistToDelete ->
                        playlistsListViewModel.deletePlaylist(playlistToDelete.id!!)

                        showResonantSnackbar(
                            text = "Se ha borrado la lista correctamente",
                            colorRes = R.color.successColor,
                            iconRes = R.drawable.ic_success
                        )
                    }
                )
                bottomSheet.show(childFragmentManager, bottomSheet.tag)
            }
        )
        playlistRecyclerView.adapter = playlistAdapter
        playlistRecyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
    }

    private fun reloadPlaylistsInitial() {
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