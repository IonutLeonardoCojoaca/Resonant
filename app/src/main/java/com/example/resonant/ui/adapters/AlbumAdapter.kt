package com.example.resonant.ui.adapters

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.example.resonant.utils.ImageRequestHelper
import com.example.resonant.utils.MiniPlayerColorizer
import com.example.resonant.R
import com.example.resonant.utils.Utils
import com.example.resonant.data.models.Album

class AlbumAdapter(
    private var albums: List<Album>,
    private val viewType: Int
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_SIMPLE = 0
        const val VIEW_TYPE_DETAILED = 1
        const val VIEW_TYPE_FAVORITE = 2
    }

    override fun getItemViewType(position: Int): Int = viewType

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_DETAILED -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_album_2, parent, false)
                DetailedAlbumViewHolder(view)
            }
            VIEW_TYPE_SIMPLE -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_album, parent, false)
                SimpleAlbumViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_favorite_album, parent, false)
                FavoriteAlbumViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val album = albums[position]
        when (holder) {
            is SimpleAlbumViewHolder -> holder.bind(album)
            is DetailedAlbumViewHolder -> holder.bind(album)
            is FavoriteAlbumViewHolder -> holder.bind(album) // <-- FALTA ESTO
        }
    }

    override fun getItemCount(): Int = albums.size

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        when (holder) {
            is SimpleAlbumViewHolder -> Glide.with(holder.itemView).clear(holder.albumImage)
            is DetailedAlbumViewHolder -> Glide.with(holder.itemView).clear(holder.albumImage)
            is FavoriteAlbumViewHolder -> Glide.with(holder.itemView).clear(holder.albumImage)
        }
    }

    fun submitAlbums(newAlbums: List<Album>) {
        this.albums = newAlbums
        notifyDataSetChanged()
    }

    // ViewHolder simple (grid / tarjetas pequeñas)
    inner class SimpleAlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val albumImage: ImageView = itemView.findViewById(R.id.artistImage)
        private val albumName: TextView = itemView.findViewById(R.id.albumName)
        private val artistName: TextView = itemView.findViewById(R.id.artistName)
        private val container: View = itemView.findViewById(R.id.itemContainer) // ConstraintLayout

        fun bind(album: Album) {
            albumName.text = album.title ?: "Unknown"
            artistName.text = album.artistName ?: "Unknown"

            loadAlbumCoverPalette(album.url, albumImage, container, albumName, artistName)

            itemView.setOnClickListener {
                val bundle = Bundle().apply { putString("albumId", album.id) }
                itemView.postDelayed({
                    itemView.findNavController().navigate(R.id.action_homeFragment_to_albumFragment, bundle)
                }, 200) // 200ms de delay
            }
        }
    }

    // ViewHolder detallado (lista con más datos)
    inner class DetailedAlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val albumImage: ImageView = itemView.findViewById(R.id.artistImage)
        private val albumTitle: TextView = itemView.findViewById(R.id.albumTitle)
        private val albumYear: TextView = itemView.findViewById(R.id.albumReleaseYear)
        private val albumTracks: TextView = itemView.findViewById(R.id.albumNumberOfSongs)
        private val albumDuration: TextView = itemView.findViewById(R.id.albumDuration)

        fun bind(album: Album) {
            albumTitle.text = album.title ?: "Not found"
            albumYear.text = album.releaseYear?.toString() ?: "-"
            albumTracks.text = album.numberOfTracks?.toString() ?: "-"
            albumDuration.text = Utils.formatDuration(album.duration)

            loadAlbumCover(album.url, albumImage)

            itemView.setOnClickListener {
                val bundle = Bundle().apply { putString("albumId", album.id) }
                itemView.postDelayed({
                    itemView.findNavController().navigate(R.id.action_artistFragment_to_albumFragment, bundle)
                }, 200)
            }
        }
    }

    inner class FavoriteAlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val albumImage: ImageView = itemView.findViewById(R.id.albumImage)
        private val albumName: TextView = itemView.findViewById(R.id.albumTitle)
        private val artistName: TextView = itemView.findViewById(R.id.albumArtistName)

        fun bind(album: Album) {
            albumName.text = album.title ?: "Unknown"
            artistName.text = album.artistName ?: "Unknown"

            loadAlbumCover(album.url, albumImage)

            itemView.setOnClickListener {
                val bundle = Bundle().apply { putString("albumId", album.id) }
                itemView.postDelayed({
                    itemView.findNavController().navigate(R.id.action_favoriteAlbumsFragment_to_albumFragment, bundle)
                }, 200)
            }
        }
    }

    private fun loadAlbumCover(url: String?, imageView: ImageView) {
        val placeholderRes = R.drawable.ic_album_stack

        Glide.with(imageView).clear(imageView)

        if (url.isNullOrBlank()) {
            imageView.setImageResource(placeholderRes)
            return
        }

        val model = ImageRequestHelper.buildGlideModel(imageView.context, url)

        Glide.with(imageView)
            .load(model)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .timeout(10_000) // evita “carga infinita”
            .dontAnimate()
            .placeholder(placeholderRes)
            .error(placeholderRes)
            .into(imageView)
    }

    private fun loadAlbumCoverPalette(
        url: String?,
        imageView: ImageView,
        container: View, // El ConstraintLayout, no el CardView
        albumName: TextView,
        artistName: TextView
    ) {
        val placeholderRes = R.drawable.ic_album_stack

        Glide.with(imageView).clear(imageView)

        if (url.isNullOrBlank()) {
            imageView.setImageResource(placeholderRes)
            // Aplica el colorizer con el fallback
            MiniPlayerColorizer.applyFromImageView(
                imageView,
                MiniPlayerColorizer.Targets(
                    container = container,
                    title = albumName,
                    subtitle = artistName
                ),
                fallbackColor = imageView.context.getColor(R.color.appBackgroundTheme),
                animateMillis = 400L
            )
            return
        }

        val model = ImageRequestHelper.buildGlideModel(imageView.context, url)

        Glide.with(imageView)
            .asBitmap()
            .load(model)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .timeout(10_000)
            .dontAnimate()
            .placeholder(placeholderRes)
            .error(placeholderRes)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    imageView.setImageBitmap(resource)
                    // Aplica el colorizer con el bitmap real
                    MiniPlayerColorizer.applyFromImageView(
                        imageView,
                        MiniPlayerColorizer.Targets(
                            container = container,
                            title = albumName,
                            subtitle = artistName
                        ),
                        fallbackColor = imageView.context.getColor(R.color.appBackgroundTheme),
                        animateMillis = 400L
                    )
                }
                override fun onLoadCleared(placeholder: Drawable?) {
                    imageView.setImageDrawable(placeholder)
                }
            })
    }

}