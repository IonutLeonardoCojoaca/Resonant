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
    private val onEditClick: (Playlist) -> Unit,
    private val onToggleVisibilityClick: ((Playlist) -> Unit)? = null
) : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.AppBottomSheetDialogTheme

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

        val editBtn = view.findViewById<TextView>(R.id.editPlaylistButton)
        val toggleVisibilityBtn = view.findViewById<TextView>(R.id.toggleVisibilityButton)
        val deleteBtn = view.findViewById<TextView>(R.id.deletePlaylistButton)
        val cancelButton = view.findViewById<TextView>(R.id.cancelButton)

        // Cover image
        val imageUrl = playlist.imageUrl
        when {
            !imageUrl.isNullOrEmpty() ->
                Glide.with(this).load(imageUrl).placeholder(R.drawable.ic_playlist_stack).into(playlistImage)
            playlistImageBitmap != null -> playlistImage.setImageBitmap(playlistImageBitmap)
            else -> playlistImage.setImageResource(R.drawable.ic_playlist_stack)
        }

        playlistName.text = playlist.name
        val count = playlist.numberOfTracks ?: 0
        playlistTracks.text = when {
            count == 0 -> "Sin canciones"
            count == 1 -> "1 canción"
            else -> "$count canciones"
        }

        // Load owner name async
        val userService = ApiClient.getUserService(requireContext())
        lifecycleScope.launch {
            try {
                val userId = playlist.userId
                if (!userId.isNullOrEmpty()) {
                    val user = userService.getUserById(userId)
                    playlistOwner.text = user.name ?: "Desconocido"
                } else {
                    playlistOwner.text = "Desconocido"
                }
            } catch (_: Exception) {
                playlistOwner.text = "Desconocido"
            }
        }

        // Toggle visibility button label + icon
        val isPublic = playlist.isPublic
        toggleVisibilityBtn.text = if (isPublic == true) "Hacer privada" else "Hacer pública"
        toggleVisibilityBtn.setCompoundDrawablesWithIntrinsicBounds(
            if (isPublic == true) R.drawable.ic_private else R.drawable.ic_public, 0, 0, 0
        )

        // Listeners
        editBtn.setOnClickListener {
            dismiss()
            onEditClick(playlist)
        }

        toggleVisibilityBtn.setOnClickListener {
            dismiss()
            onToggleVisibilityClick?.invoke(playlist)
        }

        deleteBtn.setOnClickListener {
            com.example.resonant.ui.dialogs.ResonantDialog(requireContext())
                .setTitle("¿Eliminar playlist?")
                .setMessage("¿Estás seguro de que deseas eliminar '${playlist.name}'?")
                .setPositiveButton("Eliminar") {
                    onDeleteClick(playlist)
                    dismiss()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        cancelButton.setOnClickListener { dismiss() }

        return view
    }
}