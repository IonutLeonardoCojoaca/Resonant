package com.example.resonant.ui.bottomsheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.example.resonant.R
import com.example.resonant.data.models.Song
import com.example.resonant.ui.flow.FlowFeedbackActions

/**
 * Opciones de una canción que añadió Flow: feedback para el recomendador y acceso al menú
 * normal de la canción. Sigue el patrón de lambdas de [SongOptionsBottomSheet].
 */
class FlowSongOptionsBottomSheet(
    private val song: Song,
    private val onSongOptions: (Song) -> Unit
) : ResonantBottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.bottom_sheet_flow_song_options, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.flowOptionsTitle).text = song.title
        view.findViewById<TextView>(R.id.flowLessArtistButton).apply {
            visibility = if (song.artists.isNotEmpty()) View.VISIBLE else View.GONE
            setOnClickListener {
                FlowFeedbackActions.lessOfArtist(this@FlowSongOptionsBottomSheet, song)
                dismiss()
            }
        }
        view.findViewById<TextView>(R.id.flowNeverSongButton).setOnClickListener {
            FlowFeedbackActions.neverSuggest(this, song)
            dismiss()
        }
        view.findViewById<TextView>(R.id.flowSongOptionsButton).setOnClickListener {
            dismiss()
            onSongOptions(song)
        }
    }
}
