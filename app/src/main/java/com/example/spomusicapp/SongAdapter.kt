package com.example.spomusicapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Collections

class SongAdapter : ListAdapter<Song, SongAdapter.SongViewHolder>(SongDiffCallback()) {

    var onItemClick: ((Pair<Song, Uri?>) -> Unit)? = null
    var onLikeClick: ((Song, Int) -> Unit)? = null

    private var currentPlayingUrl: String? = null
    private var previousPlayingUrl: String? = null

    val bitmapCache: MutableMap<String, Bitmap> = Collections.synchronizedMap(mutableMapOf())
    val imageUriCache: MutableMap<String, Uri> = Collections.synchronizedMap(mutableMapOf())

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
        private val streamsTextView: TextView = itemView.findViewById(R.id.song_streams)
        private val albumArtImageView: ImageView = itemView.findViewById(R.id.albumArtImage)

        private val backgroundItemSelected: FrameLayout = itemView.findViewById(R.id.item_background)
        private val gradientText: View = itemView.findViewById(R.id.gradientBorder)
        private val likeButton: ImageButton = itemView.findViewById(R.id.like_button)

        fun bind(song: Song) {

            nameTextView.text = song.title
            artistTextView.text = song.artistName ?: "Desconocido"
            streamsTextView.text = formatStreams(song.streams)

            showPlayingSong(song.url, currentPlayingUrl, itemView, nameTextView, backgroundItemSelected, gradientText)

            Utils.getImageSongFromCache(song, itemView.context, albumArtImageView, song.localCoverPath.toString())

            itemView.setOnClickListener {
                val bitmap = bitmapCache[song.url]
                val imageUri = bitmap?.let { saveBitmapToCache(itemView.context, it, "album_${song.title}.png") }
                onItemClick?.invoke(song to imageUri)
            }

            likeButton.setImageResource(
                if (song.isLiked) R.drawable.favorite else R.drawable.favorite_border
            )

            likeButton.setOnClickListener {
                onLikeClick?.invoke(song, adapterPosition)
            }

        }
    }

    class SongDiffCallback : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem.id == newItem.id
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

    fun formatStreams(streams: Int): String{
        return if(streams == 0){
            "Sin reproducciones"
        }else{
            ("$streams reproducciones")
        }
    }

    fun updateLikeStatus(position: Int, isLiked: Boolean) {
        val newList = currentList.toMutableList()
        val song = newList[position]
        newList[position] = song.copy(isLiked = isLiked)
        submitList(newList)
    }

    fun removeSongAt(position: Int) {
        val newList = currentList.toMutableList()
        if (position in newList.indices) {
            newList.removeAt(position)
            submitList(newList)
        }
    }


    fun showPlayingSong(urlSong: String, currentUrlSong: String?, itemView: View, textView: TextView, backgroundItemSelected: FrameLayout, gradientText: View){
        if (urlSong == currentUrlSong) {
            textView.setTextColor(ContextCompat.getColor(itemView.context, R.color.titleSongColorWhilePlaying))

            ViewCompat.setBackgroundTintList(
                backgroundItemSelected,
                ColorStateList.valueOf(ContextCompat.getColor(itemView.context, R.color.selectedSongColorWhilePlaying))
            )

            gradientText.setBackgroundResource(R.drawable.gradient_text_player_background_selected)
        } else {
            textView.setTextColor(ContextCompat.getColor(itemView.context, R.color.white))

            ViewCompat.setBackgroundTintList(
                backgroundItemSelected,
                ColorStateList.valueOf(ContextCompat.getColor(itemView.context, R.color.cardsTheme))
            )

            gradientText.setBackgroundResource(R.drawable.gradient_text_player_background)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setCurrentPlayingSong(url: String?) {
        if (currentPlayingUrl == url) return

        previousPlayingUrl = currentPlayingUrl
        currentPlayingUrl = url

        previousPlayingUrl?.let { prevUrl ->
            val prevIndex = currentList.indexOfFirst { it.url == prevUrl }
            if (prevIndex != -1) notifyItemChanged(prevIndex)
        }

        currentPlayingUrl?.let { currUrl ->
            val currIndex = currentList.indexOfFirst { it.url == currUrl }
            if (currIndex != -1) notifyItemChanged(currIndex)
        }
    }

}
