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
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.example.resonant.R
import com.example.resonant.ui.viewmodels.ExploreViewModel.ArtistMix
import com.example.resonant.ui.bottomsheets.ItemOptions.onLongPressOptions

/**
 * Filas horizontales de Explorar con las smart playlists por artista:
 * [Kind.ESSENTIALS] ("Esto es", portada editorial) y [Kind.RADIO] (foto en ondas de radio).
 */
class ArtistMixAdapter(
    private val kind: Kind,
    private val onClick: (ArtistMix) -> Unit
) : ListAdapter<ArtistMix, ArtistMixAdapter.MixViewHolder>(DIFF) {

    /** Mantener pulsado: abre las opciones del elemento (ItemOptions). */
    var onItemLongClick: ((ArtistMix) -> Unit)? = null

    enum class Kind { ESSENTIALS, RADIO }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MixViewHolder {
        val layout = if (kind == Kind.ESSENTIALS) R.layout.item_artist_essentials else R.layout.item_artist_radio
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return MixViewHolder(view)
    }

    override fun onBindViewHolder(holder: MixViewHolder, position: Int) {
        holder.itemView.onLongPressOptions {
            val pos = holder.bindingAdapterPosition
            val open = onItemLongClick
            if (pos == RecyclerView.NO_POSITION || open == null) return@onLongPressOptions false
            open(getItem(pos))
            true
        }
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: MixViewHolder) {
        super.onViewRecycled(holder)
        Glide.with(holder.itemView).clear(holder.image)
    }

    inner class MixViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.mixImage)
        private val title: TextView = view.findViewById(R.id.mixTitle)
        private val meta: TextView = view.findViewById(R.id.mixMeta)

        init {
            view.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(getItem(position))
            }
        }

        fun bind(mix: ArtistMix) {
            title.text = mix.artist.name
            val count = mix.playlist.songCount
            if (kind == Kind.ESSENTIALS) {
                meta.text = if (count == 1) "1 canción" else "$count canciones"
                meta.visibility = if (count > 0) View.VISIBLE else View.GONE
            }
            itemView.contentDescription = when (kind) {
                Kind.ESSENTIALS -> "Esto es ${mix.artist.name}"
                Kind.RADIO -> "Radio de ${mix.artist.name}"
            }

            // Foto del artista; si no hay, la portada que genera el servidor.
            val url = mix.artist.url?.takeIf { it.isNotBlank() } ?: mix.playlist.coverUrl
            Glide.with(itemView)
                .load(url)
                .override(if (kind == Kind.ESSENTIALS) 360 else 200)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .placeholder(R.drawable.ic_user)
                .error(R.drawable.ic_user)
                .transition(DrawableTransitionOptions.withCrossFade(180))
                .into(image)
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ArtistMix>() {
            override fun areItemsTheSame(oldItem: ArtistMix, newItem: ArtistMix) =
                oldItem.artist.id == newItem.artist.id && oldItem.playlist.playlistType == newItem.playlist.playlistType

            override fun areContentsTheSame(oldItem: ArtistMix, newItem: ArtistMix) = oldItem == newItem
        }
    }
}
