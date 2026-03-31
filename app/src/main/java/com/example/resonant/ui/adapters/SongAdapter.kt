package com.example.resonant.ui.adapters

import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import com.example.resonant.R
import com.example.resonant.data.models.Song
import com.example.resonant.utils.ChartUtils
import com.example.resonant.utils.ImageRequestHelper
import com.example.resonant.utils.MiniPlayerColorizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

class SongAdapter(private val viewType: Int) : ListAdapter<Song, RecyclerView.ViewHolder>(SongDiffCallback()) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).id.hashCode().toLong()
    }

    companion object {
        const val VIEW_TYPE_FULL = 1
        const val VIEW_TYPE_TOP_SONG = 2
        const val VIEW_TYPE_GRID = 3
    }

    var onItemClick: ((Pair<Song, Bitmap?>) -> Unit)? = null
    private var currentPlayingId: String? = null
    private var previousPlayingId: String? = null
    var onSettingsClick: ((Song) -> Unit)? = null
    var onFavoriteClick: ((Song, Boolean) -> Unit)? = null
    var favoriteSongIds: Set<String> = emptySet()
        set(newFavoriteIds) {
            val oldFavoriteIds = field
            field = newFavoriteIds

            val changedIds = (oldFavoriteIds - newFavoriteIds) + (newFavoriteIds - oldFavoriteIds)

            if (changedIds.isEmpty()) return

            changedIds.forEach { songId ->
                val index = currentList.indexOfFirst { it.id == songId }
                if (index != -1) {
                    notifyItemChanged(index, "silent") // "silent" es un payload opcional que ya usas
                }
            }
        }

    var downloadedSongIds: Set<String> = emptySet()
        set(value) {
            field = value
            // Notificamos cambios para refrescar los iconos
            notifyDataSetChanged()
        }

    var hideArtwork: Boolean = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    // CORRECCIÓN 2: Añadido el método getItemViewType que faltaba
    override fun getItemViewType(position: Int): Int {
        return viewType
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_TOP_SONG -> {
                // Asegúrate de que tu layout se llame 'list_item_top_song.xml' como en el ejemplo anterior
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_top_song, parent, false)
                TopSongViewHolder(view)
            }
            VIEW_TYPE_GRID -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song_grid, parent, false)
                GridSongViewHolder(view)
            }
            else -> { // VIEW_TYPE_FULL
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
                SongViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val song = getItem(position)
        when (holder) {
            is SongViewHolder -> holder.bind(song, partial = false)
            is TopSongViewHolder -> holder.bind(song, position, partial = false)
            is GridSongViewHolder -> holder.bind(song, partial = false)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            val song = getItem(position)
            when (holder) {
                is SongViewHolder -> holder.bind(song, partial = true)
                // MODIFICADO: Ahora llamamos al 'bind' completo con partial = true
                is TopSongViewHolder -> holder.bind(song, position, partial = true)
                is GridSongViewHolder -> holder.bind(song, partial = true)
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is SongViewHolder) {
            holder.cancelJobs()
            Glide.with(holder.itemView).clear(holder.albumArtImageView)
            holder.albumArtAnimator?.cancel()
            holder.albumArtImageView.rotation = 0f
        }
    }

    // Optimization: Cache formatter to avoid expensive creation in onBind
    private val numberFormatter by lazy { NumberFormat.getInstance(Locale.getDefault()) }

    // Optimization: Cache dominant colors to avoid expensive Palette recalculations
    private val dominantColorCache = mutableMapOf<String, Int>()

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val nameTextView: TextView = itemView.findViewById(R.id.songTitle)
        private val artistTextView: TextView = itemView.findViewById(R.id.songArtist)
        private val likeButton: ImageButton = itemView.findViewById(R.id.likeButton)
        private val settingsButton: ImageButton = itemView.findViewById(R.id.featuredButton)
        private val downloadedIcon: ImageView = itemView.findViewById(R.id.downloadedIcon)
        private val textContainer: LinearLayout = itemView.findViewById(R.id.songTextContainer)

        val albumArtImageView: ImageView = itemView.findViewById(R.id.songImage)
        var albumArtAnimator: ObjectAnimator? = null

        private var artworkJob: Job? = null

        init {
            likeButton.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val song = getItem(position)
                    onFavoriteClick?.invoke(song, favoriteSongIds.contains(song.id))
                }
            }

            settingsButton.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                     val song = getItem(position)
                     onSettingsClick?.invoke(song)
                }
            }

            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val song = getItem(position)
                    onItemClick?.invoke(song to null)
                }
            }
        }

        fun cancelJobs() {
            artworkJob?.cancel()
            artworkJob = null
        }

        fun bind(song: Song, partial: Boolean = false) {

            val isDownloaded = downloadedSongIds.contains(song.id)
            if (isDownloaded) {
                downloadedIcon.visibility = View.VISIBLE
            } else {
                downloadedIcon.visibility = View.GONE
            }

            nameTextView.text = song.title ?: "Desconocido"
            artistTextView.text = song.artistName ?: "Desconocido"

            val isFavorite = favoriteSongIds.contains(song.id)
            likeButton.visibility = if (isFavorite) View.VISIBLE else View.INVISIBLE
            likeButton.setImageResource(if (isFavorite) R.drawable.ic_favorite else 0)

            nameTextView.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    if (song.id == currentPlayingId) R.color.titleSongColorWhilePlaying else R.color.white
                )
            )

            if (!partial) {
                cancelJobs()
                albumArtAnimator?.cancel()
                albumArtImageView.rotation = 0f
                if (hideArtwork) {
                    albumArtImageView.visibility = View.GONE
                    val layoutParams = textContainer.layoutParams as ConstraintLayout.LayoutParams
                    layoutParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    layoutParams.startToEnd = ConstraintLayout.LayoutParams.UNSET
                    layoutParams.marginStart = 0
                    textContainer.layoutParams = layoutParams
                } else {
                    albumArtImageView.visibility = View.VISIBLE
                    val layoutParams = textContainer.layoutParams as ConstraintLayout.LayoutParams
                    layoutParams.startToStart = ConstraintLayout.LayoutParams.UNSET
                    layoutParams.startToEnd = R.id.songImage
                    layoutParams.marginStart = (14 * itemView.resources.displayMetrics.density).roundToInt()
                    textContainer.layoutParams = layoutParams

                    Glide.with(itemView).clear(albumArtImageView)

                    val placeholderRes = R.drawable.ic_disc
                    val url = song.coverUrl

                    if (!url.isNullOrBlank()) {
                        albumArtAnimator?.cancel()
                        albumArtImageView.rotation = 0f

                        Glide.with(albumArtImageView.context)
                            .asBitmap()
                            .load(url)
                            .override(200, 200) // OPTIMIZATION: Downsample image
                            .dontAnimate()
                            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                            .placeholder(placeholderRes)
                            .error(placeholderRes)
                            .into(albumArtImageView)
                    } else {
                        albumArtImageView.setImageResource(placeholderRes)
                    }
                }
            }
        }
    }

    inner class TopSongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val positionTextView: TextView = itemView.findViewById(R.id.songPosition)
        private val coverImageView: ImageView = itemView.findViewById(R.id.songImage)
        private val title: TextView = itemView.findViewById(R.id.songTitle)
        private val streams: TextView = itemView.findViewById(R.id.songStreams)
        private val likeButton: ImageButton = itemView.findViewById(R.id.likeButton)
        private val settingsButton: ImageButton = itemView.findViewById(R.id.featuredButton)
        private val downloadedIcon: ImageView = itemView.findViewById(R.id.downloadedIcon)
        private val tvPositionChange: TextView = itemView.findViewById(R.id.tvPositionChange)
        private val ivTrendIcon: ImageView = itemView.findViewById(R.id.ivTrendIcon)

        init {
             likeButton.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val song = getItem(position)
                    onFavoriteClick?.invoke(song, favoriteSongIds.contains(song.id))
                }
            }

            settingsButton.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                     val song = getItem(position)
                     onSettingsClick?.invoke(song)
                }
            }

            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val song = getItem(position)
                    onItemClick?.invoke(song to null)
                }
            }
        }

        fun bind(song: Song, itemPosition: Int, partial: Boolean = false) {
            positionTextView.text = "#${(itemPosition + 1)}"
            ChartUtils.bindPositionChange(tvPositionChange, ivTrendIcon, song.positionChange)
            title.text = song.title

            val isDownloaded = downloadedSongIds.contains(song.id)
             if (isDownloaded) {
                downloadedIcon.visibility = View.VISIBLE
            } else {
                downloadedIcon.visibility = View.GONE
            }

            // OPTIMIZATION: Use cached formatter
            streams.text = "${numberFormatter.format(song.streams)} reproducciones"

            val isFavorite = favoriteSongIds.contains(song.id)
            likeButton.visibility = if (isFavorite) View.VISIBLE else View.INVISIBLE
            likeButton.setImageResource(if (isFavorite) R.drawable.ic_favorite else 0)

            title.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    if (song.id == currentPlayingId) R.color.titleSongColorWhilePlaying else R.color.white
                )
            )

            if (!partial) {
                val placeholderRes = R.drawable.ic_disc
                val url = song.coverUrl

                if (!url.isNullOrBlank()) {
                    Glide.with(coverImageView.context)
                        .asBitmap()
                        .load(url)
                        .override(200, 200) // OPTIMIZATION: Downsample image
                        .dontAnimate()
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .placeholder(placeholderRes)
                        .error(placeholderRes)
                        .into(coverImageView)
                } else {
                    coverImageView.setImageResource(placeholderRes)
                }
            }
        }
    }

    inner class GridSongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleTextView: TextView = itemView.findViewById(R.id.songTitle)
        private val artistTextView: TextView = itemView.findViewById(R.id.songArtist)
        private val albumArtImageView: ImageView = itemView.findViewById(R.id.songImage)
        private val playPauseIcon: ImageView = itemView.findViewById(R.id.playIcon)
        private val container: View = itemView.findViewById(R.id.itemContainer)

        init {
             itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val song = getItem(position)
                    onItemClick?.invoke(song to null)
                }
            }
        }

        fun bind(song: Song, partial: Boolean = false) {
            titleTextView.text = song.title ?: "Desconocido"
            artistTextView.text = song.artistName ?: "Desconocido"

            val isPlaying = (song.id == currentPlayingId)

            // Highlight playing song title
            titleTextView.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    if (isPlaying) R.color.titleSongColorWhilePlaying else R.color.white
                )
            )

            // Update Play/Pause icon
            playPauseIcon.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            playPauseIcon.visibility = if (isPlaying) View.VISIBLE else View.GONE

            if (!partial) {
                val placeholderRes = R.drawable.ic_disc
                val url = song.coverUrl

                Glide.with(itemView).clear(albumArtImageView)

                if (!url.isNullOrBlank()) {
                    val model = ImageRequestHelper.buildGlideModel(albumArtImageView.context, url)
                    Glide.with(albumArtImageView.context)
                        .asBitmap()
                        .load(model)
                        .override(150, 150)
                        .dontAnimate()
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .placeholder(placeholderRes)
                        .error(placeholderRes)
                        .into(object : CustomTarget<Bitmap>() {
                            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                                albumArtImageView.setImageBitmap(resource)
                                MiniPlayerColorizer.applyFromImageView(
                                    imageView = albumArtImageView,
                                    targets = MiniPlayerColorizer.Targets(
                                        container = container,
                                        title = titleTextView,
                                        subtitle = artistTextView
                                    ),
                                    fallbackColor = ContextCompat.getColor(albumArtImageView.context, R.color.primaryColorTheme),
                                    animateMillis = 300L
                                )
                            }
                            override fun onLoadCleared(placeholder: Drawable?) {
                                albumArtImageView.setImageDrawable(placeholder)
                            }
                            override fun onLoadFailed(errorDrawable: Drawable?) {
                                albumArtImageView.setImageDrawable(errorDrawable)
                            }
                        })
                } else {
                    albumArtImageView.setImageResource(placeholderRes)
                    MiniPlayerColorizer.applyFromImageView(
                        imageView = albumArtImageView,
                        targets = MiniPlayerColorizer.Targets(
                            container = container,
                            title = titleTextView,
                            subtitle = artistTextView
                        ),
                        fallbackColor = ContextCompat.getColor(albumArtImageView.context, R.color.primaryColorTheme),
                        animateMillis = 0L
                    )
                }
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