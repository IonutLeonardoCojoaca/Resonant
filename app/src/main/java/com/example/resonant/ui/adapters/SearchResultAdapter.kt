package com.example.resonant.ui.adapters

import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Parcelable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.example.resonant.R
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.DataType
import com.example.resonant.data.models.Song
import com.example.resonant.utils.ImageRequestHelper
import com.example.resonant.utils.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import java.util.Collections

sealed class SearchResult : Parcelable {
    @Parcelize data class SongItem(val song: Song) : SearchResult() { override val type = DataType.SONG }
    @Parcelize data class AlbumItem(val album: Album) : SearchResult() { override val type = DataType.ALBUM }
    @Parcelize data class ArtistItem(val artist: Artist) : SearchResult() { override val type = DataType.ARTIST }
    abstract val type: DataType
}

class SearchResultAdapter : ListAdapter<SearchResult, RecyclerView.ViewHolder>(SearchResultDiffCallback()) {

    companion object {
        private const val TYPE_SONG = 0
        private const val TYPE_ALBUM = 1
        private const val TYPE_ARTIST = 2
    }

    // --- CALLBACKS DE CLIC ---
    var onSettingsClick: ((Song) -> Unit)? = null
    var onFavoriteClick: ((Song, Boolean) -> Unit)? = null
    var onSongClick: ((Pair<Song, Bitmap?>) -> Unit)? = null
    var onAlbumClick: ((Album) -> Unit)? = null
    var onArtistClick: ((Artist, ImageView) -> Unit)? = null
    
    // Settings callbacks
    var onAlbumSettingsClick: ((Album) -> Unit)? = null
    var onArtistSettingsClick: ((Artist) -> Unit)? = null

    // --- FAVORITOS ---
    private var _favoriteSongIds: Set<String> = emptySet()
    var favoriteSongIds: Set<String>
        get() = _favoriteSongIds
        set(newFavoriteIds) {
            val oldFavoriteIds = _favoriteSongIds
            _favoriteSongIds = newFavoriteIds
            val changedIds = (oldFavoriteIds - newFavoriteIds) + (newFavoriteIds - oldFavoriteIds)
            if (changedIds.isEmpty()) return
            changedIds.forEach { songId ->
                val index = currentList.indexOfFirst { it is SearchResult.SongItem && it.song.id == songId }
                if (index != -1) notifyItemChanged(index, "silent")
            }
        }

    // --- 1. NUEVA VARIABLE PARA IDS DESCARGADOS ---
    var downloadedSongIds: Set<String> = emptySet()
        set(value) {
            field = value
            notifyDataSetChanged() // Refrescamos la lista para actualizar los iconos
        }

