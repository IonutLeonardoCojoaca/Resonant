package com.example.resonant

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.SnackbarUtils.showResonantSnackbar
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

    // ✅ 1. OBTENEMOS AMBOS VIEWMODELS CON ALCANCE DE ACTIVITY
    private val playlistsListViewModel: PlaylistsListViewModel by viewModels {
        val service = ApiClient.getService(requireContext())
        val playlistManager = PlaylistManager(service)
        PlaylistsListViewModelFactory(playlistManager)
    }
    private val playlistDetailViewModel: PlaylistDetailViewModel by viewModels {
        val service = ApiClient.getService(requireContext())
        val playlistManager = PlaylistManager(service)
        PlaylistDetailViewModelFactory(playlistManager)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.bottom_sheet_playlists_selector, container, false)

        // --- Inicialización de vistas ---
        val songImage: ShapeableImageView = view.findViewById(R.id.songImage)
        val songTitle: TextView = view.findViewById(R.id.songTitle)
        val songArtist: TextView = view.findViewById(R.id.songArtist)
        noPlaylistTextView = view.findViewById(R.id.noPlaylistTextView)
        selectPlaylistText = view.findViewById(R.id.selectPlaylistText)
        playlistRecyclerView = view.findViewById(R.id.playlistList)

        // --- Configuración de la información de la canción (parte superior) ---
        songTitle.text = song.title
        songArtist.text = song.artistName
        Glide.with(songImage).load(song.coverUrl ?: song.imageFileName).placeholder(R.drawable.album_cover).into(songImage)

        // --- Configuración del RecyclerView ---
        setupRecyclerView()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ✅ 2. OBSERVAMOS LA LISTA DE PLAYLISTS DESDE EL VIEWMODEL
        playlistsListViewModel.playlists.observe(viewLifecycleOwner) { playlists ->
            playlistAdapter.submitList(playlists ?: emptyList())
            updateEmptyState(playlists.isEmpty())
        }

        // ✅ 3. PEDIMOS AL VIEWMODEL QUE CARGUE LAS PLAYLISTS
        val userId = UserManager.getUserId(requireContext())
        if (userId != null) {
            playlistsListViewModel.getPlaylistsByUserId(userId)
        } else {
            updateEmptyState(true) // No hay usuario, por tanto no hay playlists
        }
    }

    private fun setupRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            PlaylistAdapter.VIEW_TYPE_LIST,
            onClick = { selectedPlaylist ->
                // ✅ 4. AL HACER CLICK, USAMOS EL VIEWMODEL DE DETALLE PARA LA ACCIÓN
                lifecycleScope.launch {
                    try {
                        val alreadyInPlaylist = playlistDetailViewModel.checkSongInPlaylist(song.id, selectedPlaylist.id!!)
                        if (alreadyInPlaylist) {
                            showResonantSnackbar(
                                text = "La canción ya está en la lista",
                                colorRes = R.color.adviseColor,
                                iconRes = R.drawable.information
                            )
                        } else {
                            playlistDetailViewModel.addSongToPlaylist(
                                song.id,
                                selectedPlaylist.id!!,
                                requireContext()
                            )
                            showResonantSnackbar(
                                text = "¡Canción añadida a '${selectedPlaylist.name}'!",
                                colorRes = R.color.successColor,
                                iconRes = R.drawable.success
                            )
                            // 2. ¡AQUÍ ESTÁ LA MAGIA!
                            // Forzamos al adapter a olvidar la portada vieja
                            playlistAdapter.clearCacheForPlaylist(selectedPlaylist.id!!)

                            // Forzamos al ViewModel de listas a recargar todo
                            playlistsListViewModel.refreshPlaylists()

                            // 3. Cerramos el BottomSheet
                            dismiss()
                        }
                        dismiss()
                    } catch (e: Exception) {
                        Log.i("SelectPlaylistBS", "Hubo un error al añadir la canción: ${e.message}")
                        dismiss()
                    }
                }
            }
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
                // El dismiss() no es estrictamente necesario aquí,
                // ya que la lambda probablemente cerrará toda la pila de diálogos.
                // Pero no hace daño dejarlo.
                dismiss()
            }
        } else {
            playlistRecyclerView.visibility = View.VISIBLE
            noPlaylistTextView.visibility = View.GONE
            selectPlaylistText.visibility = View.VISIBLE
        }
    }
}