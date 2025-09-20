package com.example.resonant

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.SnackbarUtils.showResonantSnackbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class SavedFragment : BaseFragment(R.layout.fragment_saved) {

    private lateinit var songsButton: MaterialButton
    private lateinit var artistsButton: MaterialButton
    private lateinit var albumsButton: MaterialButton
    private lateinit var createPlaylistButton: MaterialButton
    private lateinit var emptyTextView: TextView
    private lateinit var playlistRecyclerView: RecyclerView
    private lateinit var playlistAdapter: PlaylistAdapter

    private var playlists: List<Playlist> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_saved, container, false)

        songsButton = view.findViewById(R.id.songsButton)
        artistsButton = view.findViewById(R.id.artistsButton)
        albumsButton = view.findViewById(R.id.albumsButton)
        createPlaylistButton = view.findViewById(R.id.createPlaylistButton)
        playlistRecyclerView = view.findViewById(R.id.playlistList)
        emptyTextView = view.findViewById(R.id.noPlaylistText)

        // SOLO UNA VEZ: crea el adapter con el long click
        playlistAdapter = PlaylistAdapter(
            PlaylistAdapter.VIEW_TYPE_GRID,
            onClick = null,
            onPlaylistLongClick = { playlist, bitmap ->
                val bottomSheet = PlaylistOptionsBottomSheet(
                    playlist = playlist,
                    playlistImageBitmap = bitmap,
                    onDeleteClick = { playlistToDelete ->
                        val service = ApiClient.getService(requireContext())
                        val playlistManager = PlaylistManager(service)
                        lifecycleScope.launch {
                            lifecycleScope.launch {
                                try {
                                    playlistManager.deletePlaylist(playlistToDelete.id!!)
                                    showResonantSnackbar(
                                        text = "Se ha borrado la lista correctamente",
                                        colorRes = R.color.successColor,
                                        iconRes = R.drawable.success
                                    )
                                    reloadPlaylists()
                                } catch (e: Exception) {
                                    Log.i("Error playlist", e.toString())
                                    showResonantSnackbar(
                                        text = "Ha habido un error al borrar",
                                        colorRes = R.color.errorColor,
                                        iconRes = R.drawable.error
                                    )
                                }
                            }
                        }
                    }
                )
                bottomSheet.show(childFragmentManager, bottomSheet.tag)
            }
        )

        playlistRecyclerView.adapter = playlistAdapter
        playlistRecyclerView.layoutManager = GridLayoutManager(requireContext(), 3)

        songsButton.setOnClickListener {
            songsButton.postDelayed({
                val action = SavedFragmentDirections.actionSavedFragmentToFavoriteSongsFragment()
                findNavController().navigate(action)
            }, 200)
        }

        createPlaylistButton.setOnClickListener {
            createPlaylistButton.postDelayed({
                val action = SavedFragmentDirections.actionSavedFragmentToCreatePlaylistFragment()
                findNavController().navigate(action)
            }, 200)
        }

        reloadPlaylists()

        return view
    }

    private fun reloadPlaylists() {
        val userId = UserManager.getUserId(requireContext())
        val service = ApiClient.getService(requireContext())
        if (userId != null) {
            val playlistManager = PlaylistManager(service)
            lifecycleScope.launch {
                try {
                    val playlists = playlistManager.getPlaylistByUserId(userId)
                    playlistAdapter.updateData(playlists)
                    if (playlists.isEmpty()) {
                        emptyTextView.visibility = View.VISIBLE
                        playlistRecyclerView.visibility = View.GONE
                    } else {
                        emptyTextView.visibility = View.GONE
                        playlistRecyclerView.visibility = View.VISIBLE
                    }
                } catch (e: Exception) {
                    Log.e("SavedFragment", "Error: ${e.message}")
                    emptyTextView.text = "Error al cargar las playlists"
                    emptyTextView.visibility = View.VISIBLE
                    playlistRecyclerView.visibility = View.GONE
                }
            }
        } else {
            emptyTextView.text = "No tienes ninguna playlist guardada"
            emptyTextView.visibility = View.VISIBLE
            playlistRecyclerView.visibility = View.GONE
        }
    }
}