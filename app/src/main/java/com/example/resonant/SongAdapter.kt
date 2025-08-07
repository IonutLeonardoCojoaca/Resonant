package com.example.resonant

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections

class SongAdapter : ListAdapter<Song, SongAdapter.SongViewHolder>(SongDiffCallback()) {

    var onItemClick: ((Pair<Song, Bitmap?>) -> Unit)? = null
    private var currentPlayingId: String? = null
    private var previousPlayingId: String? = null
    val bitmapCache: MutableMap<String, Bitmap> = Collections.synchronizedMap(mutableMapOf())

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
            val song = getItem(position)
            holder.bind(song, partial = true)
        }
    }

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val nameTextView: TextView = itemView.findViewById(R.id.songTitle)
        private val artistTextView: TextView = itemView.findViewById(R.id.songArtist)
        private val albumArtImageView: ImageView = itemView.findViewById(R.id.songImage)
        private val loadingAnimation: LottieAnimationView = itemView.findViewById(R.id.loadingAnimation)

        fun bind(song: Song, partial: Boolean = false) {
            nameTextView.text = song.title
            artistTextView.text = song.artistName ?: "Desconocido"

            nameTextView.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    if (song.id == currentPlayingId) R.color.titleSongColorWhilePlaying else R.color.white
                )
            )

            if (!partial) {
                if (!bitmapCache.containsKey(song.id)) {

                    val albumImageUrl = song.albumImageUrl
                    if (!albumImageUrl.isNullOrEmpty()) {
                        CoroutineScope(Dispatchers.Main).launch {
                            Glide.with(itemView)
                                .asBitmap()
                                .load(albumImageUrl)
                                .into(albumArtImageView)

                            CoroutineScope(Dispatchers.IO).launch {
                                val bitmap = Glide.with(itemView)
                                    .asBitmap()
                                    .load(albumImageUrl)
                                    .submit()
                                    .get()

                                bitmapCache[song.id] = bitmap
                                Utils.saveBitmapToCache(itemView.context, bitmap, song.id)
                            }
                        }
                    } else if (!song.url.isNullOrEmpty()) {
                        loadingAnimation.visibility = View.VISIBLE
                        albumArtImageView.visibility = View.INVISIBLE
                        val currentAdapterPosition = bindingAdapterPosition
                        CoroutineScope(Dispatchers.IO).launch {
                            val bitmap = withTimeoutOrNull(8000L) {
                                Utils.getEmbeddedPictureFromUrl(itemView.context, song.url!!)
                            }
                            if (bitmap != null) {
                                bitmapCache[song.id] = bitmap
                                Utils.saveBitmapToCache(itemView.context, bitmap, song.id)

                                withContext(Dispatchers.Main) {
                                    if (bindingAdapterPosition == currentAdapterPosition) {
                                        albumArtImageView.setImageBitmap(bitmap)
                                        loadingAnimation.visibility = View.GONE
                                        albumArtImageView.visibility = View.VISIBLE
                                    }
                                }
                            }
                        }
                    }
                } else {
                    albumArtImageView.setImageBitmap(bitmapCache[song.id])
                }
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
