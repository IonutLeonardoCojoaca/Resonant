package com.example.resonant.ui.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.data.models.Playlist
import com.example.resonant.managers.PlaylistManager
import com.example.resonant.managers.UserManager
import com.example.resonant.ui.adapters.PlaylistAdapter
import com.example.resonant.ui.bottomsheets.PlaylistOptionsBottomSheet
import com.example.resonant.ui.viewmodels.PlaylistsListViewModel
import com.example.resonant.ui.viewmodels.PlaylistsListViewModelFactory
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.example.resonant.utils.Utils
import com.google.android.material.button.MaterialButton

class SavedFragment : BaseFragment(R.layout.fragment_saved) {

    private lateinit var songsButton: MaterialButton
    private lateinit var artistsButton: MaterialButton
    private lateinit var albumsButton: MaterialButton
    private lateinit var emptyTextView: TextView
    private lateinit var playlistRecyclerView: RecyclerView
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var userProfileImage: ImageView

    private val playlistsListViewModel: PlaylistsListViewModel by viewModels {
        val playlistManager = PlaylistManager(requireContext())
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
            Log.d("SavedFragment", "Playlists recibidas: ${playlists?.size ?: 0}")
            playlistAdapter.submitList(playlists ?: emptyList())
            updateEmptyView(playlists)
        })

        val navController = findNavController()
        navController.currentBackStackEntry?.savedStateHandle?.getLiveData<String>("PLAYLIST_UPDATED_ID")
            ?.observe(viewLifecycleOwner) { playlistId ->
                if (playlistId != null) {
                    Log.d("SavedFragment", "Refrescando playlist ID: $playlistId")

                    playlistAdapter.clearCacheForPlaylist(playlistId)
                    forceReloadPlaylists()
                    navController.currentBackStackEntry?.savedStateHandle?.remove<String>("PLAYLIST_UPDATED_ID")
                }
            }

        reloadPlaylistsInitial()
    }

    private fun showPlaylistOptions(playlist: Playlist) {
        val bottomSheet = PlaylistOptionsBottomSheet(
            playlist = playlist,
            playlistImageBitmap = null,
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

    private fun initViews(view: View) {
        songsButton = view.findViewById(R.id.songsButton)
        artistsButton = view.findViewById(R.id.artistsButton)
        albumsButton = view.findViewById(R.id.albumsButton)
        playlistRecyclerView = view.findViewById(R.id.playlistList)
        emptyTextView = view.findViewById(R.id.noPlaylistText)
        userProfileImage = view.findViewById(R.id.userProfile)
        Utils.loadUserProfile(requireContext(), userProfileImage)
    }

    private fun setupRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            viewType = PlaylistAdapter.Companion.VIEW_TYPE_GRID,

            // 1. Navegación al hacer Click en la tarjeta
            onClick = { playlist ->
                val bundle = Bundle().apply { putString("playlistId", playlist.id) }
                findNavController().navigate(R.id.action_savedFragment_to_playlistFragment, bundle)
            },

            // 2. Long Click (Mantenemos la lógica, pero llamando a la función extraída)
            onPlaylistLongClick = { playlist, _ ->
                showPlaylistOptions(playlist)
            },

            // 3. NUEVO: Click en el botón de ajustes (ImageButton)
            onSettingsClick = { playlist ->
                showPlaylistOptions(playlist)
            }
        )

        playlistRecyclerView.adapter = playlistAdapter
        playlistRecyclerView.layoutManager = LinearLayoutManager(context)
    }

    private fun reloadPlaylistsInitial() {
        if (playlistsListViewModel.playlists.value.isNullOrEmpty()) {
            forceReloadPlaylists()
        }
    }

    private fun forceReloadPlaylists() {
        val userManager = UserManager(requireContext())
        val userId = userManager.getUserId()

        if (userId != null) {
            playlistsListViewModel.getPlaylistsByUserId(userId)
        } else {
            updateEmptyView(null)
        }
    }

    private fun updateEmptyView(playlists: List<Playlist>?) {
        val userManager = UserManager(requireContext())
        val userId = userManager.getUserId()

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
            findNavController().navigate(R.id.action_savedFragment_to_favoriteSongsFragment)
        }
        artistsButton.setOnClickListener {
            findNavController().navigate(R.id.action_savedFragment_to_favoriteArtistsFragment)
        }
        albumsButton.setOnClickListener {
            findNavController().navigate(R.id.action_savedFragment_to_favoriteAlbumsFragment)
        }
    }
}