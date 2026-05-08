package com.example.resonant.ui.bottomsheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.data.network.PlaymixDTO
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.imageview.ShapeableImageView

class PlaymixListOptionsBottomSheet(
    private val playmix: PlaymixDTO,
    private val onDeleteClick: (PlaymixDTO) -> Unit
) : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.AppBottomSheetDialogTheme

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.bottom_sheet_playmix_list_options, container, false)

        val coverImage: ShapeableImageView = view.findViewById(R.id.playmixCover)
        val nameText: TextView = view.findViewById(R.id.playmixName)
        val infoText: TextView = view.findViewById(R.id.playmixInfo)
        val deleteButton: TextView = view.findViewById(R.id.deletePlaymixButton)
        val cancelButton: TextView = view.findViewById(R.id.cancelButton)

        nameText.text = playmix.name
        val count = playmix.numberOfTracks
        infoText.text = if (count == 1) "1 canción" else "$count canciones"

        if (!playmix.coverUrl.isNullOrBlank()) {
            Glide.with(coverImage)
                .load(playmix.coverUrl)
                .placeholder(R.drawable.ic_playmix)
                .error(R.drawable.ic_playmix)
                .centerCrop()
                .into(coverImage)
        } else {
            coverImage.setImageResource(R.drawable.ic_playmix)
        }

        deleteButton.setOnClickListener {
            dismiss()
            onDeleteClick(playmix)
        }

        cancelButton.setOnClickListener {
            dismiss()
        }

        return view
    }
}
