package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.View
import android.widget.TextView
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
import com.example.resonant.unisound.UnisoundManager
import com.example.resonant.unisound.UnisoundSummarySongs
import com.google.gson.Gson
import kotlinx.coroutines.launch

/** End of a room: duration, number of songs and what played, in order. */
class UnisoundSummaryFragment : Fragment(R.layout.fragment_unisound_summary) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val summary = arguments?.getString(ARG_SUMMARY_JSON)
            ?.let { runCatching { Gson().fromJson(it, UnisoundSessionSummaryDTO::class.java) }.getOrNull() }
            ?: UnisoundSessionSummaryDTO()

        view.findViewById<View>(R.id.unisoundSummaryClose).setOnClickListener { findNavController().popBackStack() }
        view.findViewById<TextView>(R.id.unisoundSummaryStats).text = statsText(summary)

        val empty = view.findViewById<TextView>(R.id.unisoundSummaryEmpty)
        val adapter = UnisoundQueueAdapter(onRemove = {})
        view.findViewById<RecyclerView>(R.id.unisoundSummarySongs).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = adapter
        }
        if (summary.songsPlayed <= 0 || summary.songIds.isNullOrEmpty()) {
            empty.visibility = View.VISIBLE
            return
        }

        val songService = ApiClient.getSongService(requireContext())
        val resolver = UnisoundSummarySongs(fetchSong = { id -> songService.getSongById(id) })
        val known = UnisoundManager.get(requireContext()).lastRoomSongs
        viewLifecycleOwner.lifecycleScope.launch {
            val songs = resolver.resolve(summary.songIds.orEmpty(), known)
            adapter.submitList(songs.mapIndexed { index, song ->
                UnisoundQueueRow(
                    item = UnisoundQueueItemDTO(itemId = "$index:${song.id}", songId = song.id, song = song),
                    addedByLabel = "",
                    canRemove = false
                )
            })
        }
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
