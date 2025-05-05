package com.example.spomusicapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SongAdapter : ListAdapter<Song, SongAdapter.SongViewHolder>(SongDiffCallback()) {

    var onItemClick: ((Song) -> Unit)? = null
    private val bitmapCache = mutableMapOf<String, Bitmap?>()
    private var currentPlayingUrl: String? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = getItem(position)
        holder.bind(song)
    }

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val nameTextView: TextView = itemView.findViewById(R.id.song_name)
        private val artistTextView: TextView = itemView.findViewById(R.id.song_artist)
        private val albumArtImageView: ImageView = itemView.findViewById(R.id.albumArtImage)

        private val backgroundItemSelected: FrameLayout = itemView.findViewById(R.id.item_background)
        private val gradientText: View = itemView.findViewById(R.id.gradientBorder)

        fun bind(song: Song) {
            nameTextView.text = song.title
                .removeSuffix(".mp3")
                .replace(Regex("\\s*\\([^)]*\\)"), "")
                .replace("-", "–")
                .trim()

            artistTextView.text = song.artist ?: "Desconocido"

            if (song.url == currentPlayingUrl) {
                nameTextView.setTextColor(ContextCompat.getColor(itemView.context, R.color.titleSongColorWhilePlaying))

                ViewCompat.setBackgroundTintList(
                    backgroundItemSelected,
                    ColorStateList.valueOf(ContextCompat.getColor(itemView.context, R.color.selectedSongColorWhilePlaying))
                )

                gradientText.setBackgroundResource(R.drawable.gradient_text_player_background_selected)
            } else {
                nameTextView.setTextColor(ContextCompat.getColor(itemView.context, R.color.white))

                ViewCompat.setBackgroundTintList(
                    backgroundItemSelected,
                    ColorStateList.valueOf(ContextCompat.getColor(itemView.context, R.color.cardsTheme))
                )

                gradientText.setBackgroundResource(R.drawable.gradient_text_player_background)
            }

            bitmapCache[song.url]?.let {
                albumArtImageView.setImageBitmap(it)
            } ?: run {
                CoroutineScope(Dispatchers.IO).launch {
                    val bitmap = getEmbeddedPictureFromUrl(itemView.context, song.url)
                    withContext(Dispatchers.Main) {
                        if (bitmap != null) {
                            albumArtImageView.setImageBitmap(bitmap)
                            bitmapCache[song.url] = bitmap
                        } else {
                            albumArtImageView.setImageResource(R.drawable.album_cover)
                        }
                    }
                }
            }

            itemView.setOnClickListener {
                onItemClick?.invoke(song)
            }
        }
    }

    class SongDiffCallback : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem.url == newItem.url
        }

        @SuppressLint("DiffUtilEquals")
        override fun areContentsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem == newItem
        }
    }

    fun getEmbeddedPictureFromUrl(context: Context, url: String): Bitmap? {
        return try {
            val mediaMetadataRetriever = MediaMetadataRetriever()
            mediaMetadataRetriever.setDataSource(url, HashMap<String, String>())

            val art = mediaMetadataRetriever.embeddedPicture
            mediaMetadataRetriever.release()

            art?.let {
                BitmapFactory.decodeByteArray(it, 0, it.size)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getBitmapFromCacheOrLoad(context: Context, song: Song, callback: (Bitmap?) -> Unit) {
        bitmapCache[song.url]?.let {
            callback(it)
        } ?: run {
            CoroutineScope(Dispatchers.IO).launch {
                val bitmap = getEmbeddedPictureFromUrl(context, song.url)
                withContext(Dispatchers.Main) {
                    if (bitmap != null) {
                        bitmapCache[song.url] = bitmap
                    }
                    callback(bitmap)
                }
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setCurrentPlayingSong(url: String?) {
        currentPlayingUrl = url
        notifyDataSetChanged()
    }

}
