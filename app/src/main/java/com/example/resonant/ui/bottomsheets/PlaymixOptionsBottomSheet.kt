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

class PlaymixOptionsBottomSheet(
    private val detail: PlaymixDetailDTO,
    private val onPlayClick: (() -> Unit)? = null,
    private val onEditNameClick: (() -> Unit)? = null,
    private val onDeleteClick: (() -> Unit)? = null
) : BottomSheetDialogFragment() {

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

        // Populate header
        playmixName.text = detail.name
        val count = detail.numberOfTracks
        playmixInfo.text = if (count == 1) "1 canción" else "$count canciones"
        playmixDuration.text = formatDuration(detail.duration)

        val coverUrl = detail.coverUrl
        if (!coverUrl.isNullOrBlank()) {
            Glide.with(playmixImage)
                .load(coverUrl)
                .placeholder(R.drawable.ic_playmix)
                .error(R.drawable.ic_playmix)
                .centerCrop()
                .into(playmixImage)
        } else {
            playmixImage.setImageResource(R.drawable.ic_playmix)
        }

        // Actions
        playAllButton.setOnClickListener {
            dismiss()
            onPlayClick?.invoke()
        }

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
