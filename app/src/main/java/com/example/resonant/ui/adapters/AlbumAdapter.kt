package com.example.resonant.ui.adapters

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.navigation.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
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
    albums: List<Album>,
    private val viewType: Int
) : ListAdapter<Album, RecyclerView.ViewHolder>(AlbumDiffCallback()) {

    init {
        setHasStableIds(true)
        if (albums.isNotEmpty()) submitList(albums)
    }

    var onSettingsClick: ((Album) -> Unit)? = null // New callback
    var onAlbumClick: ((Album) -> Unit)? = null

    /** When > 0, overrides the item root width (use for horizontal RecyclerViews) */
    var itemWidthOverride: Int = 0

    companion object {
        const val VIEW_TYPE_SIMPLE = 0
        const val VIEW_TYPE_DETAILED = 1
        const val VIEW_TYPE_FAVORITE = 2
        const val VIEW_TYPE_FEATURED = 3
    }

    override fun getItemViewType(position: Int): Int = viewType

    override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

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
        val album = getItem(position)
        when (holder) {
            is SimpleAlbumViewHolder -> {
                if (itemWidthOverride > 0) {
                    holder.itemView.layoutParams = holder.itemView.layoutParams
                        ?.also { it.width = itemWidthOverride }
                        ?: ViewGroup.LayoutParams(itemWidthOverride, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                holder.bind(album)
            }
            is DetailedAlbumViewHolder -> holder.bind(album)
            is FavoriteAlbumViewHolder -> holder.bind(album)
            is FeaturedAlbumViewHolder -> holder.bind(album)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        when (holder) {
            is SimpleAlbumViewHolder -> Glide.with(holder.itemView).clear(holder.albumImage)
            is DetailedAlbumViewHolder -> Glide.with(holder.itemView).clear(holder.albumImage)
            is FavoriteAlbumViewHolder -> Glide.with(holder.albumImage).clear(holder.albumImage)
            is FeaturedAlbumViewHolder -> Glide.with(holder.albumImage).clear(holder.albumImage)
        }
    }

    /** [submitAlbums] and [updateList] are kept as aliases of [submitList] for source compatibility. */
    fun submitAlbums(newAlbums: List<Album>) = submitList(newAlbums)

    // ViewHolder simple (grid / tarjetas pequeñas)
    inner class SimpleAlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val albumImage: ImageView = itemView.findViewById(R.id.artistImage)
        private val albumName: TextView = itemView.findViewById(R.id.albumName)
        private val artistName: TextView = itemView.findViewById(R.id.artistName)
        private val btnSettings: View? = itemView.findViewById(R.id.btnSettings)
        private val container: View? = itemView.findViewById(R.id.itemContainer) ?: itemView.findViewById(R.id.albumRoot) ?: itemView

        fun bind(album: Album) {
            albumName.text = album.title ?: "Unknown"
            
            // Fix: Use artist from inner list if artistName is null
            val displayedArtistName = album.artistName ?: album.artists.joinToString(", ") { it.name }.takeIf { it.isNotEmpty() } ?: "Unknown"
            artistName.text = displayedArtistName

            loadAlbumCover(album.url, albumImage, null, null, null)

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
        private val container: View? = itemView.findViewById(R.id.itemContainer) ?: itemView.findViewById(R.id.albumRoot) ?: itemView

        fun bind(album: Album) {
            albumTitle.text = album.title ?: "Not found"
            
            val displayedArtistName = album.artistName ?: album.artists.joinToString(", ") { it.name }.takeIf { it.isNotEmpty() } ?: "Unknown Artist"
            artistName.text = displayedArtistName
            
            albumYear.text = album.releaseYear?.toString() ?: "-"
            val trackCount = album.numberOfTracks ?: 0
            albumTracks.text = "$trackCount canciones"
            albumType.text = if (trackCount > 6) "Álbum" else "EP/Single"
            loadAlbumCover(album.url, albumImage, container, albumTitle, artistName)
            
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
        private val settingsButton: View? = itemView.findViewById(R.id.settingsButton)
        private val container: View? = itemView.findViewById(R.id.albumDataContainer) ?: itemView.findViewById(R.id.itemContainer) ?: itemView.findViewById(R.id.albumRoot) ?: itemView

        fun bind(album: Album) {
            albumName.text = album.title ?: "Unknown"
            
            val displayedArtistName = album.artistName ?: album.artists.joinToString(", ") { it.name }.takeIf { it.isNotEmpty() } ?: "Unknown"
            artistName.text = displayedArtistName
            
            loadAlbumCover(album.url, albumImage, container, albumName, artistName)
            
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
        private val container: View? = itemView.findViewById(R.id.itemContainer) ?: itemView.findViewById(R.id.albumRoot) ?: itemView

        fun bind(album: Album) {
            albumName.text = album.title ?: "Sin título"
            trackCount.text = (album.numberOfTracks ?: 0).toString()

            // Lógica simple para la etiqueta: Si es de este año o anterior = Nuevo
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            val isNew = (album.releaseYear ?: 0) >= (currentYear - 1)

            if (isNew) {
                featuredTag.text = "Último lanzamiento"
            } else {
                featuredTag.text = "Álbum destacado"
            }

            // El título/track-count viven ahora sobre el scrim de la portada
            // (captionScrim en item_featured_album.xml), no sobre el fondo
            // plano de itemContainer, así que no se pasan como titleView/
            // subtitleView: MiniPlayerColorizer calcularía su contraste
            // contra el color de itemContainer, que ya no es lo que hay
            // detrás del texto. El contenedor sigue tiñéndose (container),
            // el texto se queda blanco fijo (ya legible sobre el scrim).
            // El destacado se muestra a mucho más tamaño que el resto de
            // tarjetas de este mismo adapter, así que pide una descarga/
            // decodificación de mayor resolución en vez de compartir el
            // override(200,200) pensado para miniaturas de grid.
            loadAlbumCover(album.url, albumImage, container, null, null, highQuality = true)

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

    private fun loadAlbumCover(
        url: String?,
        imageView: ImageView,
        containerView: View? = null,
        titleView: TextView? = null,
        subtitleView: TextView? = null,
        highQuality: Boolean = false
    ) {
        val placeholderRes = R.drawable.ic_album_stack
        Glide.with(imageView).clear(imageView)

        val fallbackColor = ContextCompat.getColor(imageView.context, R.color.cardsTheme)

        if (containerView != null) {
            MiniPlayerColorizer.applyFromImageView(
                imageView = imageView,
                targets = MiniPlayerColorizer.Targets(
                    container = containerView,
                    title = titleView,
                    subtitle = subtitleView
                ),
                fallbackColor = fallbackColor,
                animateMillis = 0L
            )
        }

        if (url.isNullOrBlank()) {
            imageView.setImageResource(placeholderRes)
            return
        }

        val model = ImageRequestHelper.buildGlideModel(imageView.context, url)
        val overrideSize = if (highQuality) 720 else 200

        Glide.with(imageView)
            .asBitmap()
            .load(model)
            .override(overrideSize, overrideSize)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .timeout(10_000)
            .dontAnimate()
            .placeholder(placeholderRes)
            .error(placeholderRes)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    imageView.setImageBitmap(resource)
                    if (containerView != null) {
                        MiniPlayerColorizer.applyFromImageView(
                            imageView = imageView,
                            targets = MiniPlayerColorizer.Targets(
                                container = containerView,
                                title = titleView,
                                subtitle = subtitleView
                            ),
                            fallbackColor = fallbackColor,
                            animateMillis = 300L
                        )
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    imageView.setImageDrawable(placeholder)
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    imageView.setImageDrawable(errorDrawable)
                }
            })
    }

    fun updateList(newList: List<Album>) = submitList(newList)

    class AlbumDiffCallback : DiffUtil.ItemCallback<Album>() {
        override fun areItemsTheSame(oldItem: Album, newItem: Album): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Album, newItem: Album): Boolean = oldItem == newItem
    }
}
