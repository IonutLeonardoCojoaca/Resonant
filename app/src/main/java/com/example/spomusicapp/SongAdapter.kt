package com.example.spomusicapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SongAdapter : ListAdapter<Song, SongAdapter.SongViewHolder>(SongDiffCallback()) {

    var onItemClick: ((Song) -> Unit)? = null
    private var currentJob: Job? = null
    private val bitmapCache = mutableMapOf<String, Bitmap?>()

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
        private val albumTextView: TextView = itemView.findViewById(R.id.song_album)
        private val albumArtImageView: ImageView = itemView.findViewById(R.id.albumArtImage)

        fun bind(song: Song) {
            nameTextView.text = song.title
                .substringBefore("-")   // Obtiene el texto antes del primer guión
                .removeSuffix(".mp3")
                .replace(Regex("\\s*\\([^)]*\\)"), "")
                .replace("-", "–")
                .trim()

            artistTextView.text = song.artist ?: "Desconocido"
            albumTextView.text = song.album ?: "Desconocido"

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
            return oldItem.url == newItem.url // Comparar por la URL o algún identificador único
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
}
