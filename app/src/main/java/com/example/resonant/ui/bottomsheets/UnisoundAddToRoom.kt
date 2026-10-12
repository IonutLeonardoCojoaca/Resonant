package com.example.resonant.ui.bottomsheets

import android.view.View
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.resonant.R
import com.example.resonant.unisound.UnisoundManager
import com.example.resonant.unisound.UnisoundResult
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import kotlinx.coroutines.launch

/**
 * "Añadir a la sala" in an options sheet: visible only in a room where this user can add.
 * [type] is the hub source type ("song", "album" or "playlist"). Errors are reported by
 * [UnisoundManager]; success shows how many songs went to the room.
 */
internal fun DialogFragment.bindAddToRoom(button: TextView, type: String, id: String) {
    val manager = UnisoundManager.get(requireContext())
    button.visibility = if (manager.canAddNow() && id.isNotBlank()) View.VISIBLE else View.GONE
    button.setOnClickListener {
        val host = requireActivity()
        dismiss()
        host.lifecycleScope.launch {
            val result = if (type == SOURCE_SONG) manager.addSong(id) else manager.addSource(type, id)
            if (result is UnisoundResult.Success) {
                val count = result.value.size
                val text = when {
                    type == SOURCE_SONG -> "Añadida a la sala"
                    count == 1 -> "1 canción añadida a la sala"
                    else -> "$count canciones añadidas a la sala"
                }
                host.showResonantSnackbar(text, R.color.successColor, R.drawable.ic_success)
            }
        }
    }
}

internal const val SOURCE_SONG = "song"
internal const val SOURCE_ALBUM = "album"
internal const val SOURCE_PLAYLIST = "playlist"
