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
import com.bumptech.glide.request.target.Target
import com.example.resonant.R
import com.example.resonant.data.models.Song
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

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val nameTextView: TextView = itemView.findViewById(R.id.songTitle)
        private val artistTextView: TextView = itemView.findViewById(R.id.songArtist)
        private val likeButton: ImageButton = itemView.findViewById(R.id.likeButton)
        private val settingsButton: ImageButton = itemView.findViewById(R.id.featuredButton)
        private val downloadedIcon: ImageView = itemView.findViewById(R.id.downloadedIcon)

        val albumArtImageView: ImageView = itemView.findViewById(R.id.songImage)
        var albumArtAnimator: ObjectAnimator? = null

        private var artworkJob: Job? = null

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

                Glide.with(itemView)
                    .clear(albumArtImageView)

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
                        .listener(object : RequestListener<Bitmap> {
                            override fun onLoadFailed(
                                e: GlideException?,
                                model: Any?,
                                target: Target<Bitmap?>,
                                isFirstResource: Boolean
                            ): Boolean {
                                return false
                            }

                            override fun onResourceReady(
                                resource: Bitmap,
                                model: Any,
                                target: Target<Bitmap?>?,
                                dataSource: DataSource,
                                isFirstResource: Boolean
                            ): Boolean {
                                return false
                            }
                        })
                        .into(albumArtImageView)
                } else {
                    albumArtImageView.setImageResource(placeholderRes)
                    Log.w("SongAdapter", "coverUrl nulo para ${song.title}")
                }

            }

            likeButton.setOnClickListener {
                // Ya no modificamos 'favoriteSongIds' aquí.
                // Solo notificamos al Fragment que el botón fue pulsado.
                onFavoriteClick?.invoke(song, favoriteSongIds.contains(song.id))
            }

            settingsButton.setOnClickListener {
                onSettingsClick?.invoke(song)
            }

            itemView.setOnClickListener {
                // Ahora solo notificamos el clic. Nada más.
                onItemClick?.invoke(song to null)
            }
        }

    }

    inner class TopSongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val position: TextView = itemView.findViewById(R.id.songPosition)
        private val coverImageView: ImageView = itemView.findViewById(R.id.songImage)
        private val title: TextView = itemView.findViewById(R.id.songTitle)
        private val streams: TextView = itemView.findViewById(R.id.songStreams)
        private val likeButton: ImageButton = itemView.findViewById(R.id.likeButton)
        private val settingsButton: ImageButton = itemView.findViewById(R.id.featuredButton)
        private val downloadedIcon: ImageView = itemView.findViewById(R.id.downloadedIcon)


        fun bind(song: Song, itemPosition: Int, partial: Boolean = false) {
            position.text = (itemPosition + 1).toString()
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

            // --- CORRECCIÓN CRÍTICA AQUÍ ---
            // Antes recargabas la imagen SIEMPRE. Ahora solo si NO es partial.
            if (!partial) {
                val placeholderRes = R.drawable.ic_disc
                val url = song.coverUrl

                if (!url.isNullOrBlank()) {
                    Glide.with(coverImageView.context)
                        .asBitmap()
                        .load(url)
                        .override(200, 200) // OPTIMIZATION: Downsample image
                        .dontAnimate() // <--- IMPORTANTE: Evita el parpadeo blanco
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .placeholder(placeholderRes)
                        .error(placeholderRes)
                        .into(coverImageView)
                } else {
                    coverImageView.setImageResource(placeholderRes)
                }
            }
            // -------------------------------

            likeButton.setOnClickListener {
                onFavoriteClick?.invoke(song, favoriteSongIds.contains(song.id))
            }

            settingsButton.setOnClickListener {
                onSettingsClick?.invoke(song)
            }

            itemView.setOnClickListener {
                onItemClick?.invoke(song to null)
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