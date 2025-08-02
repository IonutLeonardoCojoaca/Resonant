package com.example.resonant

import android.content.Intent
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

class AlbumAdapter(private val albums: List<Album>) : RecyclerView.Adapter<AlbumAdapter.AlbumViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_album, parent, false)

        return AlbumViewHolder(view)
    }

    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        val album = albums[position]
        holder.bind(album)
    }

    override fun getItemCount(): Int = albums.size

    inner class AlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val albumImage: ImageView = itemView.findViewById(R.id.artistImage)
        private val albumName: TextView = itemView.findViewById(R.id.albumName)
        private val artistName: TextView = itemView.findViewById(R.id.artistName)

        fun bind(album: Album) {
            artistName.text = album.artistName ?: "Null"
            albumName.text = album.title ?: "Null"
            albumImage.transitionName = "albumImage_${album.id}"

            if (!album.url.isNullOrEmpty()) {
                Picasso.get().load(album.url).into(albumImage)
            }

            itemView.setOnClickListener {
                val bundle = Bundle().apply {
                    putString("albumId", album.id)
                    putString("albumFileName", album.fileName)
                    putString("albumTitle", album.title)
                    putString("albumImageTransitionName", "albumImage_${album.id}")
                }

                val extras = FragmentNavigatorExtras(
                    albumImage to "albumImage_${album.id}"
                )

                itemView.findNavController().navigate(
                    R.id.action_homeFragment_to_albumFragment,
                    bundle,
                    null,
                    extras
                )
            }
        }

    }
}