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
import com.example.resonant.data.models.Song
import com.example.resonant.explore.AriaPick
import com.example.resonant.utils.ImageRequestHelper

class ExplorePlaylistAdapter(
    private val onPlaylistClick: (Playlist) -> Unit
) : ListAdapter<Playlist, ExplorePlaylistAdapter.PlaylistViewHolder>(PlaylistDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_explore_playlist, parent, false)
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
        private val tracks: TextView? = itemView.findViewById(R.id.playlistTracks)

        fun bind(playlist: Playlist) {
            title.text = playlist.name
            tracks?.text = playlist.trackCountLabel()
            image.loadExploreImage(playlist.imageUrl, R.drawable.ic_playlist_stack)
            itemView.setOnClickListener { onClick(playlist) }
        }

        private fun Playlist.trackCountLabel(): String {
            return if (numberOfTracks == 1) "1 tema" else "$numberOfTracks temas"
        }
    }

    class PlaylistDiffCallback : DiffUtil.ItemCallback<Playlist>() {
        override fun areItemsTheSame(oldItem: Playlist, newItem: Playlist): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Playlist, newItem: Playlist): Boolean {
            return oldItem == newItem
        }
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
        private val artist: TextView = itemView.findViewById(R.id.albumArtist)

        fun bind(album: Album) {
            title.text = album.title?.takeIf { it.isNotBlank() } ?: "Album"
            val displayArtist = album.artistName?.takeIf { it.isNotBlank() } ?: album.artists.firstOrNull()?.name ?: ""
            artist.text = displayArtist
            artist.visibility = if (displayArtist.isNotBlank()) View.VISIBLE else View.GONE
            image.loadExploreImage(album.url, R.drawable.ic_album)
            itemView.setOnClickListener { onClick(album) }
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

/** Songs of an Explore v2 section; the click reports the position so the whole row becomes the queue. */
class ExploreSongAdapter(
    private val onSongClick: (Int) -> Unit
) : ListAdapter<Song, ExploreSongAdapter.SongViewHolder>(SongDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_explore_song, parent, false)
        return SongViewHolder(view, onSongClick)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: SongViewHolder) {
        super.onViewRecycled(holder)
        Glide.with(holder.itemView).clear(holder.image)
    }

    class SongViewHolder(
        itemView: View,
        onClick: (Int) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        val image: ImageView = itemView.findViewById(R.id.songImage)
        private val title: TextView = itemView.findViewById(R.id.songTitle)
        private val artist: TextView = itemView.findViewById(R.id.songArtist)

        init {
            itemView.setOnClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(onClick)
            }
        }

        fun bind(song: Song) {
            title.text = song.title
            artist.text = song.artistName.orEmpty()
            image.loadExploreImage(song.coverUrl ?: song.album?.url, R.drawable.ic_disc)
        }
    }

    class SongDiffCallback : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem == newItem
        }
    }
}

/** Aria's daily picks: badge + Aria's reason; long press opens the feedback menu. */
class AriaPickAdapter(
    private val onPickClick: (Int) -> Unit,
    private val onPickLongClick: (AriaPick, View) -> Unit
) : ListAdapter<AriaPick, AriaPickAdapter.PickViewHolder>(PickDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PickViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_aria_pick, parent, false)
        return PickViewHolder(view, onPickClick) { position, anchor -> onPickLongClick(getItem(position), anchor) }
    }

    override fun onBindViewHolder(holder: PickViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: PickViewHolder) {
        super.onViewRecycled(holder)
        Glide.with(holder.itemView).clear(holder.image)
    }

    class PickViewHolder(
        itemView: View,
        onClick: (Int) -> Unit,
        onLongClick: (Int, View) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        val image: ImageView = itemView.findViewById(R.id.ariaPickImage)
        private val title: TextView = itemView.findViewById(R.id.ariaPickTitle)
        private val artist: TextView = itemView.findViewById(R.id.ariaPickArtist)
        private val reason: TextView = itemView.findViewById(R.id.ariaPickReason)

        init {
            itemView.setOnClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(onClick)
            }
            itemView.setOnLongClickListener { view ->
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                onLongClick(position, view)
                true
            }
        }

        fun bind(pick: AriaPick) {
            title.text = pick.song.title
            artist.text = pick.song.artistName.orEmpty()
            reason.text = pick.reason
            image.loadExploreImage(pick.song.coverUrl ?: pick.song.album?.url, R.drawable.ic_disc)
        }
    }

    class PickDiffCallback : DiffUtil.ItemCallback<AriaPick>() {
        override fun areItemsTheSame(oldItem: AriaPick, newItem: AriaPick): Boolean {
            return oldItem.song.id == newItem.song.id
        }

        override fun areContentsTheSame(oldItem: AriaPick, newItem: AriaPick): Boolean {
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
