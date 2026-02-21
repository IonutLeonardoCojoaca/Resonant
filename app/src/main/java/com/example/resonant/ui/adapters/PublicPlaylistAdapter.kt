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
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.example.resonant.R
import com.example.resonant.data.models.Playlist

class PublicPlaylistAdapter(
    private val onPlaylistClick: (Playlist) -> Unit
) : ListAdapter<Playlist, PublicPlaylistAdapter.PlaylistViewHolder>(PlaylistDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_public_playlist, parent, false)
        return PlaylistViewHolder(view, onPlaylistClick)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class PlaylistViewHolder(itemView: View, val onClick: (Playlist) -> Unit) :
        RecyclerView.ViewHolder(itemView) {

        private val ivCover: ImageView = itemView.findViewById(R.id.ivPlaylistCover)
        private val tvName: TextView = itemView.findViewById(R.id.tvPlaylistName)
        private val tvOwner: TextView = itemView.findViewById(R.id.tvOwnerName)
        private val tvTracks: TextView = itemView.findViewById(R.id.tvTracksCount)

        fun bind(playlist: Playlist) {
            tvName.text = playlist.name

            val trackCount = playlist.numberOfTracks
            tvTracks.text = when {
                trackCount == 0 -> "Sin canciones"
                trackCount == 1 -> "1 canción"
                else -> "$trackCount canciones"
            }

            // Nombre del owner: "Por X" o "Usuario" como fallback
            val owner = playlist.ownerName?.takeIf { it.isNotBlank() } ?: "Usuario"
            tvOwner.text = "Por $owner"

            // Cargar imagen de portada
            if (!playlist.imageUrl.isNullOrEmpty()) {
                Glide.with(itemView.context)
                    .load(playlist.imageUrl)
                    .transform(CenterCrop())
                    .placeholder(R.drawable.ic_playlist_stack)
                    .error(R.drawable.ic_playlist_stack)
                    .into(ivCover)
            } else {
                ivCover.setImageResource(R.drawable.ic_playlist_stack)
                ivCover.scaleType = ImageView.ScaleType.CENTER_INSIDE
                ivCover.setBackgroundColor(0xFF222222.toInt())
            }

            itemView.setOnClickListener {
                onClick(playlist)
            }
        }
    }

    class PlaylistDiffCallback : DiffUtil.ItemCallback<Playlist>() {
        override fun areItemsTheSame(oldItem: Playlist, newItem: Playlist): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Playlist, newItem: Playlist): Boolean {
            return oldItem == newItem && oldItem.ownerName == newItem.ownerName
        }
    }
}
