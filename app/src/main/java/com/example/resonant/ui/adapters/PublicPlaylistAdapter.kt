package com.example.resonant.ui.adapters

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import com.example.resonant.ui.bottomsheets.ItemOptions.onLongPressOptions
import com.example.resonant.ui.components.DefaultCover

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

    /** Mantener pulsado: abre las opciones del elemento (ItemOptions). */
    var onItemLongClick: ((Playlist) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_public_playlist, parent, false)
        return PlaylistViewHolder(view) { position -> onPlaylistClick(getItem(position)) }
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.itemView.onLongPressOptions {
            val pos = holder.bindingAdapterPosition
            val open = onItemLongClick
            if (pos == RecyclerView.NO_POSITION || open == null) return@onLongPressOptions false
            open(getItem(pos))
            true
        }
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: PlaylistViewHolder) {
        super.onViewRecycled(holder)
        Glide.with(holder.itemView).clear(holder.ivCover)
    }

    class PlaylistViewHolder(
        itemView: View,
        onClick: (Int) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        val ivCover: ImageView = itemView.findViewById(R.id.ivPlaylistCover)
        private val tvName: TextView = itemView.findViewById(R.id.tvPlaylistName)
        private val tvOwner: TextView = itemView.findViewById(R.id.tvOwnerName)
        private val tvTracks: TextView = itemView.findViewById(R.id.tvTracksCount)
        private val initialsContainer: FrameLayout = itemView.findViewById(R.id.ownerInitialsContainer)
        private val tvOwnerInitial: TextView = itemView.findViewById(R.id.tvOwnerInitial)
        private val ivOwnerResonantAvatar: ImageView = itemView.findViewById(R.id.ivOwnerResonantAvatar)
        private val initialsBackground = GradientDrawable().apply { shape = GradientDrawable.OVAL }

        init {
            initialsContainer.background = initialsBackground
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(position)
            }
        }

        fun bind(playlist: Playlist) {
            tvName.text = playlist.name

            val trackCount = playlist.numberOfTracks
            tvTracks.text = when {
                trackCount == 0 -> "Sin canciones"
                trackCount == 1 -> "1 canción"
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
                // Color estable por dueño: cada creador se reconoce de un vistazo.
                initialsBackground.setColor(AVATAR_COLORS[Math.floorMod(owner.hashCode(), AVATAR_COLORS.size)])
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
                .placeholder(DefaultCover.drawable(itemView.context, DefaultCover.Kind.PLAYLIST, playlist.name))
                .error(DefaultCover.drawable(itemView.context, DefaultCover.Kind.PLAYLIST, playlist.name))
                .centerCrop()
                .dontAnimate()
                .into(ivCover)
        }
    }

    private companion object {
        val AVATAR_COLORS = intArrayOf(
            Color.parseColor("#6A4FD8"),
            Color.parseColor("#C47A1E"),
            Color.parseColor("#1E9C78"),
            Color.parseColor("#C23B6E"),
            Color.parseColor("#2F7FC8"),
            Color.parseColor("#9B4FC4"),
            Color.parseColor("#A8902E")
        )
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
