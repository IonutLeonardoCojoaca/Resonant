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
import com.example.resonant.playback.QueueCommands
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import com.example.resonant.ui.components.DefaultCover

class PlaylistOptionsBottomSheet(
    private val playlist: Playlist,
    private val playlistImageBitmap: Bitmap?,
    private val onDeleteClick: (Playlist) -> Unit,
    private val onEditClick: (Playlist) -> Unit,
    private val onToggleVisibilityClick: ((Playlist) -> Unit)? = null,
    /** `null` = sin fila de descarga. `true` muestra "Quitar descarga". */
    private val isDownloaded: Boolean? = null,
    private val onDownloadClick: ((Playlist) -> Unit)? = null,
    private val onRemoveDownloadClick: ((Playlist) -> Unit)? = null
) : ResonantBottomSheetDialogFragment() {

    override val hasOptionRows = true

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
        bindAddToRoom(view.findViewById(R.id.addToRoomButton), SOURCE_PLAYLIST, playlist.id.orEmpty())

        // Cola y descarga de la lista entera (collection-queue-offline).
        view.findViewById<TextView>(R.id.playNextButton).setOnClickListener {
            CollectionQueueActions.playNext(this, QueueCommands.COLLECTION_PLAYLIST, playlist.id.orEmpty())
            dismiss()
        }
        view.findViewById<TextView>(R.id.addToQueueButton).setOnClickListener {
            CollectionQueueActions.addToQueue(this, QueueCommands.COLLECTION_PLAYLIST, playlist.id.orEmpty())
            dismiss()
        }
        view.findViewById<TextView>(R.id.downloadPlaylistButton).let { downloadBtn ->
            val downloaded = isDownloaded
            downloadBtn.visibility = if (downloaded == null) View.GONE else View.VISIBLE
            if (downloaded == true) {
                downloadBtn.text = "Quitar descarga"
                OptionRows.setIcon(downloadBtn, R.drawable.ic_delete)
            }
            downloadBtn.setOnClickListener {
                dismiss()
                if (downloaded == true) onRemoveDownloadClick?.invoke(playlist) else onDownloadClick?.invoke(playlist)
            }
        }

        // Cover image
        val imageUrl = playlist.imageUrl
        when {
            imageUrl.isNullOrEmpty() && playlistImageBitmap != null -> playlistImage.setImageBitmap(playlistImageBitmap)
            else -> DefaultCover.load(playlistImage, imageUrl, DefaultCover.Kind.PLAYLIST, playlist.name)
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
        OptionRows.setIcon(toggleVisibilityBtn, if (isPublic == true) R.drawable.ic_private else R.drawable.ic_public)

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
