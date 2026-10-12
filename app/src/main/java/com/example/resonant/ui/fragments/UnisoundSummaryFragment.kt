package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.UnisoundQueueItemDTO
import com.example.resonant.data.network.UnisoundSessionSummaryDTO
import com.example.resonant.ui.adapters.UnisoundQueueAdapter
import com.example.resonant.ui.adapters.UnisoundQueueRow
import com.example.resonant.ui.dialogs.UnisoundSavePlaylistDialog
import com.example.resonant.unisound.UnisoundManager
import com.example.resonant.unisound.UnisoundResult
import com.example.resonant.unisound.UnisoundSaveMessages
import com.example.resonant.unisound.UnisoundSummaryPoller
import com.example.resonant.unisound.UnisoundSummarySongs
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.google.gson.Gson
import kotlinx.coroutines.launch

/**
 * End of a room: duration, number of songs, Aria's line (asked again while pending)
 * and what played, in order, with "Guardar como playlist".
 */
class UnisoundSummaryFragment : Fragment(R.layout.fragment_unisound_summary) {

    private lateinit var ariaLine: TextView
    private lateinit var openButton: View
    private var savedPlaylistId: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val summary = arguments?.getString(ARG_SUMMARY_JSON)
            ?.let { runCatching { Gson().fromJson(it, UnisoundSessionSummaryDTO::class.java) }.getOrNull() }
            ?: UnisoundSessionSummaryDTO()
        val manager = UnisoundManager.get(requireContext())

        ariaLine = view.findViewById(R.id.unisoundSummaryAriaLine)
        openButton = view.findViewById(R.id.unisoundSummaryOpen)
        view.findViewById<View>(R.id.unisoundSummaryClose).setOnClickListener { findNavController().popBackStack() }
        view.findViewById<TextView>(R.id.unisoundSummaryStats).text = statsText(summary)
        renderAriaLine(summary)

        val hasSongs = summary.songsPlayed > 0 && !summary.songIds.isNullOrEmpty()
        view.findViewById<View>(R.id.unisoundSummarySave).apply {
            isVisible = hasSongs && summary.sessionId.isNotBlank()
            setOnClickListener { savePlaylist(summary.sessionId) }
        }
        openButton.setOnClickListener { openSavedPlaylist() }

        if (summary.sessionId.isNotBlank()) {
            viewLifecycleOwner.lifecycleScope.launch {
                UnisoundSummaryPoller(fetch = { manager.getSummary(summary.sessionId) })
                    .poll(summary, ::renderAriaLine)
            }
        }

        val empty = view.findViewById<TextView>(R.id.unisoundSummaryEmpty)
        val adapter = UnisoundQueueAdapter()
        view.findViewById<RecyclerView>(R.id.unisoundSummarySongs).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = adapter
        }
        if (!hasSongs) {
            empty.visibility = View.VISIBLE
            return
        }

        val songService = ApiClient.getSongService(requireContext())
        val resolver = UnisoundSummarySongs(fetchSong = { id -> songService.getSongById(id) })
        viewLifecycleOwner.lifecycleScope.launch {
            val songs = resolver.resolve(summary.songIds.orEmpty(), manager.lastRoomSongs)
            adapter.submitList(songs.mapIndexed { index, song ->
                UnisoundQueueRow(
                    item = UnisoundQueueItemDTO(itemId = "$index:${song.id}", songId = song.id, song = song),
                    addedByLabel = ""
                )
            })
        }
    }

    /** Only a ready line is shown, as plain text; pending, none or unavailable show nothing. */
    private fun renderAriaLine(summary: UnisoundSessionSummaryDTO) {
        val line = summary.ariaLine?.takeIf {
            summary.ariaLineStatus == UnisoundSummaryPoller.STATUS_READY && it.isNotBlank()
        }
        ariaLine.text = line.orEmpty()
        ariaLine.isVisible = line != null
    }

    private fun savePlaylist(sessionId: String) {
        UnisoundSavePlaylistDialog(requireContext()) { name ->
            viewLifecycleOwner.lifecycleScope.launch {
                val result = UnisoundManager.get(requireContext()).savePlaylist(sessionId, name)
                val success = result is UnisoundResult.Success
                if (result is UnisoundResult.Success) {
                    savedPlaylistId = result.value.playlist.playlistId
                    openButton.isVisible = true
                }
                showResonantSnackbar(
                    UnisoundSaveMessages.forResult(result),
                    if (success) R.color.successColor else R.color.errorColor,
                    if (success) R.drawable.ic_success else R.drawable.ic_warning
                )
            }
        }.show()
    }

    private fun openSavedPlaylist() {
        val playlistId = savedPlaylistId ?: return
        findNavController().navigate(R.id.action_global_to_playlistFragment, bundleOf("playlistId" to playlistId))
    }

    private fun statsText(summary: UnisoundSessionSummaryDTO): String {
        val minutes = (summary.durationMs / 60_000L).coerceAtLeast(1L)
        val songs = if (summary.songsPlayed == 1) "1 canción" else "${summary.songsPlayed} canciones"
        return "$minutes min · $songs"
    }

    companion object {
        const val ARG_SUMMARY_JSON = "summaryJson"
    }
}
