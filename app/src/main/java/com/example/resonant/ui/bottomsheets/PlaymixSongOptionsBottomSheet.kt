package com.example.resonant.ui.bottomsheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.data.network.PlaymixSongDTO
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.imageview.ShapeableImageView

class PlaymixSongOptionsBottomSheet(
    private val song: PlaymixSongDTO,
    private val onPlayClick: ((PlaymixSongDTO) -> Unit)? = null,
    private val onSeeSongClick: ((PlaymixSongDTO) -> Unit)? = null,
    private val onRemoveClick: ((PlaymixSongDTO) -> Unit)? = null
) : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.AppBottomSheetDialogTheme

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.bottom_sheet_playmix_song_options, container, false)

        val songImage: ShapeableImageView = view.findViewById(R.id.songImage)
        val songTitle: TextView = view.findViewById(R.id.songTitle)
        val songArtist: TextView = view.findViewById(R.id.songArtist)
        val songDuration: TextView = view.findViewById(R.id.songDuration)

        val playSongButton: TextView = view.findViewById(R.id.playSongButton)
        val seeSongButton: TextView = view.findViewById(R.id.seeSongButton)
        val removeSongButton: TextView = view.findViewById(R.id.removeSongButton)
        val cancelButton: TextView = view.findViewById(R.id.cancelButton)

        // Populate header
        songTitle.text = song.title ?: "Sin título"
        songArtist.text = song.artist ?: "Artista desconocido"
        songDuration.text = formatDuration(song.duration)

        val coverUrl = song.imageUrl ?: song.coverUrl
        if (!coverUrl.isNullOrBlank()) {
            Glide.with(songImage)
                .load(coverUrl)
                .placeholder(R.drawable.ic_disc)
                .error(R.drawable.ic_disc)
                .centerCrop()
                .into(songImage)
        } else {
            songImage.setImageResource(R.drawable.ic_disc)
        }

        // Actions
        playSongButton.setOnClickListener {
            dismiss()
            onPlayClick?.invoke(song)
        }

        seeSongButton.setOnClickListener {
            dismiss()
            onSeeSongClick?.invoke(song)
        }

        removeSongButton.setOnClickListener {
            dismiss()
            onRemoveClick?.invoke(song)
        }

        cancelButton.setOnClickListener {
            dismiss()
        }

        return view
    }

    private fun formatDuration(totalSeconds: Int): String {
        val mins = totalSeconds / 60
        val secs = totalSeconds % 60
        return String.format("%d:%02d", mins, secs)
    }
}
