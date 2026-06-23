package com.example.resonant.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.resonant.R
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.Playlist
import com.example.resonant.utils.ImageRequestHelper

class ExplorePlaylistAdapter(
    private val onPlaylistClick: (Playlist) -> Unit
) : ListAdapter<Playlist, ExplorePlaylistAdapter.PlaylistViewHolder>(PlaylistDiffCallback()) {

    override fun getItemViewType(position: Int): Int {
        return if (position == 0) VIEW_TYPE_FEATURED else VIEW_TYPE_COMPACT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val layoutId = if (viewType == VIEW_TYPE_FEATURED) {
            R.layout.item_explore_playlist_featured
        } else {
            R.layout.item_explore_playlist
        }
        val view = LayoutInflater.from(parent.context)
            .inflate(layoutId, parent, false)
        return PlaylistViewHolder(view, onPlaylistClick)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: PlaylistViewHolder) {
        super.onViewRecycled(holder)
        Glide.with(holder.itemView).clear(holder.image)
    }

    class PlaylistViewHolder(
        itemView: View,
        private val onClick: (Playlist) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        val image: ImageView = itemView.findViewById(R.id.playlistImage)
        private val title: TextView = itemView.findViewById(R.id.playlistTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.playlistSubtitle)
        private val tracks: TextView = itemView.findViewById(R.id.playlistTracks)

        fun bind(playlist: Playlist) {
            title.text = playlist.name
            subtitle.text = "Por ${playlist.displayOwner()}"
            tracks.text = playlist.trackCountLabel()
            image.loadExploreImage(playlist.imageUrl, R.drawable.ic_playlist_stack)
            itemView.setOnClickListener { onClick(playlist) }
        }

        private fun Playlist.trackCountLabel(): String {
            return if (numberOfTracks == 1) "1 tema" else "$numberOfTracks temas"
        }

        private fun Playlist.displayOwner(): String {
            return when {
                isSystemPlaylist -> "Resonant"
                !ownerName.isNullOrBlank() -> ownerName!!
                else -> "Usuario"
            }
        }
    }

    class PlaylistDiffCallback : DiffUtil.ItemCallback<Playlist>() {
        override fun areItemsTheSame(oldItem: Playlist, newItem: Playlist): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Playlist, newItem: Playlist): Boolean {
            return oldItem == newItem && oldItem.ownerName == newItem.ownerName
        }
    }

    companion object {
        private const val VIEW_TYPE_FEATURED = 0
        private const val VIEW_TYPE_COMPACT = 1
    }
}

class ExploreArtistAdapter(
    private val onArtistClick: (Artist) -> Unit
) : ListAdapter<Artist, ExploreArtistAdapter.ArtistViewHolder>(ArtistDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArtistViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_explore_artist, parent, false)
        return ArtistViewHolder(view, onArtistClick)
    }

    override fun onBindViewHolder(holder: ArtistViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ArtistViewHolder) {
        super.onViewRecycled(holder)
        Glide.with(holder.itemView).clear(holder.image)
    }

    class ArtistViewHolder(
        itemView: View,
        private val onClick: (Artist) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        val image: ImageView = itemView.findViewById(R.id.artistImage)
        private val name: TextView = itemView.findViewById(R.id.artistName)

        fun bind(artist: Artist) {
            name.text = artist.name
            image.loadExploreImage(artist.url, R.drawable.ic_user, circle = true)
            itemView.setOnClickListener { onClick(artist) }
        }
    }

    class ArtistDiffCallback : DiffUtil.ItemCallback<Artist>() {
        override fun areItemsTheSame(oldItem: Artist, newItem: Artist): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Artist, newItem: Artist): Boolean {
            return oldItem == newItem
        }
    }
}

class ExploreAlbumAdapter(
    private val onAlbumClick: (Album) -> Unit
) : ListAdapter<Album, ExploreAlbumAdapter.AlbumViewHolder>(AlbumDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_explore_album, parent, false)
        return AlbumViewHolder(view, onAlbumClick)
    }

    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: AlbumViewHolder) {
        super.onViewRecycled(holder)
        Glide.with(holder.itemView).clear(holder.image)
    }

    class AlbumViewHolder(
        itemView: View,
        private val onClick: (Album) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        val image: ImageView = itemView.findViewById(R.id.albumImage)
        private val title: TextView = itemView.findViewById(R.id.albumTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.albumSubtitle)

        fun bind(album: Album) {
            title.text = album.title?.takeIf { it.isNotBlank() } ?: "Album"
            subtitle.text = album.artistLabel()
            image.loadExploreImage(album.url, R.drawable.ic_album)
            itemView.setOnClickListener { onClick(album) }
        }

        private fun Album.artistLabel(): String {
            val artist = artistName?.takeIf { it.isNotBlank() }
                ?: artists.joinToString(", ") { it.name }.takeIf { it.isNotBlank() }
                ?: "Artista desconocido"
            return if (releaseYear > 0) "$artist - $releaseYear" else artist
        }
    }

    class AlbumDiffCallback : DiffUtil.ItemCallback<Album>() {
        override fun areItemsTheSame(oldItem: Album, newItem: Album): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Album, newItem: Album): Boolean {
            return oldItem == newItem
        }
    }
}

private fun ImageView.loadExploreImage(
    url: String?,
    placeholder: Int,
    circle: Boolean = false
) {
    val request = Glide.with(this)
        .load(url?.takeIf { it.isNotBlank() }?.let { ImageRequestHelper.buildGlideModel(context, it) })
        .override(420, 420)
        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
        .timeout(10_000)
        .placeholder(placeholder)
        .error(placeholder)
        .dontAnimate()

    if (circle) {
        request.circleCrop()
    } else {
        request.centerCrop()
    }

    request.into(this)
}
