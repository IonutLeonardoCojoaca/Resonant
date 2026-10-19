package com.example.resonant.ui.bottomsheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.data.network.PlaymixDetailDTO
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.imageview.ShapeableImageView
import com.example.resonant.ui.components.DefaultCover

class PlaymixOptionsBottomSheet(
    private val detail: PlaymixDetailDTO,
    private val onPlayClick: (() -> Unit)? = null,
    private val onEditNameClick: (() -> Unit)? = null,
    private val onDeleteClick: (() -> Unit)? = null,
    /** Cola de la Playmix entera (collection-queue-offline). Sin callbacks, las filas no se muestran. */
    private val onPlayNextClick: (() -> Unit)? = null,
    private val onAddToQueueClick: (() -> Unit)? = null,
    /** `null` = sin fila de descarga. `true` muestra "Quitar descarga". */
    private val isDownloaded: Boolean? = null,
    private val onDownloadClick: (() -> Unit)? = null,
    private val onRemoveDownloadClick: (() -> Unit)? = null,
    /** Compartir y descubrir (playmix-sharing-discovery). null = sin esas filas (capacidad apagada). */
    private val sharing: PlaymixSheetSharing? = null
) : ResonantBottomSheetDialogFragment() {

    override val hasOptionRows = true

    override fun getTheme(): Int = R.style.AppBottomSheetDialogTheme

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.bottom_sheet_playmix_options, container, false)

        val playmixImage: ShapeableImageView = view.findViewById(R.id.playmixImage)
        val playmixName: TextView = view.findViewById(R.id.playmixName)
        val playmixInfo: TextView = view.findViewById(R.id.playmixInfo)
        val playmixDuration: TextView = view.findViewById(R.id.playmixDuration)

        val playAllButton: TextView = view.findViewById(R.id.playAllButton)
        val editNameButton: TextView = view.findViewById(R.id.editNameButton)
        val deletePlaymixButton: TextView = view.findViewById(R.id.deletePlaymixButton)
        val cancelButton: TextView = view.findViewById(R.id.cancelButton)
        val playNextButton: TextView = view.findViewById(R.id.playNextButton)
        val addToQueueButton: TextView = view.findViewById(R.id.addToQueueButton)
        val downloadButton: TextView = view.findViewById(R.id.downloadPlaymixButton)

        // Populate header
        playmixName.text = detail.name
        val count = detail.numberOfTracks
        playmixInfo.text = if (count == 1) "1 canción" else "$count canciones"
        playmixDuration.text = formatDuration(detail.duration)

        val coverUrl = detail.coverUrl
        DefaultCover.load(playmixImage, coverUrl, DefaultCover.Kind.PLAYMIX, detail.name)

        // Actions
        playAllButton.setOnClickListener {
            dismiss()
            onPlayClick?.invoke()
        }

        playNextButton.visibility = if (onPlayNextClick != null) View.VISIBLE else View.GONE
        playNextButton.setOnClickListener {
            dismiss()
            onPlayNextClick?.invoke()
        }
        addToQueueButton.visibility = if (onAddToQueueClick != null) View.VISIBLE else View.GONE
        addToQueueButton.setOnClickListener {
            dismiss()
            onAddToQueueClick?.invoke()
        }
        val downloaded = isDownloaded
        downloadButton.visibility = if (downloaded == null) View.GONE else View.VISIBLE
        if (downloaded == true) {
            downloadButton.text = "Quitar descarga"
            OptionRows.setIcon(downloadButton, R.drawable.ic_delete)
        }
        downloadButton.setOnClickListener {
            dismiss()
            if (downloaded == true) onRemoveDownloadClick?.invoke() else onDownloadClick?.invoke()
        }

        sharing?.bind(view, ::dismiss)

        // Sin callback (Playmix ajena) no se ofrece editar ni borrar.
        editNameButton.visibility = if (onEditNameClick != null) View.VISIBLE else View.GONE
        deletePlaymixButton.visibility = if (onDeleteClick != null) View.VISIBLE else View.GONE

        editNameButton.setOnClickListener {
            dismiss()
            onEditNameClick?.invoke()
        }

        deletePlaymixButton.setOnClickListener {
            dismiss()
            onDeleteClick?.invoke()
        }

        cancelButton.setOnClickListener {
            dismiss()
        }

        return view
    }

    private fun formatDuration(seconds: Int): String {
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        return if (hrs > 0) {
            String.format("%dh %02dmin", hrs, mins)
        } else {
            String.format("%d min", mins)
        }
    }
}
