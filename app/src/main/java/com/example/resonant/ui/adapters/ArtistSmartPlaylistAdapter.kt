package com.example.resonant.ui.adapters

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
import com.example.resonant.data.models.ArtistSmartPlaylist

class ArtistSmartPlaylistAdapter(
    private val onItemClick: (ArtistSmartPlaylist) -> Unit
) : ListAdapter<ArtistSmartPlaylist, ArtistSmartPlaylistAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_smart_playlist, parent, false)
        // Reset width to fixed size for horizontal list
        view.layoutParams = ViewGroup.MarginLayoutParams(480, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
             setMargins(15, 0, 15, 0)
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val playlist = getItem(position)
        holder.bind(playlist, onItemClick)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val coverImage: ImageView = itemView.findViewById(R.id.artistImage)
        private val nameText: TextView = itemView.findViewById(R.id.albumName)
        private val descText: TextView = itemView.findViewById(R.id.artistName)
        private val typeOverlay: TextView = itemView.findViewById(R.id.playlistTypeOverlay)

        fun bind(playlist: ArtistSmartPlaylist, onItemClick: (ArtistSmartPlaylist) -> Unit) {
            nameText.text = playlist.name
            descText.text = playlist.description
            
            // Set the overlay text
            typeOverlay.text = if (playlist.playlistType.equals("Essentials", ignoreCase = true)) "ESSENTIALS" else "RADIO"

            Glide.with(itemView.context)
                .load(playlist.coverUrl)
                .placeholder(R.drawable.ic_playlist_stack)
                .error(R.drawable.ic_playlist_stack)
                .into(coverImage)

            itemView.setOnClickListener { onItemClick(playlist) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ArtistSmartPlaylist>() {
        override fun areItemsTheSame(oldItem: ArtistSmartPlaylist, newItem: ArtistSmartPlaylist): Boolean {
            // Uniquely identify by artist + type
            return oldItem.artistId == newItem.artistId && oldItem.playlistType == newItem.playlistType
        }

        override fun areContentsTheSame(oldItem: ArtistSmartPlaylist, newItem: ArtistSmartPlaylist): Boolean {
            return oldItem == newItem
        }
    }
}
