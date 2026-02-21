package com.example.resonant.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.data.models.Playlist

data class PlaylistSection(
    val ownerName: String,
    val playlists: List<Playlist>
)

class PublicPlaylistSectionAdapter(
    private val onPlaylistClick: (Playlist) -> Unit
) : RecyclerView.Adapter<PublicPlaylistSectionAdapter.SectionViewHolder>() {

    private var sections: List<PlaylistSection> = emptyList()

    fun submitSections(newSections: List<PlaylistSection>) {
        sections = newSections
        notifyDataSetChanged()
    }

    override fun getItemCount() = sections.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_public_playlist_section, parent, false)
        return SectionViewHolder(view, onPlaylistClick)
    }

    override fun onBindViewHolder(holder: SectionViewHolder, position: Int) {
        holder.bind(sections[position])
    }

    class SectionViewHolder(
        itemView: View,
        private val onPlaylistClick: (Playlist) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val tvOwnerInitial: TextView = itemView.findViewById(R.id.tvOwnerInitial)
        private val tvOwnerName: TextView = itemView.findViewById(R.id.tvSectionOwnerName)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvSectionSubtitle)
        private val rvPlaylists: RecyclerView = itemView.findViewById(R.id.rvSectionPlaylists)

        fun bind(section: PlaylistSection) {
            // Inicial del avatar
            tvOwnerInitial.text = section.ownerName.firstOrNull()?.uppercase() ?: "U"
            tvOwnerName.text = section.ownerName

            val count = section.playlists.size
            tvSubtitle.text = when {
                count == 1 -> "1 playlist pública"
                else -> "$count playlists públicas"
            }

            // Nested horizontal RecyclerView
            val adapter = PublicPlaylistAdapter(onPlaylistClick)
            rvPlaylists.layoutManager = LinearLayoutManager(
                itemView.context,
                LinearLayoutManager.HORIZONTAL,
                false
            )
            rvPlaylists.adapter = adapter
            rvPlaylists.isNestedScrollingEnabled = false
            adapter.submitList(section.playlists)
        }
    }
}
