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
import com.example.resonant.data.models.Album
import java.util.Calendar

class AlbumAdapter(
    private var albums: List<Album>,
    private val viewType: Int
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var onSettingsClick: ((Album) -> Unit)? = null // New callback
    var onAlbumClick: ((Album) -> Unit)? = null

    companion object {
        const val VIEW_TYPE_SIMPLE = 0
        const val VIEW_TYPE_DETAILED = 1
        const val VIEW_TYPE_FAVORITE = 2
        const val VIEW_TYPE_FEATURED = 3
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
            VIEW_TYPE_FEATURED -> { // <--- INFLAMOS TU XML NUEVO
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_featured_album, parent, false)
                FeaturedAlbumViewHolder(view)
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
            is FavoriteAlbumViewHolder -> holder.bind(album)
            is FeaturedAlbumViewHolder -> holder.bind(album) // <--- BINDING NUEVO
        }
    }

    override fun getItemCount(): Int = albums.size

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        when (holder) {
            is SimpleAlbumViewHolder -> Glide.with(holder.itemView).clear(holder.albumImage)
            is DetailedAlbumViewHolder -> Glide.with(holder.itemView).clear(holder.albumImage)
            is FavoriteAlbumViewHolder -> Glide.with(holder.itemView).clear(holder.albumImage)
            is FeaturedAlbumViewHolder -> Glide.with(holder.itemView).clear(holder.albumImage)
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
        private val container: View = itemView.findViewById(R.id.itemContainer)
        private val btnSettings: View? = itemView.findViewById(R.id.btnSettings)

        fun bind(album: Album) {
            albumName.text = album.title ?: "Unknown"
            
            // Fix: Use artist from inner list if artistName is null
            val displayedArtistName = album.artistName ?: album.artists.joinToString(", ") { it.name }.takeIf { it.isNotEmpty() } ?: "Unknown"
            artistName.text = displayedArtistName

            loadAlbumCoverPalette(album.url, albumImage, container, albumName, artistName)

            btnSettings?.visibility = View.VISIBLE
            btnSettings?.setOnClickListener {
                onSettingsClick?.invoke(album)
            }

            itemView.setOnClickListener {
                onAlbumClick?.invoke(album) ?: run {
                    val bundle = Bundle().apply { putString("albumId", album.id) }
                    itemView.postDelayed({
                        itemView.findNavController().navigate(R.id.albumFragment, bundle)
                    }, 200)
                }
            }
            
            itemView.setOnLongClickListener {
                onSettingsClick?.invoke(album)
                true
            }
        }
    }

    // ViewHolder detallado (lista con más datos)
    inner class DetailedAlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val albumImage: ImageView = itemView.findViewById(R.id.albumCover)
        private val albumTitle: TextView = itemView.findViewById(R.id.albumTitle)
        private val artistName: TextView = itemView.findViewById(R.id.artistName)
        private val albumType: TextView = itemView.findViewById(R.id.albumType)
        val albumYear: TextView = itemView.findViewById(R.id.albumYear)
        val albumTracks: TextView = itemView.findViewById(R.id.albumTrackCount)
        private val btnSettings: View? = itemView.findViewById(R.id.btnSettings)

        fun bind(album: Album) {
            albumTitle.text = album.title ?: "Not found"
            
            val displayedArtistName = album.artistName ?: album.artists.joinToString(", ") { it.name }.takeIf { it.isNotEmpty() } ?: "Unknown Artist"
            artistName.text = displayedArtistName
            
            albumYear.text = album.releaseYear?.toString() ?: "-"
            val trackCount = album.numberOfTracks ?: 0
            albumTracks.text = "$trackCount canciones"
            albumType.text = if (trackCount > 6) "Álbum" else "EP/Single"
            loadAlbumCover(album.url, albumImage)
            
            btnSettings?.visibility = View.VISIBLE
            btnSettings?.setOnClickListener {
                onSettingsClick?.invoke(album)
            }

            itemView.setOnClickListener {
                onAlbumClick?.invoke(album) ?: run {
                    val bundle = Bundle().apply { putString("albumId", album.id) }
                    itemView.postDelayed({
                        itemView.findNavController().navigate(R.id.albumFragment, bundle)
                    }, 200)
                }
            }
            
             itemView.setOnLongClickListener {
                onSettingsClick?.invoke(album)
                true
            }
        }
    }

    // ViewHolder FAVORITO
    inner class FavoriteAlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val albumImage: ImageView = itemView.findViewById(R.id.albumImage)
        private val albumName: TextView = itemView.findViewById(R.id.albumTitle)
        private val artistName: TextView = itemView.findViewById(R.id.albumArtistName)
        private val settingsButton: View? = itemView.findViewById(R.id.settingsButton) // Changed to View? to use findViewById

        fun bind(album: Album) {
            albumName.text = album.title ?: "Unknown"
            
            val displayedArtistName = album.artistName ?: album.artists.joinToString(", ") { it.name }.takeIf { it.isNotEmpty() } ?: "Unknown"
            artistName.text = displayedArtistName
            
            loadAlbumCover(album.url, albumImage)
            
            settingsButton?.visibility = View.VISIBLE
            settingsButton?.setOnClickListener {
                onSettingsClick?.invoke(album)
            }
            
             itemView.setOnLongClickListener {
                onSettingsClick?.invoke(album)
                true
            }
        }
    }

    // --- NUEVO: FEATURED ALBUM VIEWHOLDER ---
    // Usamos los IDs de tu nuevo XML item_features_album.xml
    inner class FeaturedAlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val albumImage: ImageView = itemView.findViewById(R.id.artistImage) // El ID en tu XML es artistImage
        private val albumName: TextView = itemView.findViewById(R.id.albumName)
        private val trackCount: TextView = itemView.findViewById(R.id.textView) // El ID de "0" en tu XML
        private val featuredTag: TextView = itemView.findViewById(R.id.featuredTag) // El ID añadido al primer TextView

        fun bind(album: Album) {
            albumName.text = album.title ?: "Sin título"
            trackCount.text = (album.numberOfTracks ?: 0).toString()

            // Lógica simple para la etiqueta: Si es de este año o anterior = Nuevo
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            val isNew = (album.releaseYear ?: 0) >= (currentYear - 1)

            if (isNew) {
                featuredTag.text = "Último lanzamiento"
                // Opcional: Cambiar background tint si tienes drawables distintos
                // featuredTag.setBackgroundResource(R.drawable.bg_rounded_secondary)
            } else {
                featuredTag.text = "Álbum destacado"
                // featuredTag.setBackgroundResource(R.drawable.bg_rounded_gold) // Ejemplo si tuvieras otro color
            }

            // Usamos carga normal (sin Palette) para mejor rendimiento en items grandes
            loadAlbumCover(album.url, albumImage)

            itemView.setOnClickListener {
                onAlbumClick?.invoke(album) ?: run {
                    val bundle = Bundle().apply { putString("albumId", album.id) }
                    itemView.findNavController().navigate(R.id.albumFragment, bundle)
                }
            }
            
             itemView.setOnLongClickListener {
                onSettingsClick?.invoke(album)
                true
            }
        }
    }

    // --- HELPERS DE CARGA ---

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
            .timeout(10_000)
            .dontAnimate()
            .placeholder(placeholderRes)
            .error(placeholderRes)
            .into(object : CustomTarget<Drawable>() {
                 override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                     imageView.setImageDrawable(resource)
                 }
                 override fun onLoadCleared(placeholder: Drawable?) {
                     imageView.setImageDrawable(placeholder)
                 }
            })
    }

    private fun loadAlbumCoverPalette(
        url: String?,
        imageView: ImageView,
        container: View,
        albumName: TextView,
        artistName: TextView
    ) {
        val placeholderRes = R.drawable.ic_album_stack
        Glide.with(imageView).clear(imageView)

        if (url.isNullOrBlank()) {
            imageView.setImageResource(placeholderRes)
            MiniPlayerColorizer.applyFromImageView(
                imageView,
                MiniPlayerColorizer.Targets(container = container, title = albumName, subtitle = artistName),
                fallbackColor = imageView.context.getColor(R.color.primaryColorTheme),
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
                    MiniPlayerColorizer.applyFromImageView(
                        imageView,
                        MiniPlayerColorizer.Targets(container = container, title = albumName, subtitle = artistName),
                        fallbackColor = imageView.context.getColor(R.color.primaryColorTheme),
                        animateMillis = 400L
                    )
                }
                override fun onLoadCleared(placeholder: Drawable?) {
                    imageView.setImageDrawable(placeholder)
                }
            })
    }

    fun updateList(newList: List<Album>) {
        this.albums = newList
        notifyDataSetChanged()
    }
}