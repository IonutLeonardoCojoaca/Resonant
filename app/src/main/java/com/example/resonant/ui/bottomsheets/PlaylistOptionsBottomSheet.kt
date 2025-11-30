package com.example.resonant.ui.bottomsheets

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide // <--- IMPORTANTE: Importar Glide
import com.example.resonant.R
import com.example.resonant.data.models.Playlist
import com.example.resonant.data.network.ApiClient
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

class PlaylistOptionsBottomSheet(
    private val playlist: Playlist,
    private val playlistImageBitmap: Bitmap?,
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

        // --- CAMBIO PRINCIPAL AQUÍ ---
        val imageUrl = playlist.imageUrl

        if (!imageUrl.isNullOrEmpty()) {
            // 1. Si hay URL del backend, usamos Glide
            Glide.with(this)
                .load(imageUrl)
                .placeholder(R.drawable.ic_playlist_stack)
                .error(R.drawable.ic_playlist_stack)
                .centerCrop()
                .into(playlistImage)
        } else if (playlistImageBitmap != null) {
            // 2. Si llega un Bitmap (sistema antiguo), lo usamos
            playlistImage.setImageBitmap(playlistImageBitmap)
        } else {
            // 3. Si no hay nada, ponemos el placeholder
            playlistImage.setImageResource(R.drawable.ic_playlist_stack)
        }
        // -----------------------------

        playlistName.text = playlist.name
        playlistTracks.text = "${playlist.numberOfTracks ?: 0} canciones"

        val userService = ApiClient.getUserService(requireContext())

        lifecycleScope.launch {
            try {
                // Si el ID del usuario es nulo, evitamos la llamada
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