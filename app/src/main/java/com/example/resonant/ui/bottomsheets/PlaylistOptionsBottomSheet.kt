package com.example.resonant.ui.bottomsheets

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.data.models.Playlist
import com.example.resonant.data.network.ApiClient
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

class PlaylistOptionsBottomSheet(
    private val playlist: Playlist,
    private val playlistImageBitmap: Bitmap?,
    private val onDeleteClick: (Playlist) -> Unit,
    private val onEditClick: (Playlist) -> Unit
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

        // Referencias a botones
        val editBtn = view.findViewById<TextView>(R.id.editPlaylistButton) // 👇 REFERENCIA
        val deleteBtn = view.findViewById<TextView>(R.id.deletePlaylistButton)
        val cancelButton = view.findViewById<TextView>(R.id.cancelButton)

        // ... (Tu código de carga de imagen existente va aquí) ...
        val imageUrl = playlist.imageUrl
        if (!imageUrl.isNullOrEmpty()) {
            Glide.with(this).load(imageUrl).placeholder(R.drawable.ic_playlist_stack).into(playlistImage)
        } else if (playlistImageBitmap != null) {
            playlistImage.setImageBitmap(playlistImageBitmap)
        } else {
            playlistImage.setImageResource(R.drawable.ic_playlist_stack)
        }

        playlistName.text = playlist.name
        playlistTracks.text = "${playlist.numberOfTracks ?: 0} canciones"

        val userService = ApiClient.getUserService(requireContext())
        lifecycleScope.launch {
            try {
                val userId = playlist.userId
                if (!userId.isNullOrEmpty()) {
                    val user = userService.getUserById(userId)
                    playlistOwner.text = user.name
                } else {
                    playlistOwner.text = "Desconocido"
                }
            } catch (e: Exception) {
                playlistOwner.text = "Desconocido"
            }
        }

        // 👇 2. LISTENER PARA EDITAR
        editBtn.setOnClickListener {
            dismiss() // Cerramos el bottom sheet primero
            onEditClick(playlist) // Navegamos
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