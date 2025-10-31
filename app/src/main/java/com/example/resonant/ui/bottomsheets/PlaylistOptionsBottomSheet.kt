package com.example.resonant.ui.bottomsheets

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.example.resonant.R
import com.example.resonant.data.models.Playlist
import com.example.resonant.data.network.ApiClient
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

class PlaylistOptionsBottomSheet(
    private val playlist: Playlist,
    private val playlistImageBitmap: Bitmap?, // <-- Nuevo parámetro
    private val onDeleteClick: (Playlist) -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.bottom_sheet_playlist_options, container, false)

        val playlistImage = view.findViewById<ImageView>(R.id.playlistImage)
        val playlistName = view.findViewById<TextView>(R.id.playlistName)
        val playlistOwner = view.findViewById<TextView>(R.id.playlistOwner)
        val playlistTracks = view.findViewById<TextView>(R.id.playlistNumberOfTracks)
        val deleteBtn = view.findViewById<TextView>(R.id.deletePlaylistButton)
        val cancelButton = view.findViewById<TextView>(R.id.cancelButton)

        // Setear la imagen si existe
// Setear la imagen si existe
        if (playlistImageBitmap != null) {
            playlistImage.setImageBitmap(playlistImageBitmap)
        } else {
            playlistImage.setImageResource(R.drawable.ic_playlist_stack)
        }

        // Setear datos básicos
        playlistName.text = playlist.name
        playlistTracks.text = "${playlist.numberOfTracks ?: 0} canciones"

        // Consultar el nombre del usuario por su id
        val service = ApiClient.getService(requireContext())
        lifecycleScope.launch {
            try {
                val user = service.getUserById(playlist.userId ?: "")
                playlistOwner.text = user.name
            } catch (e: Exception) {
                playlistOwner.text = "Desconocido"
            }
        }

        deleteBtn.setOnClickListener {
            onDeleteClick(playlist)
            dismiss()
        }

        cancelButton.setOnClickListener {
            dismiss()
        }

        return view
    }
}