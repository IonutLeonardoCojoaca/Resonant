package com.example.resonant.feature.collabfinder.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.feature.collabfinder.domain.model.SharedSong

class SharedSongsAdapter(
    private val onItemClicked: (SharedSong) -> Unit
) : ListAdapter<SharedSong, SharedSongsAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_shared_song, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivAlbumCover: ImageView = itemView.findViewById(R.id.ivAlbumCover)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvAlbum: TextView = itemView.findViewById(R.id.tvAlbum)
        private val tvArtists: TextView = itemView.findViewById(R.id.tvArtists)
        private val tvYear: TextView = itemView.findViewById(R.id.tvYear)
        private val tvStreams: TextView = itemView.findViewById(R.id.tvStreams)

        fun bind(song: SharedSong) {
            tvTitle.text = song.title
            tvAlbum.text = song.albumTitle
            tvArtists.text = song.allArtists.joinToString(" · ") { it.name }
            tvYear.text = song.releaseYear?.toString() ?: ""
            
            val streamStr = song.streams?.let { formatStreams(it) } ?: ""
            tvStreams.text = streamStr

            Glide.with(itemView.context)
                .load(song.albumCoverUrl)
                .into(ivAlbumCover)

            itemView.setOnClickListener {
                onItemClicked(song)
            }
        }
        
        private fun formatStreams(streams: Long): String {
            return when {
                streams >= 1_000_000 -> String.format("%.1fM", streams / 1_000_000.0)
                streams >= 1_000 -> String.format("%.1fK", streams / 1_000.0)
                else -> streams.toString()
            }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<SharedSong>() {
            override fun areItemsTheSame(oldItem: SharedSong, newItem: SharedSong): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: SharedSong, newItem: SharedSong): Boolean {
                return oldItem == newItem
            }
        }
    }
}
