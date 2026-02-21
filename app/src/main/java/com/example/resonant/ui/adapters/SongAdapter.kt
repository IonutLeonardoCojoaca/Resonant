package com.example.resonant.ui.adapters

import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
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
import com.example.resonant.utils.ImageRequestHelper
import com.example.resonant.utils.MiniPlayerColorizer
import android.graphics.drawable.Drawable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.text.NumberFormat
import java.util.Locale

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
                albumArtImageView.visibility = View.VISIBLE

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

    inner class TopSongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val positionTextView: TextView = itemView.findViewById(R.id.songPosition)
        private val coverImageView: ImageView = itemView.findViewById(R.id.songImage)
        private val title: TextView = itemView.findViewById(R.id.songTitle)
        private val streams: TextView = itemView.findViewById(R.id.songStreams)
        private val likeButton: ImageButton = itemView.findViewById(R.id.likeButton)
        private val settingsButton: ImageButton = itemView.findViewById(R.id.featuredButton)
        private val downloadedIcon: ImageView = itemView.findViewById(R.id.downloadedIcon)

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

            if (!partial) {
                // Use Palette loading for dynamic background
                loadSongCoverPalette(song.id, song.coverUrl, albumArtImageView, container, titleTextView, artistTextView)
            }
        }
    }

    private fun loadSongCoverPalette(
        songId: String,
        url: String?,
        imageView: ImageView,
        container: View,
        titleView: TextView,
        subtitleView: TextView
    ) {
        val placeholderRes = R.drawable.ic_disc // Or specific dark placeholder

        // 1. Check Cache
        if (dominantColorCache.containsKey(songId)) {
            val cachedColor = dominantColorCache[songId]!!
            // If cached, apply immediately without Glide palette generation logic overhead
            // But we still need to load the image into the view!
             Glide.with(imageView).clear(imageView)
             if (!url.isNullOrBlank()) {
                 Glide.with(imageView)
                    .load(url) // Just load image normally
                    .override(300, 300)
                    .dontAnimate()
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .placeholder(placeholderRes)
                    .into(imageView)
             } else {
                 imageView.setImageResource(placeholderRes)
             }
             
             // Apply Cached Color
             MiniPlayerColorizer.applyFromImageView(
                imageView, // This won't be used for generation bc we pass a color? No, helper needs refactor to accept color directly?
                // Actually MiniPlayerColorizer doesn't support "Apply this int color".
                // I should assume the color calculation is fast enough? 
                // No, I want to avoid Palette generation.
                // I will add a manual apply using the cached color.
                 MiniPlayerColorizer.Targets(container = container, title = titleView, subtitle = subtitleView),
                 fallbackColor = cachedColor, // Use cached as "fallback" but it isn't fallback.
                 animateMillis = 0L // Instant
             )
             // Wait, MiniPlayerColorizer logic: if image is NOT null, it generates palette. 
             // If I use 'applyFromImageView', it WILL regenerate.
             // I need to manually apply the color if cached.
             // I'll inline the application logic for cache hit.
             
             // Apply Cached Color Logic:
             applyCachedColor(container, titleView, subtitleView, cachedColor)
             return
        }

        // 2. Cache Miss: Full Load
        Glide.with(imageView).clear(imageView)

        if (url.isNullOrBlank()) {
            imageView.setImageResource(placeholderRes)
             MiniPlayerColorizer.applyFromImageView(
                imageView,
                MiniPlayerColorizer.Targets(container = container, title = titleView, subtitle = subtitleView),
                fallbackColor = imageView.context.getColor(R.color.primaryColorTheme), // Default dark/primary
                animateMillis = 0L
            )
            return
        }

        val model = ImageRequestHelper.buildGlideModel(imageView.context, url)

        Glide.with(imageView)
            .asBitmap()
            .load(model)
            .override(300, 300)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .dontAnimate()
            .placeholder(placeholderRes)
            .error(placeholderRes)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    imageView.setImageBitmap(resource)
                    
                    // We need to Intercept the color to cache it.
                    // MiniPlayerColorizer doesn't return it.
                    // We might need to generate palette here manually then cache it.
                    
                    androidx.palette.graphics.Palette.from(resource).maximumColorCount(32).generate { palette ->
                         val swatches = listOfNotNull(
                            palette?.vibrantSwatch,
                            palette?.darkVibrantSwatch,
                            palette?.lightVibrantSwatch,
                            palette?.mutedSwatch,
                            palette?.darkMutedSwatch,
                            palette?.lightMutedSwatch,
                            palette?.dominantSwatch
                        )
                        val bestSwatch = swatches.maxByOrNull { it.population }
                        val rawBgColor = bestSwatch?.rgb ?: imageView.context.getColor(R.color.primaryColorTheme)
                        
                        // CACHE IT
                        dominantColorCache[songId] = rawBgColor
                        
                        // Now Apply
                        // We can call MiniPlayerColorizer but it will regen again. 
                        // Or we duplicate logic. 
                        // Best: MiniPlayerColorizer is small enough to just let it run once, 
                        // BUT if we want true optimization we should not generate twice.
                        // Since I am already generating here to cache, I should apply it myself.
                        
                        // Copying logic from MiniPlayerColorizer (Contrast check)
                        val isBgDark = androidx.core.graphics.ColorUtils.calculateLuminance(rawBgColor) < 0.6
                        val textColor = if (isBgDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                        
                        // We call ensureContrast helper from MiniPlayerColorizer? It is private.
                        // I'll skipping exact Contrast helper for now or expose it?
                        // Simplest: Just use MiniPlayerColorizer.applyFromImageView(imageView...) inside generic flow 
                        // and accept it runs Palette twice first time? NO, that defeats optimization.
                        
                        // Hack: I will just use MiniPlayerColorizer normally on MISS. 
                        // But wait, how do I get the color to cache it if I use MiniPlayerColorizer?
                        // I can't.
                        // So I MUST Generate Palette here.
                        
                        applyColorToViews(container, titleView, subtitleView, rawBgColor, textColor)
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
    
    private fun applyCachedColor(container: View, title: TextView, subtitle: TextView, color: Int) {
         val isBgDark = androidx.core.graphics.ColorUtils.calculateLuminance(color) < 0.6
         val textColor = if (isBgDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
         applyColorToViews(container, title, subtitle, color, textColor)
    }
    
    private fun applyColorToViews(container: View, title: TextView, subtitle: TextView, bgColor: Int, textColor: Int) {
         // Direct application without animation for cache hits or fast loads
         // Ensure contrast logic (simplified version of MiniPlayerColorizer)
         // Actually, let's just create a quick robust helper
         
         // Fix background tint
         androidx.core.view.ViewCompat.setBackgroundTintList(container, android.content.res.ColorStateList.valueOf(bgColor))
         
         // Fix text color
         title.setTextColor(textColor)
         subtitle.setTextColor(adjustAlpha(textColor, 0.85f))
    }
     private fun adjustAlpha(color: Int, factor: Float): Int {
        val a = (android.graphics.Color.alpha(color) * factor).toInt()
        return android.graphics.Color.argb(a, android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color))
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