    private var currentPlayingId: String? = null
    private var previousPlayingId: String? = null
    private val bitmapCache: MutableMap<String, Bitmap> = Collections.synchronizedMap(mutableMapOf())

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is SearchResult.SongItem -> TYPE_SONG
        is SearchResult.AlbumItem -> TYPE_ALBUM
        is SearchResult.ArtistItem -> TYPE_ARTIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SONG -> SongViewHolder(inf.inflate(R.layout.item_result_song, parent, false)) // Asegúrate de que este XML tiene el ID downloadedIcon
            TYPE_ALBUM -> AlbumViewHolder(inf.inflate(R.layout.item_result_album, parent, false))
            TYPE_ARTIST -> ArtistViewHolder(inf.inflate(R.layout.item_result_artist, parent, false))
            else -> error("Unknown viewType: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is SearchResult.SongItem -> (holder as SongViewHolder).bind(item.song)
            is SearchResult.AlbumItem -> (holder as AlbumViewHolder).bind(item.album)
            is SearchResult.ArtistItem -> (holder as ArtistViewHolder).bind(item.artist)
        }
    }
    
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
             when (val item = getItem(position)) {
                is SearchResult.SongItem -> (holder as SongViewHolder).bind(item.song, partial = true)
                else -> onBindViewHolder(holder, position)
            }
        } else {
            onBindViewHolder(holder, position)
        }
    }


    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        when (holder) {
            is SongViewHolder -> {
                holder.cancelJobs()
                Glide.with(holder.itemView).clear(holder.albumArtImageView)
                holder.albumArtImageView.rotation = 0f
            }
            is AlbumViewHolder -> Glide.with(holder.itemView).clear(holder.albumImage)
            is ArtistViewHolder -> {
                Glide.with(holder.itemView).clear(holder.artistImage)
                holder.loadingAnimation.cancelAnimation()
                holder.loadingAnimation.visibility = View.GONE
            }
        }
    }

    // --- SONG VIEW HOLDER ---
    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.songTitle)
        private val artistTextView: TextView = itemView.findViewById(R.id.songArtist)
        val albumArtImageView: ImageView = itemView.findViewById(R.id.songImage)
        private val likeButton: ImageButton = itemView.findViewById(R.id.likeButton)
        private val settingsButton: ImageButton = itemView.findViewById(R.id.featuredButton)

        // --- 2. REFERENCIA AL ICONO DE DESCARGA ---
        // (Asegúrate de haber actualizado el XML item_result_song con este ID)
        private val downloadedIcon: ImageView = itemView.findViewById(R.id.downloadedIcon)

        private var artworkJob: Job? = null
        private val ioScope = CoroutineScope(Dispatchers.IO)


        fun cancelJobs() { artworkJob?.cancel(); artworkJob = null }

        fun bind(song: Song, partial: Boolean = false) {
            // --- 3. LÓGICA DE VISIBILIDAD DE DESCARGA ---
            val isDownloaded = downloadedSongIds.contains(song.id)
            if (isDownloaded) {
                downloadedIcon.visibility = View.VISIBLE
            } else {
                downloadedIcon.visibility = View.GONE
            }

            val isFavorite = _favoriteSongIds.contains(song.id)
            likeButton.visibility = if (isFavorite) View.VISIBLE else View.INVISIBLE
            likeButton.setImageResource(if (isFavorite) R.drawable.ic_favorite else 0)

            nameTextView.setTextColor(ContextCompat.getColor(itemView.context, if (song.id == currentPlayingId) R.color.titleSongColorWhilePlaying else R.color.white))

            if (!partial) {
                nameTextView.text = song.title ?: "Desconocido"
                artistTextView.text = song.artistName ?: "Desconocido"

                cancelJobs()
                albumArtImageView.visibility = View.VISIBLE

                Glide.with(itemView).clear(albumArtImageView)

                val placeholderRes = R.drawable.ic_disc
                val url = song.coverUrl

                if (!url.isNullOrBlank()) {
                    Glide.with(itemView.context)
                        .asBitmap()
                        .load(url)
                        .override(200, 200) // Optimization: resize
                        .dontAnimate()
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .placeholder(placeholderRes)
                        .error(placeholderRes)
                        .into(albumArtImageView)
                } else {
                    albumArtImageView.setImageResource(placeholderRes)
                }
            }

            settingsButton.setOnClickListener { onSettingsClick?.invoke(song) }
            likeButton.setOnClickListener {
                val newState = !_favoriteSongIds.contains(song.id)
                _favoriteSongIds = if (newState) _favoriteSongIds + song.id else _favoriteSongIds - song.id
                notifyItemChanged(bindingAdapterPosition, "silent")
                onFavoriteClick?.invoke(song, newState)
            }
            itemView.setOnClickListener {
                val prev = currentPlayingId; currentPlayingId = song.id
                prev?.let { p -> notifyItemChanged(currentList.indexOfFirst { it is SearchResult.SongItem && it.song.id == p }, "silent") }
                notifyItemChanged(currentList.indexOfFirst { it is SearchResult.SongItem && it.song.id == currentPlayingId }, "silent")
                onSongClick?.invoke(song to bitmapCache[song.id])
            }
        }
    }

    // --- ALBUM VIEW HOLDER ---
    inner class AlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val albumTitle: TextView = itemView.findViewById(R.id.albumTitle)
        private val albumArtistName: TextView = itemView.findViewById(R.id.albumArtistName)
        val albumImage: ImageView = itemView.findViewById(R.id.albumImage)
        private val settingsButton: ImageButton = itemView.findViewById(R.id.settingsButton)

        fun bind(album: Album) {
            albumTitle.text = album.title ?: "Not found"
            albumArtistName.text = album.artistName ?: "Desconocido"
            val placeholderRes = R.drawable.ic_album_stack
            Glide.with(itemView).clear(albumImage)
            val url = album.url
            if (url.isNullOrBlank()) { albumImage.setImageResource(placeholderRes) }
            else {
                val model = ImageRequestHelper.buildGlideModel(itemView.context, url)
                Glide.with(itemView).load(model).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).timeout(10_000).dontAnimate().placeholder(placeholderRes).error(placeholderRes).into(albumImage)
            }

            settingsButton.setOnClickListener {
                onAlbumSettingsClick?.invoke(album)
            }
            itemView.setOnLongClickListener {
                onAlbumSettingsClick?.invoke(album)
                true
            }
            itemView.setOnClickListener {
                onAlbumClick?.invoke(album)
            }
        }
    }

    // --- ARTIST VIEW HOLDER ---
    inner class ArtistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val artistName: TextView = itemView.findViewById(R.id.artistName)
        val artistImage: ImageView = itemView.findViewById(R.id.artistImage)
        val loadingAnimation: LottieAnimationView = itemView.findViewById(R.id.loadingAnimation)
        private val settingsButton: ImageButton = itemView.findViewById(R.id.settingsButton)

        fun bind(artist: Artist) {
            artistName.text = artist.name
            artistImage.transitionName = "artistImage_${artist.id}"
            loadingAnimation.visibility = View.VISIBLE; artistImage.visibility = View.INVISIBLE
            Glide.with(itemView).clear(artistImage)
            val placeholderRes = R.drawable.ic_user
            val url = artist.url
            if (url.isNullOrBlank()) {
                loadingAnimation.visibility = View.GONE; artistImage.setImageResource(placeholderRes); artistImage.visibility = View.VISIBLE
            } else {
                val model = ImageRequestHelper.buildGlideModel(itemView.context, url)
                Glide.with(itemView).load(model).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).timeout(10_000).dontAnimate().circleCrop().placeholder(placeholderRes).error(placeholderRes)
                    .listener(object : RequestListener<Drawable> {
                        override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                            loadingAnimation.visibility = View.GONE; artistImage.visibility = View.VISIBLE; return false
                        }
                        override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                            loadingAnimation.visibility = View.GONE; artistImage.visibility = View.VISIBLE; return false
                        }
                    }).into(artistImage)
            }

            settingsButton.setOnClickListener {
                onArtistSettingsClick?.invoke(artist)
            }
            itemView.setOnLongClickListener {
                onArtistSettingsClick?.invoke(artist)
                true
            }
            itemView.setOnClickListener {
                onArtistClick?.invoke(artist, artistImage)
            }
        }
    }

    fun setCurrentPlayingSong(songId: String?) {
        if (currentPlayingId == songId) return
        previousPlayingId = currentPlayingId
        currentPlayingId = songId
        previousPlayingId?.let { prev ->
            val prevIndex = currentList.indexOfFirst { it is SearchResult.SongItem && it.song.id == prev }
            if (prevIndex != -1) notifyItemChanged(prevIndex, "silent")
        }
        currentPlayingId?.let { curr ->
            val currIndex = currentList.indexOfFirst { it is SearchResult.SongItem && it.song.id == curr }
            if (currIndex != -1) notifyItemChanged(currIndex, "silent")
        }
    }
}

private class SearchResultDiffCallback : DiffUtil.ItemCallback<SearchResult>() {
    override fun areItemsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean {
        return when {
            oldItem is SearchResult.SongItem && newItem is SearchResult.SongItem -> oldItem.song.id == newItem.song.id
            oldItem is SearchResult.AlbumItem && newItem is SearchResult.AlbumItem -> oldItem.album.id == newItem.album.id
            oldItem is SearchResult.ArtistItem && newItem is SearchResult.ArtistItem -> oldItem.artist.id == newItem.artist.id
            else -> false
        }
    }
    override fun areContentsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean {
        return oldItem == newItem
    }
}