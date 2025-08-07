package com.example.resonant

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.navigation.findNavController
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso

class AlbumAdapter(private val albums: List<Album>, private val viewType: Int) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_SIMPLE = 0
        const val VIEW_TYPE_DETAILED = 1
    }

    override fun getItemViewType(position: Int): Int {
        return viewType
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_DETAILED -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_album_2, parent, false)
                DetailedAlbumViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_album, parent, false)
                SimpleAlbumViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val album = albums[position]
        when (holder) {
            is SimpleAlbumViewHolder -> holder.bind(album)
            is DetailedAlbumViewHolder -> holder.bind(album)
        }
    }

    override fun getItemCount(): Int = albums.size

    inner class SimpleAlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val albumImage: ImageView = itemView.findViewById(R.id.artistImage)
        private val albumName: TextView = itemView.findViewById(R.id.albumName)
        private val artistName: TextView = itemView.findViewById(R.id.artistName)

        fun bind(album: Album) {
            albumName.text = album.title ?: "Unknown"
            artistName.text = album.artistName ?: "Unknown"
            albumImage.transitionName = "albumImage_${album.id}"
            if (!album.url.isNullOrEmpty()) {
                Picasso.get().load(album.url).into(albumImage)
            }

            itemView.setOnClickListener {
                val bundle = Bundle().apply {
                    putString("albumId", album.id)
                    putString("albumImageTransitionName", albumImage.transitionName)
                }

                val extras = FragmentNavigatorExtras(albumImage to albumImage.transitionName)
                itemView.findNavController().navigate(R.id.action_homeFragment_to_albumFragment, bundle, null, extras)
            }
        }
    }

    inner class DetailedAlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val albumImage: ImageView = itemView.findViewById(R.id.artistImage)
        private val albumTitle: TextView = itemView.findViewById(R.id.albumTitle)
        private val albumYear: TextView = itemView.findViewById(R.id.albumReleaseYear)
        private val albumTracks: TextView = itemView.findViewById(R.id.albumNumberOfSongs)
        private val albumDuration: TextView = itemView.findViewById(R.id.albumDuration)

        fun bind(album: Album) {
            albumTitle.text = album.title ?: "Not found"
            albumYear.text = album.releaseYear.toString()
            albumTracks.text = album.numberOfTracks.toString()
            albumDuration.text = Utils.formatDuration(album.duration)

            if (!album.url.isNullOrEmpty()) {
                Picasso.get().load(album.url).into(albumImage)
            }

            itemView.setOnClickListener {
                val bundle = Bundle().apply {
                    putString("albumId", album.id)
                }
                itemView.findNavController().navigate(R.id.action_artistFragment_to_albumFragment, bundle)
            }
        }
    }
}