package com.example.resonant.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.resonant.R
import com.example.resonant.data.models.Playlist
import com.example.resonant.utils.ImageRequestHelper

/**
 * Fila estilo búsqueda (ver item_result_album.xml / SearchResultAdapter):
 * portada 52dp con efecto de tarjeta apilada + título + nº de canciones +
 * avatar/nombre del dueño. Lista plana única — la mezcla entre playlists
 * oficiales de Resonant y de usuarios ya la resuelve
 * PublicPlaylistsViewModel.interleaveByOwnerType(), este adapter no agrupa.
 */
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

    override fun onViewRecycled(holder: PlaylistViewHolder) {
        super.onViewRecycled(holder)
        Glide.with(holder.itemView).clear(holder.ivCover)
    }

    class PlaylistViewHolder(
        itemView: View,
        private val onClick: (Playlist) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        val ivCover: ImageView = itemView.findViewById(R.id.ivPlaylistCover)
        private val tvName: TextView = itemView.findViewById(R.id.tvPlaylistName)
        private val tvOwner: TextView = itemView.findViewById(R.id.tvOwnerName)
        private val tvTracks: TextView = itemView.findViewById(R.id.tvTracksCount)
        private val initialsContainer: FrameLayout = itemView.findViewById(R.id.ownerInitialsContainer)
        private val tvOwnerInitial: TextView = itemView.findViewById(R.id.tvOwnerInitial)
        private val ivOwnerResonantAvatar: ImageView = itemView.findViewById(R.id.ivOwnerResonantAvatar)

        fun bind(playlist: Playlist) {
            tvName.text = playlist.name

            val trackCount = playlist.numberOfTracks
            tvTracks.text = when {
                trackCount == 0 -> "Sin canciones"
                trackCount == 1 -> "1 cancion"
                else -> "$trackCount canciones"
            }

            if (playlist.isSystemPlaylist) {
                tvOwner.text = "Resonant"
                initialsContainer.visibility = View.GONE
                ivOwnerResonantAvatar.visibility = View.VISIBLE
            } else {
                val owner = playlist.ownerName?.takeIf { it.isNotBlank() } ?: "Usuario"
                tvOwner.text = owner
                tvOwnerInitial.text = owner.firstOrNull()?.uppercase() ?: "U"
                initialsContainer.visibility = View.VISIBLE
                ivOwnerResonantAvatar.visibility = View.GONE
            }

            ivCover.scaleType = ImageView.ScaleType.CENTER_CROP
            val url = playlist.imageUrl?.takeIf { it.isNotBlank() }
            Glide.with(itemView.context)
                .load(url?.let { ImageRequestHelper.buildGlideModel(itemView.context, it) })
                .override(160, 160)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .timeout(10_000)
                .placeholder(R.drawable.ic_playlist_stack)
                .error(R.drawable.ic_playlist_stack)
                .centerCrop()
                .dontAnimate()
                .into(ivCover)

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
