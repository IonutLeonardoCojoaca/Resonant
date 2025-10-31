package com.example.resonant.ui.adapters

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
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.DataType
import com.example.resonant.data.models.Song
import com.example.resonant.utils.ImageRequestHelper
import com.example.resonant.utils.Utils
import kotlinx.parcelize.Parcelize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
    androidx.recyclerview.widget.ListAdapter<SearchResult, RecyclerView.ViewHolder>(SearchResultDiffCallback()) {

    companion object {
        private const val TYPE_SONG = 0
        private const val TYPE_ALBUM = 1
        private const val TYPE_ARTIST = 2
    }

    var onSettingsClick: ((Song) -> Unit)? = null

    var onSongClick: ((Pair<Song, Bitmap?>) -> Unit)? = null
    private var currentPlayingId: String? = null
    private var previousPlayingId: String? = null

    var onFavoriteClick: ((Song, Boolean) -> Unit)? = null
    var favoriteSongIds: Set<String> = emptySet()
        set(newFavoriteIds) {
            val oldFavoriteIds = field
            field = newFavoriteIds

            val changedIds = (oldFavoriteIds - newFavoriteIds) + (newFavoriteIds - oldFavoriteIds)

            if (changedIds.isEmpty()) return

            changedIds.forEach { songId ->
                val index = currentList.indexOfFirst { it is SearchResult.SongItem && it.song.id == songId }
                if (index != -1) {
                    notifyItemChanged(index, "silent") // "silent" es un payload opcional que ya usas
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
            TYPE_SONG -> SongViewHolder(inf.inflate(_root_ide_package_.com.example.resonant.R.layout.item_result_song, parent, false))
            TYPE_ALBUM -> AlbumViewHolder(inf.inflate(_root_ide_package_.com.example.resonant.R.layout.item_result_album, parent, false))
            TYPE_ARTIST -> ArtistViewHolder(inf.inflate(_root_ide_package_.com.example.resonant.R.layout.item_result_artist, parent, false))
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
        private val nameTextView: TextView = itemView.findViewById(_root_ide_package_.com.example.resonant.R.id.songTitle)
        private val artistTextView: TextView = itemView.findViewById(_root_ide_package_.com.example.resonant.R.id.songArtist)
        val albumArtImageView: ImageView = itemView.findViewById(_root_ide_package_.com.example.resonant.R.id.songImage)
        private val likeButton: ImageButton = itemView.findViewById(_root_ide_package_.com.example.resonant.R.id.likeButton)
        private val settingsButton: ImageButton = itemView.findViewById(_root_ide_package_.com.example.resonant.R.id.featuredButton)


        private var artworkJob: Job? = null
        private val ioScope = CoroutineScope(Dispatchers.IO)
        var albumArtAnimator: ObjectAnimator? = null

        fun cancelJobs() {
            artworkJob?.cancel()
            artworkJob = null
        }

        fun bind(song: Song) {
            val isFavorite = favoriteSongIds.contains(song.id)
            likeButton.visibility = if (isFavorite) View.VISIBLE else View.INVISIBLE
            likeButton.setImageResource(if (isFavorite) _root_ide_package_.com.example.resonant.R.drawable.ic_favorite else 0)

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
                    if (song.id == currentPlayingId) _root_ide_package_.com.example.resonant.R.color.titleSongColorWhilePlaying else _root_ide_package_.com.example.resonant.R.color.white
                )
            )

            val placeholderRes = _root_ide_package_.com.example.resonant.R.drawable.ic_disc

            // Cache propia
            bitmapCache[song.id]?.let { cached ->
                albumArtImageView.setImageBitmap(cached)
            } ?: run {
                val albumImageUrl = song.coverUrl
                if (!albumImageUrl.isNullOrBlank()) {
                    // Animación de rotación mientras carga la portada remota
                    albumArtAnimator = ObjectAnimator.ofFloat(albumArtImageView, "rotation", 0f, 360f).apply {
                        duration = 3000
                        repeatCount = ObjectAnimator.INFINITE
                        interpolator = LinearInterpolator()
                        start()
                    }

                    Glide.with(itemView)
                        .asBitmap()
                        .load(albumImageUrl) // <-- usar coverUrl, no imageFileName
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
                                Log.w(
                                    "SearchResultAdapter",
                                    "Song album art load failed: $model -> ${e?.rootCauses?.firstOrNull()?.message}"
                                )
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
                } else {
                    albumArtImageView.setImageResource(placeholderRes)
                }

            }


            settingsButton.setOnClickListener {
                onSettingsClick?.invoke(song)
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
        private val albumTitle: TextView = itemView.findViewById(_root_ide_package_.com.example.resonant.R.id.albumTitle)
        private val albumArtistName: TextView = itemView.findViewById(_root_ide_package_.com.example.resonant.R.id.albumArtistName)
        val albumImage: ImageView = itemView.findViewById(_root_ide_package_.com.example.resonant.R.id.albumImage)

        fun bind(album: Album) {
            albumTitle.text = album.title ?: "Not found"
            albumArtistName.text = album.artistName ?: "Desconocido"

            val placeholderRes = _root_ide_package_.com.example.resonant.R.drawable.ic_album_stack
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
                    .navigate(_root_ide_package_.com.example.resonant.R.id.action_searchFragment_to_albumFragment, bundle)
            }
        }
    }

    // ARTISTS

    inner class ArtistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val artistName: TextView = itemView.findViewById(_root_ide_package_.com.example.resonant.R.id.artistName)
        val artistImage: ImageView = itemView.findViewById(_root_ide_package_.com.example.resonant.R.id.artistImage)
        val loadingAnimation: LottieAnimationView = itemView.findViewById(_root_ide_package_.com.example.resonant.R.id.loadingAnimation)

        fun bind(artist: Artist) {
            artistName.text = artist.name
            artistImage.transitionName = "artistImage_${artist.id}"

            loadingAnimation.visibility = View.VISIBLE
            artistImage.visibility = View.INVISIBLE

            Glide.with(itemView).clear(artistImage)

            val placeholderRes = _root_ide_package_.com.example.resonant.R.drawable.ic_user
            val url =  artist.url
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
                    putString("artistImageUrl", artist.url)
                    putString("artistImageTransitionName", artistImage.transitionName)
                }
                val extras = FragmentNavigatorExtras(artistImage to artistImage.transitionName)
                itemView.findNavController()
                    .navigate(_root_ide_package_.com.example.resonant.R.id.action_searchFragment_to_artistFragment, bundle, null, extras)
            }
        }
    }

// En SearchResultAdapter.kt

    fun setCurrentPlayingSong(songId: String?) {
        if (currentPlayingId == songId) return

        previousPlayingId = currentPlayingId
        currentPlayingId = songId

        previousPlayingId?.let { prev ->
            val prevIndex = currentList.indexOfFirst { it is SearchResult.SongItem && it.song.id == prev }
            if (prevIndex != -1) {
                notifyItemChanged(prevIndex, "silent")
            }
        }
        currentPlayingId?.let { curr ->
            val currIndex = currentList.indexOfFirst { it is SearchResult.SongItem && it.song.id == curr }
            if (currIndex != -1) {
                notifyItemChanged(currIndex, "silent")
            }
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