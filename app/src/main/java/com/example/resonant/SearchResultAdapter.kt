package com.example.resonant

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.navigation.findNavController
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.bumptech.glide.Glide
import com.squareup.picasso.Callback
import com.squareup.picasso.Picasso
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.parcelize.Parcelize

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

class SearchResultAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<SearchResult>()
    private val bitmapCache = mutableMapOf<String, Bitmap>()
    var onSongClick: ((Pair<Song, Bitmap?>) -> Unit)? = null
    private var currentPlayingId: String? = null
    private var previousPlayingId: String? = null

    companion object {
        private const val TYPE_SONG = 0
        private const val TYPE_ALBUM = 1
        private const val TYPE_ARTIST = 2
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is SearchResult.SongItem -> TYPE_SONG
            is SearchResult.AlbumItem -> TYPE_ALBUM
            is SearchResult.ArtistItem -> TYPE_ARTIST
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SONG -> SongViewHolder(inflater.inflate(R.layout.item_result_song, parent, false))
            TYPE_ALBUM -> AlbumViewHolder(inflater.inflate(R.layout.item_result_album, parent, false))
            TYPE_ARTIST -> ArtistViewHolder(inflater.inflate(R.layout.item_result_artist, parent, false))
            else -> throw IllegalArgumentException("Unknown view type $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is SearchResult.SongItem -> (holder as SongViewHolder).bind(item.song)
            is SearchResult.AlbumItem -> (holder as AlbumViewHolder).bind(item.album)
            is SearchResult.ArtistItem -> (holder as ArtistViewHolder).bind(item.artist)
        }
    }

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView = itemView.findViewById<TextView>(R.id.songTitle)
        private val artistTextView = itemView.findViewById<TextView>(R.id.songArtist)
        private val albumArtImageView = itemView.findViewById<ImageView>(R.id.songImage)
        private val loadingAnimation = itemView.findViewById<LottieAnimationView>(R.id.loadingAnimation)

        fun bind(song: Song) {
            nameTextView.text = song.title
            artistTextView.text = song.artistName ?: "Desconocido"

            nameTextView.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    if (song.id == currentPlayingId) R.color.titleSongColorWhilePlaying else R.color.white
                )
            )

            if (!bitmapCache.containsKey(song.id)) {
                val albumImageUrl = song.albumImageUrl
                if (!albumImageUrl.isNullOrEmpty()) {
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
                } else if (!song.url.isNullOrEmpty()) {
                    loadingAnimation.visibility = View.VISIBLE
                    albumArtImageView.visibility = View.INVISIBLE
                    val currentPosition = bindingAdapterPosition
                    CoroutineScope(Dispatchers.IO).launch {
                        val bitmap = withTimeoutOrNull(8000L) {
                            Utils.getEmbeddedPictureFromUrl(itemView.context, song.url!!)
                        }
                        bitmap?.let {
                            bitmapCache[song.id] = it
                            Utils.saveBitmapToCache(itemView.context, it, song.id)

                            withContext(Dispatchers.Main) {
                                if (bindingAdapterPosition == currentPosition) {
                                    albumArtImageView.setImageBitmap(it)
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

            itemView.setOnClickListener {
                val previousId = currentPlayingId
                currentPlayingId = song.id

                previousId?.let { prev ->
                    val prevIndex = items.indexOfFirst {
                        it is SearchResult.SongItem && it.song.id == prev
                    }
                    if (prevIndex != -1) notifyItemChanged(prevIndex)
                }

                val currentIndex = items.indexOfFirst {
                    it is SearchResult.SongItem && it.song.id == currentPlayingId
                }
                if (currentIndex != -1) notifyItemChanged(currentIndex)

                val bitmap = bitmapCache[song.id]
                onSongClick?.invoke(song to bitmap)
            }
        }
    }

    inner class AlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val albumTitle = itemView.findViewById<TextView>(R.id.albumTitle)
        private val albumImage = itemView.findViewById<ImageView>(R.id.albumImage)
        private val albumArtistName = itemView.findViewById<TextView>(R.id.albumArtistName) // ← CORRECTO

        fun bind(album: Album) {
            albumTitle.text = album.title ?: "Not found"
            albumArtistName.text = album.artistName ?: "Desconocido"

            if (!album.url.isNullOrEmpty()) {
                Picasso.get().load(album.url).into(albumImage)
            }

            itemView.setOnClickListener {
                val bundle = Bundle().apply {
                    putString("albumId", album.id)
                }
                itemView.findNavController()
                    .navigate(R.id.action_searchFragment_to_albumFragment, bundle)
            }
        }
    }

    inner class ArtistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val artistName = itemView.findViewById<TextView>(R.id.artistName)
        private val artistImage = itemView.findViewById<ImageView>(R.id.artistImage)
        private val loadingAnimation: LottieAnimationView = itemView.findViewById(R.id.loadingAnimation)

        fun bind(artist: Artist) {
            artistName.text = artist.name
            artistImage.transitionName = "artistImage_${artist.id}"

            loadingAnimation.visibility = View.VISIBLE
            artistImage.visibility = View.INVISIBLE

            if (!artist.imageUrl.isNullOrEmpty()) {
                Picasso.get()
                    .load(artist.imageUrl)
                    .error(R.drawable.user)
                    .into(artistImage, object : Callback {
                        override fun onSuccess() {
                            Log.d("Picasso", "Imagen cargada correctamente: ${artist.imageUrl}")
                            loadingAnimation.visibility = View.GONE
                            artistImage.visibility = View.VISIBLE
                        }

                        override fun onError(e: Exception?) {
                            Log.e("Picasso", "Error al cargar la imagen: ${artist.imageUrl}", e)
                            loadingAnimation.visibility = View.GONE
                            artistImage.visibility = View.VISIBLE
                        }
                    })
            } else {
                artistImage.setImageResource(R.drawable.user)
            }


            itemView.setOnClickListener {
                val bundle = Bundle().apply {
                    putString("artistId", artist.id)
                    putString("artistName", artist.name)
                    putString("artistImageUrl", artist.imageUrl)
                    putString("artistImageTransitionName", "artistImage_${artist.id}")
                }

                val extras = FragmentNavigatorExtras(
                    artistImage to "artistImage_${artist.id}"
                )

                itemView.findNavController().navigate(
                    R.id.action_searchFragment_to_artistFragment,
                    bundle,
                    null,
                    extras
                )
            }
        }
    }

    class SongDiffCallback : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(oldItem: Song, newItem: Song): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Song, newItem: Song): Boolean = oldItem == newItem
    }

    fun setCurrentPlayingSong(songId: String?) {
        val previous = currentPlayingId
        currentPlayingId = songId

        // Notifica el anterior y el nuevo para que se actualicen visualmente
        previous?.let {
            val oldIndex = items.indexOfFirst { it is SearchResult.SongItem && it.song.id == previous }
            if (oldIndex != -1) notifyItemChanged(oldIndex)
        }

        val newIndex = items.indexOfFirst { it is SearchResult.SongItem && it.song.id == songId }
        if (newIndex != -1) notifyItemChanged(newIndex)
    }

    fun submitList(newItems: List<SearchResult>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
