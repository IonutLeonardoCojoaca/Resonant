package com.example.resonant

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
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
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections

class SongAdapter : ListAdapter<Song, SongAdapter.SongViewHolder>(SongDiffCallback()) {

    var onItemClick: ((Pair<Song, Bitmap?>) -> Unit)? = null
    private var currentPlayingId: String? = null
    private var previousPlayingId: String? = null
    val bitmapCache: MutableMap<String, Bitmap> = Collections.synchronizedMap(mutableMapOf())
    var onFavoriteClick: ((Song, Boolean) -> Unit)? = null
    var favoriteSongIds: Set<String> = emptySet()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            currentList.forEachIndexed { index, song ->
                    if (field.contains(song.id) != value.contains(song.id)) {
                         notifyItemChanged(index, "silent")
                     }
                }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(getItem(position), partial = false)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            holder.bind(getItem(position), partial = true)
        }
    }

    override fun onViewRecycled(holder: SongViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelJobs()
        // Cancela cualquier carga pendiente de Glide asociada a esta ImageView
        Glide.with(holder.itemView).clear(holder.albumArtImageView)
        holder.albumArtAnimator?.cancel()
        holder.albumArtImageView.rotation = 0f
    }

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val nameTextView: TextView = itemView.findViewById(R.id.songTitle)
        private val artistTextView: TextView = itemView.findViewById(R.id.songArtist)
        private val streamsTextView: TextView = itemView.findViewById(R.id.songStreams)
        private val likeButton: ImageButton = itemView.findViewById(R.id.likeButton)
        val albumArtImageView: ImageView = itemView.findViewById(R.id.songImage)
        var albumArtAnimator: ObjectAnimator? = null

        private var artworkJob: Job? = null
        private val ioScope = CoroutineScope(Dispatchers.IO)


        fun cancelJobs() {
            artworkJob?.cancel()
            artworkJob = null
        }

        fun bind(song: Song, partial: Boolean = false) {
            nameTextView.text = song.title
            artistTextView.text = song.artistName ?: "Desconocido"
            streamsTextView.text = song.streams.toString()

            val isFavorite = favoriteSongIds.contains(song.id)

            likeButton.setImageResource(
                if (isFavorite) R.drawable.favorite else R.drawable.favorite_border
            )

            nameTextView.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    if (song.id == currentPlayingId) R.color.titleSongColorWhilePlaying else R.color.white
                )
            )

            if (!partial) {
                cancelJobs()
                // Detén animación previa y resetea rotación
                albumArtAnimator?.cancel()
                albumArtImageView.rotation = 0f
                albumArtImageView.visibility = View.VISIBLE

                Glide.with(itemView).clear(albumArtImageView)

                val placeholderRes = R.drawable.album_cover
                val errorRes = R.drawable.album_cover

                bitmapCache[song.id]?.let { cached ->
                    albumArtImageView.setImageBitmap(cached)
                } ?: run {
                    when {
                        !song.albumImageUrl.isNullOrBlank() -> {
                            albumArtAnimator = ObjectAnimator.ofFloat(albumArtImageView, "rotation", 0f, 360f).apply {
                                duration = 3000
                                repeatCount = ObjectAnimator.INFINITE
                                interpolator = LinearInterpolator()
                                start()
                            }

                            val model = ImageRequestHelper.buildGlideModel(itemView.context, song.albumImageUrl!!)
                            Glide.with(itemView)
                                .asBitmap()
                                .load(model)
                                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                                .timeout(10_000) // 10s
                                .dontAnimate()
                                .placeholder(placeholderRes)
                                .error(errorRes)
                                .listener(object : com.bumptech.glide.request.RequestListener<Bitmap> {
                                    override fun onLoadFailed(
                                        e: GlideException?,
                                        model: Any?,
                                        target: Target<Bitmap>,
                                        isFirstResource: Boolean
                                    ): Boolean {
                                        albumArtAnimator?.cancel()
                                        albumArtImageView.rotation = 0f
                                        Log.w("SongAdapter", "Album art load failed: $model -> ${e?.rootCauses?.firstOrNull()?.message}")
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
                                        albumArtImageView.setImageResource(errorRes)
                                    }
                                }
                            }
                        }

                        else -> {
                            albumArtImageView.setImageResource(placeholderRes)
                        }
                    }
                }
            }

            likeButton.setOnClickListener {
                val currentlyFavorite = favoriteSongIds.contains(song.id)
                val newState = !currentlyFavorite

                favoriteSongIds = if (newState) {
                    favoriteSongIds + song.id
                } else {
                    favoriteSongIds - song.id
                }

                onFavoriteClick?.invoke(song, currentlyFavorite)
            }


            itemView.setOnClickListener {
                val previousId = currentPlayingId
                currentPlayingId = song.id

                previousId?.let { prev ->
                    val prevIndex = currentList.indexOfFirst { it.id == prev }
                    if (prevIndex != -1) notifyItemChanged(prevIndex, "silent")
                }

                val currentIndex = currentList.indexOfFirst { it.id == currentPlayingId }
                if (currentIndex != -1) notifyItemChanged(currentIndex, "silent")

                val bitmap = bitmapCache[song.id]
                onItemClick?.invoke(song to bitmap)
            }
        }

    }

    class SongDiffCallback : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(oldItem: Song, newItem: Song): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Song, newItem: Song): Boolean = oldItem == newItem
    }

    fun setCurrentPlayingSong(songId: String?) {
        if (currentPlayingId == songId) return

        previousPlayingId = currentPlayingId
        currentPlayingId = songId

        previousPlayingId?.let { prev ->
            val prevIndex = currentList.indexOfFirst { it.id == prev }
            if (prevIndex != -1) notifyItemChanged(prevIndex, "silent")
        }

        currentPlayingId?.let { curr ->
            val currIndex = currentList.indexOfFirst { it.id == curr }
            if (currIndex != -1) notifyItemChanged(currIndex, "silent")
        }
    }
}