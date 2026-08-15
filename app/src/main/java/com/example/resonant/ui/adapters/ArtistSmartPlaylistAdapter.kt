package com.example.resonant.ui.adapters

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.example.resonant.R
import com.example.resonant.data.models.ArtistSmartPlaylist

import com.example.resonant.utils.ImageRequestHelper
import com.example.resonant.utils.MiniPlayerColorizer

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
        private val itemContainer: View = itemView.findViewById(R.id.itemContainer)
        private val coverImage: ImageView = itemView.findViewById(R.id.artistImage)
        private val nameText: TextView = itemView.findViewById(R.id.albumName)
        private val descText: TextView = itemView.findViewById(R.id.artistName)
        private val typeOverlay: TextView = itemView.findViewById(R.id.playlistTypeOverlay)

        fun bind(playlist: ArtistSmartPlaylist, onItemClick: (ArtistSmartPlaylist) -> Unit) {
            nameText.text = playlist.name
            descText.text = playlist.description

            // Set the overlay text
            val isEssentials = playlist.playlistType.equals("Essentials", ignoreCase = true)
            typeOverlay.text = if (isEssentials) "ESSENTIALS" else "RADIO"

            val url = playlist.coverUrl
            val fallbackRes = if (isEssentials) R.drawable.ic_essentials else R.drawable.ic_radio
            val fallbackColor = ContextCompat.getColor(itemView.context, R.color.cardsTheme)

            // Same dominant-color card tint already used by AlbumAdapter's
            // detailed/favorite cards: paints itemContainer's flat background
            // with the cover's Palette color instead of a fixed cardsTheme.
            val targets = MiniPlayerColorizer.Targets(
                container = itemContainer,
                title = nameText,
                subtitle = descText
            )

            if (url.isNullOrBlank() || url == "null") {
                coverImage.setImageResource(fallbackRes)
                MiniPlayerColorizer.applyFromImageView(coverImage, targets, fallbackColor, animateMillis = 0L)
            } else {
                val model = ImageRequestHelper.buildGlideModel(itemView.context, url)
                Glide.with(itemView.context)
                    .asBitmap()
                    .load(model)
                    .placeholder(fallbackRes)
                    .error(fallbackRes)
                    .into(object : CustomTarget<Bitmap>() {
                        override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                            coverImage.setImageBitmap(resource)
                            MiniPlayerColorizer.applyFromImageView(coverImage, targets, fallbackColor, animateMillis = 300L)
                        }

                        override fun onLoadCleared(placeholder: Drawable?) {
                            coverImage.setImageDrawable(placeholder)
                        }

                        override fun onLoadFailed(errorDrawable: Drawable?) {
                            coverImage.setImageDrawable(errorDrawable)
                            MiniPlayerColorizer.applyFromImageView(coverImage, targets, fallbackColor, animateMillis = 0L)
                        }
                    })
            }

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
