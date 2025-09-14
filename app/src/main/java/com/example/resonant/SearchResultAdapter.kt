package com.example.resonant

import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Bundle
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
import androidx.navigation.findNavController
import androidx.navigation.fragment.FragmentNavigatorExtras
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
import kotlinx.parcelize.Parcelize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections

sealed class SearchResult : Parcelable {
    @Parcelize
    data class SongItem(val song: Song) : SearchResult() {
        override val type = DataType.SONG
    }

    @Parcelize
    data class AlbumItem(val album: Album) : SearchResult() {
        override val type = DataType.ALBUM
    }

    @Parcelize
    data class ArtistItem(val artist: Artist) : SearchResult() {
        override val type = DataType.ARTIST
    }

    abstract val type: DataType
}

class SearchResultAdapter :
    ListAdapter<SearchResult, RecyclerView.ViewHolder>(SearchResultDiffCallback()) {

    companion object {
        private const val TYPE_SONG = 0
        private const val TYPE_ALBUM = 1
        private const val TYPE_ARTIST = 2
    }

    var onSongClick: ((Pair<Song, Bitmap?>) -> Unit)? = null
    private var currentPlayingId: String? = null
    private var previousPlayingId: String? = null

    var onFavoriteClick: ((Song, Boolean) -> Unit)? = null
    var favoriteSongIds: Set<String> = emptySet()
        set(value) {
            field = value
            currentList.forEachIndexed { index, result ->
                if (result is SearchResult.SongItem) {
                    val song = result.song
                    if (field.contains(song.id) != value.contains(song.id)) {
                        notifyItemChanged(index, "silent")
                    }
                }
            }
        }

    // Cache de bitmaps para canciones (para pasar a onSongClick)
    private val bitmapCache: MutableMap<String, Bitmap> = Collections.synchronizedMap(mutableMapOf())

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is SearchResult.SongItem -> TYPE_SONG
        is SearchResult.AlbumItem -> TYPE_ALBUM
        is SearchResult.ArtistItem -> TYPE_ARTIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SONG -> SongViewHolder(inf.inflate(R.layout.item_result_song, parent, false))
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

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        when (holder) {
            is SongViewHolder -> {
                holder.cancelJobs()
                Glide.with(holder.itemView).clear(holder.albumArtImageView)
                holder.albumArtAnimator?.cancel()
                holder.albumArtImageView.rotation = 0f
            }
            is AlbumViewHolder -> {
                Glide.with(holder.itemView).clear(holder.albumImage)
            }
            is ArtistViewHolder -> {
                Glide.with(holder.itemView).clear(holder.artistImage)
                holder.loadingAnimation.cancelAnimation()
                holder.loadingAnimation.visibility = View.GONE
            }
        }
    }

    // SONGS

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.songTitle)
        private val artistTextView: TextView = itemView.findViewById(R.id.songArtist)
        val albumArtImageView: ImageView = itemView.findViewById(R.id.songImage)
        private val likeButton: ImageButton = itemView.findViewById(R.id.likeButton)

        private var artworkJob: Job? = null
        private val ioScope = CoroutineScope(Dispatchers.IO)
        var albumArtAnimator: ObjectAnimator? = null

        fun cancelJobs() {
            artworkJob?.cancel()
            artworkJob = null
        }

        fun bind(song: Song) {
            // Estado del botón de like
            val isFavorite = favoriteSongIds.contains(song.id)
            likeButton.setImageResource(
                if (isFavorite) R.drawable.favorite else R.drawable.favorite_border
            )

            // Estado inicial determinista
            cancelJobs()
            Glide.with(itemView).clear(albumArtImageView)
            albumArtAnimator?.cancel()
            albumArtImageView.rotation = 0f
            albumArtImageView.visibility = View.VISIBLE

            nameTextView.text = song.title
            artistTextView.text = song.artistName ?: "Desconocido"
            nameTextView.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    if (song.id == currentPlayingId) R.color.titleSongColorWhilePlaying else R.color.white
                )
            )

            val placeholderRes = R.drawable.album_cover

            // Cache propia
            bitmapCache[song.id]?.let { cached ->
                albumArtImageView.setImageBitmap(cached)
            } ?: run {
                val albumImageUrl = song.albumImageUrl
                when {
                    !albumImageUrl.isNullOrBlank() -> {
                        // Animación de rotación mientras carga la portada remota
                        albumArtAnimator = ObjectAnimator.ofFloat(albumArtImageView, "rotation", 0f, 360f).apply {
                            duration = 3000
                            repeatCount = ObjectAnimator.INFINITE
                            interpolator = LinearInterpolator()
                            start()
                        }
                        val model = ImageRequestHelper.buildGlideModel(itemView.context, albumImageUrl)

                        Glide.with(itemView)
                            .asBitmap()
                            .load(model)
                            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                            .timeout(10_000)
                            .dontAnimate()
                            .placeholder(placeholderRes)
                            .error(placeholderRes)
                            .listener(object : RequestListener<Bitmap> {
                                override fun onLoadFailed(
                                    e: GlideException?,
                                    model: Any?,
                                    target: Target<Bitmap>,
                                    isFirstResource: Boolean
                                ): Boolean {
                                    albumArtAnimator?.cancel()
                                    albumArtImageView.rotation = 0f
                                    Log.w("SearchResultAdapter", "Song album art load failed: $model -> ${e?.rootCauses?.firstOrNull()?.message}")
                                    return false
                                }

                                override fun onResourceReady(
                                    resource: Bitmap,
                                    model: Any,
                                    target: Target<Bitmap>?,
                                    dataSource: DataSource,
                                    isFirstResource: Boolean
                                ): Boolean {
                                    albumArtAnimator?.cancel()
                                    albumArtImageView.rotation = 0f
                                    bitmapCache[song.id] = resource
                                    ioScope.launch {
                                        runCatching { Utils.saveBitmapToCache(itemView.context, resource, song.id) }
                                    }
                                    return false
                                }
                            })
                            .into(albumArtImageView)
                    }

                    !song.url.isNullOrBlank() -> {
                        albumArtAnimator = ObjectAnimator.ofFloat(albumArtImageView, "rotation", 0f, 360f).apply {
                            duration = 3000
                            repeatCount = ObjectAnimator.INFINITE
                            interpolator = LinearInterpolator()
                            start()
                        }
                        albumArtImageView.setImageResource(placeholderRes)
                        albumArtImageView.visibility = View.VISIBLE

                        val positionAtBind = bindingAdapterPosition
                        artworkJob = ioScope.launch {
                            val bitmap = withTimeoutOrNull(8_000L) {
                                Utils.getEmbeddedPictureFromUrl(itemView.context, song.url!!)
                            }
                            withContext(Dispatchers.Main) {
                                if (bindingAdapterPosition != positionAtBind) return@withContext
                                albumArtAnimator?.cancel()
                                albumArtImageView.rotation = 0f
                                if (bitmap != null) {
                                    bitmapCache[song.id] = bitmap
                                    albumArtImageView.setImageBitmap(bitmap)
                                    ioScope.launch {
                                        runCatching { Utils.saveBitmapToCache(itemView.context, bitmap, song.id) }
                                    }
                                } else {
                                    albumArtImageView.setImageResource(placeholderRes)
                                }
                            }
                        }
                    }

                    else -> {
                        albumArtImageView.setImageResource(placeholderRes)
                    }
                }
            }

            likeButton.setOnClickListener {
                val currentlyFavorite = favoriteSongIds.contains(song.id)
                val newState = !currentlyFavorite

                // Actualizar estado local inmediatamente
                favoriteSongIds = if (newState) {
                    favoriteSongIds + song.id
                } else {
                    favoriteSongIds - song.id
                }

                notifyItemChanged(bindingAdapterPosition, "silent")

                // Notificar al ViewModel
                onFavoriteClick?.invoke(song, newState)
            }

            itemView.setOnClickListener {
                val prev = currentPlayingId
                currentPlayingId = song.id

                prev?.let { prevId ->
                    val prevIndex = currentList.indexOfFirst {
                        it is SearchResult.SongItem && it.song.id == prevId
                    }
                    if (prevIndex != -1) notifyItemChanged(prevIndex, "silent")
                }
                val currIndex = currentList.indexOfFirst {
                    it is SearchResult.SongItem && it.song.id == currentPlayingId
                }
                if (currIndex != -1) notifyItemChanged(currIndex, "silent")

                val bmp = bitmapCache[song.id]
                onSongClick?.invoke(song to bmp)
            }
        }
    }

    // ALBUMS

    inner class AlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val albumTitle: TextView = itemView.findViewById(R.id.albumTitle)
        private val albumArtistName: TextView = itemView.findViewById(R.id.albumArtistName)
        val albumImage: ImageView = itemView.findViewById(R.id.albumImage)

        fun bind(album: Album) {
            albumTitle.text = album.title ?: "Not found"
            albumArtistName.text = album.artistName ?: "Desconocido"

            val placeholderRes = R.drawable.album_stack
            Glide.with(itemView).clear(albumImage)

            val url = album.url
            if (url.isNullOrBlank()) {
                albumImage.setImageResource(placeholderRes)
            } else {
                val model = ImageRequestHelper.buildGlideModel(itemView.context, url)
                Glide.with(itemView)
                    .load(model)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .timeout(10_000)
                    .dontAnimate()
                    .placeholder(placeholderRes)
                    .error(placeholderRes)
                    .into(albumImage)
            }

            itemView.setOnClickListener {
                val bundle = Bundle().apply { putString("albumId", album.id) }
                itemView.findNavController()
                    .navigate(R.id.action_searchFragment_to_albumFragment, bundle)
            }
        }
    }

    // ARTISTS

    inner class ArtistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val artistName: TextView = itemView.findViewById(R.id.artistName)
        val artistImage: ImageView = itemView.findViewById(R.id.artistImage)
        val loadingAnimation: LottieAnimationView = itemView.findViewById(R.id.loadingAnimation)

        fun bind(artist: Artist) {
            artistName.text = artist.name
            artistImage.transitionName = "artistImage_${artist.id}"

            loadingAnimation.visibility = View.VISIBLE
            artistImage.visibility = View.INVISIBLE

            Glide.with(itemView).clear(artistImage)

            val placeholderRes = R.drawable.user
            val url = artist.fileName
            if (url.isNullOrBlank()) {
                loadingAnimation.visibility = View.GONE
                artistImage.setImageResource(placeholderRes)
                artistImage.visibility = View.VISIBLE
            } else {
                val model = ImageRequestHelper.buildGlideModel(itemView.context, url)
                Glide.with(itemView)
                    .load(model)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .timeout(10_000)
                    .dontAnimate()
                    .circleCrop()
                    .placeholder(placeholderRes)
                    .error(placeholderRes)
                    .listener(object : RequestListener<Drawable> {
                        override fun onLoadFailed(
                            e: GlideException?,
                            model: Any?,
                            target: Target<Drawable>,
                            isFirstResource: Boolean
                        ): Boolean {
                            Log.w("SearchResultAdapter", "Artist image load failed: $model -> ${e?.rootCauses?.firstOrNull()?.message}")
                            loadingAnimation.visibility = View.GONE
                            artistImage.visibility = View.VISIBLE
                            return false
                        }

                        override fun onResourceReady(
                            resource: Drawable,
                            model: Any,
                            target: Target<Drawable>?,
                            dataSource: DataSource,
                            isFirstResource: Boolean
                        ): Boolean {
                            loadingAnimation.visibility = View.GONE
                            artistImage.visibility = View.VISIBLE
                            return false
                        }
                    })
                    .into(artistImage)
            }

            itemView.setOnClickListener {
                val bundle = Bundle().apply {
                    putString("artistId", artist.id)
                    putString("artistName", artist.name)
                    putString("artistImageUrl", artist.fileName)
                    putString("artistImageTransitionName", artistImage.transitionName)
                }
                val extras = FragmentNavigatorExtras(artistImage to artistImage.transitionName)
                itemView.findNavController()
                    .navigate(R.id.action_searchFragment_to_artistFragment, bundle, null, extras)
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