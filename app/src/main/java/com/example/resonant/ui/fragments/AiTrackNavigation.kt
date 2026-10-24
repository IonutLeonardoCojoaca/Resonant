package com.example.resonant.ui.fragments

import android.os.Bundle
import androidx.navigation.NavController
import com.example.resonant.R
import com.example.resonant.data.network.AiTrackDTO

/** Abre una generación de "Crear con IA": la canción si ya está publicada; si no, la pantalla de generación. */
object AiTrackNavigation {
    fun open(nav: NavController, track: AiTrackDTO) {
        val songId = track.songId
        if (songId != null) {
            nav.navigate(R.id.action_global_to_detailedSongFragment, Bundle().apply { putString("songId", songId) })
        } else {
            nav.navigate(
                R.id.action_global_to_aiTrackProgressFragment,
                Bundle().apply { putString(AiTrackProgressFragment.ARG_GENERATION_ID, track.id) }
            )
        }
    }
}
