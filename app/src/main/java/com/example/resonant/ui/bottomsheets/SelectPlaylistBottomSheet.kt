package com.example.resonant.ui.bottomsheets

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.example.resonant.data.models.Song
// 1. Borramos ApiClient, ya no hace falta aquí
import com.example.resonant.managers.PlaylistManager
import com.example.resonant.ui.adapters.PlaylistAdapter
import com.example.resonant.ui.viewmodels.PlaylistDetailViewModel
import com.example.resonant.ui.viewmodels.PlaylistDetailViewModelFactory
import com.example.resonant.ui.viewmodels.PlaylistsListViewModel
import com.example.resonant.ui.viewmodels.PlaylistsListViewModelFactory
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.launch

class SelectPlaylistBottomSheet(
    private val song: Song,
    private val onNoPlaylistsFound: () -> Unit
) : BottomSheetDialogFragment() {

    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var playlistRecyclerView: RecyclerView
    private lateinit var noPlaylistTextView: TextView
    private lateinit var selectPlaylistText: TextView

    // ✅ 1. CORREGIDO: Inicialización de PlaylistManager pasando solo el Context
    private val playlistsListViewModel: PlaylistsListViewModel by viewModels {
        val playlistManager = PlaylistManager(requireContext()) // <--- CAMBIO
        PlaylistsListViewModelFactory(playlistManager)
    }

    private val playlistDetailViewModel: PlaylistDetailViewModel by viewModels {
        val playlistManager = PlaylistManager(requireContext()) // <--- CAMBIO
        PlaylistDetailViewModelFactory(playlistManager)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.bottom_sheet_playlists_selector, container, false)

        val songImage: ShapeableImageView = view.findViewById(R.id.songImage)
        val songTitle: TextView = view.findViewById(R.id.songTitle)
        val songArtist: TextView = view.findViewById(R.id.songArtist)
        val songStreams: TextView = view.findViewById(R.id.songStreams)

        noPlaylistTextView = view.findViewById(R.id.noPlaylistTextView)
        selectPlaylistText = view.findViewById(R.id.selectPlaylistText)
        playlistRecyclerView = view.findViewById(R.id.playlistList)

        songTitle.text = song.title
        songArtist.text = song.artistName

        if(song.streams == 0){
            songStreams.text = "Sin reproducciones"
        }else if (song.streams == 1){
            songStreams.text = "${song.streams} reproducción"
        }else{
            songStreams.text = "${song.streams} reproducciones"
        }

        Glide.with(songImage).load(song.coverUrl ?: song.imageFileName).placeholder(R.drawable.ic_disc).into(songImage)

        setupRecyclerView()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        playlistsListViewModel.playlists.observe(viewLifecycleOwner) { playlists ->
            playlistAdapter.submitList(playlists ?: emptyList())
            updateEmptyState(playlists.isEmpty())
        }

        // No userId needed, JWT handles authentication
        playlistsListViewModel.loadMyPlaylists()
    }

    private fun setupRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            PlaylistAdapter.Companion.VIEW_TYPE_LIST,
            onClick = { selectedPlaylist ->
                lifecycleScope.launch {
                    try {
                        val alreadyInPlaylist = playlistDetailViewModel.checkSongInPlaylist(
                            song.id,
                            selectedPlaylist.id!!
                        )
                        if (alreadyInPlaylist) {
                            showResonantSnackbar(
                                text = "La canción ya está en la lista",
                                colorRes = R.color.adviseColor,
                                iconRes = R.drawable.ic_information
                            )
                        } else {
                            // Nota: requireContext() aquí es redundante si el método
                            // addSongToPlaylist ya no lo pide (depende de cómo dejaste el VM),
                            // pero si lo pide, está bien dejarlo.
                            playlistDetailViewModel.addSongToPlaylist(
                                song.id,
                                selectedPlaylist.id!!
                            )
                            showResonantSnackbar(
                                text = "¡Canción añadida a '${selectedPlaylist.name}'!",
                                colorRes = R.color.successColor,
                                iconRes = R.drawable.ic_success
                            )

                            playlistAdapter.clearCacheForPlaylist(selectedPlaylist.id!!)
                            playlistsListViewModel.refreshPlaylists()
                            dismiss()
                        }
                        dismiss()
                    } catch (e: Exception) {
                        Log.i("SelectPlaylistBS", "Hubo un error al añadir la canción: ${e.message}")
                        dismiss()
                    }
                }
            },
            onPlaylistLongClick = { _, _ -> },
            onSettingsClick = { }
        )
        playlistRecyclerView.adapter = playlistAdapter
        playlistRecyclerView.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        if (isEmpty) {
            playlistRecyclerView.visibility = View.GONE
            noPlaylistTextView.visibility = View.VISIBLE
            selectPlaylistText.visibility = View.GONE
            noPlaylistTextView.setOnClickListener {
                onNoPlaylistsFound()
                dismiss()
            }
        } else {
            playlistRecyclerView.visibility = View.VISIBLE
            noPlaylistTextView.visibility = View.GONE
            selectPlaylistText.visibility = View.VISIBLE
        }
    }
}