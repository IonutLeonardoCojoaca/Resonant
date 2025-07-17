package com.example.resonant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Collections

class SongAdapter : ListAdapter<Song, SongAdapter.SongViewHolder>(SongDiffCallback()) {

    var onItemClick: ((Pair<Song, Uri?>) -> Unit)? = null
    private var currentPlayingUrl: String? = null
    private var previousPlayingUrl: String? = null

    val bitmapCache: MutableMap<String, Bitmap> = Collections.synchronizedMap(mutableMapOf())
    val imageUriCache: MutableMap<String, Uri> = Collections.synchronizedMap(mutableMapOf())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    // ✅ Manejo de actualizaciones silenciosas con payload
    override fun onBindViewHolder(holder: SongViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            val song = getItem(position)
            holder.bind(song, partial = true)
        }
    }

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val nameTextView: TextView = itemView.findViewById(R.id.song_name)
        private val artistTextView: TextView = itemView.findViewById(R.id.song_artist)
        private val streamsTextView: TextView = itemView.findViewById(R.id.song_streams)
        private val albumArtImageView: ImageView = itemView.findViewById(R.id.albumArtImage)

        fun bind(song: Song, partial: Boolean = false) {
            nameTextView.text = song.title
            artistTextView.text = song.artistName ?: "Desconocido"
            streamsTextView.text = formatStreams(song.streams)

            nameTextView.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    if (song.url == currentPlayingUrl) R.color.titleSongColorWhilePlaying else R.color.white
                )
            )

            if (!partial) {
                val cachedBitmap = bitmapCache[song.id]
                if (cachedBitmap != null) {
                    albumArtImageView.setImageBitmap(cachedBitmap)
                } else {
                    albumArtImageView.setImageResource(R.drawable.album_cover) // por defecto
                    val songUrl = song.url
                    if (!songUrl.isNullOrEmpty()) {
                        CoroutineScope(Dispatchers.IO).launch {
                            val bitmap = getEmbeddedPictureFromUrl(itemView.context, songUrl)
                            bitmap?.let {
                                withContext(Dispatchers.Main) {
                                    albumArtImageView.setImageBitmap(it)
                                    bitmapCache[song.id] = it
                                }
                            }
                        }
                    }
                }
            }

            itemView.setOnClickListener {
                val previousUrl = currentPlayingUrl
                currentPlayingUrl = song.url

                previousUrl?.let { prev ->
                    val prevIndex = currentList.indexOfFirst { it.url == prev }
                    if (prevIndex != -1) notifyItemChanged(prevIndex, "silent")
                }

                val currentIndex = currentList.indexOfFirst { it.url == currentPlayingUrl }
                if (currentIndex != -1) notifyItemChanged(currentIndex, "silent")

                val bitmap = bitmapCache[song.url]
                val imageUri = bitmap?.let {
                    saveBitmapToCache(itemView.context, it, "album_${song.title}.png")
                }

                onItemClick?.invoke(song to imageUri)
            }
        }
    }

    class SongDiffCallback : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(oldItem: Song, newItem: Song): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Song, newItem: Song): Boolean = oldItem == newItem
    }

    fun formatStreams(streams: Int): String =
        if (streams == 0) "Sin reproducciones" else "$streams reproducciones"

    fun setCurrentPlayingSong(url: String?) {
        if (currentPlayingUrl == url) return

        previousPlayingUrl = currentPlayingUrl
        currentPlayingUrl = url

        previousPlayingUrl?.let { prev ->
            val prevIndex = currentList.indexOfFirst { it.url == prev }
            if (prevIndex != -1) notifyItemChanged(prevIndex, "silent")
        }

        currentPlayingUrl?.let { curr ->
            val currIndex = currentList.indexOfFirst { it.url == curr }
            if (currIndex != -1) notifyItemChanged(currIndex, "silent")
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
            Log.e("SongViewHolder", "Error al obtener la imagen incrustada desde la URL: $url", e)
            null
        }
    }

    fun saveBitmapToCache(context: Context, bitmap: Bitmap, fileName: String): Uri? {
        return try {
            val file = File(context.cacheDir, fileName)
            val fos = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            fos.flush()
            fos.close()
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    fun updateLikeStatus(position: Int, isLiked: Boolean) {
        val newList = currentList.toMutableList()
        val song = newList[position]
        //newList[position] = song.copy(isLiked = isLiked)
        submitList(newList)
    }

    fun removeSongAt(position: Int) {
        val newList = currentList.toMutableList()
        if (position in newList.indices) {
            newList.removeAt(position)
            submitList(newList)
        }
    }


}
